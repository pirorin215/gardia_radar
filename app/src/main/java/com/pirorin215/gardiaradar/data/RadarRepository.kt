package com.pirorin215.gardiaradar.data

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.pirorin215.gardiaradar.service.RadarNotificationManager
import com.pirorin215.gardiaradar.service.WearableDataHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.os.Build
import android.os.Environment
import kotlin.math.roundToInt
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.util.*

/**
 * 純粋なGATT通信クラス。
 * 接続ライフサイクル（再接続・スキャン起動）は RadarConnectionManager が担当。
 *
 * 責務:
 * - BluetoothGattCallback の処理
 * - connectGatt / discoverServices / MTU要求
 * - レーダーデータのデコード
 * - 電池残量の管理
 * - 通知の有効化/無効化
 * - 通信セッションの記録（ON/OFF両方）
 */
@SuppressLint("MissingPermission")
class RadarRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val appSettingsRepository: AppSettingsRepository,
    private val notificationManager: RadarNotificationManager,
    private val wearableDataHost: WearableDataHost,
    private val batterySessionRepository: BatterySessionRepository
) {
    private val TAG = "RadarRepository"
    private val TARGET_CHAR_UUID = UUID.fromString("f3641401-00b0-4240-ba50-05ca45bf8abc")
    private val BATTERY_LEVEL_CHAR_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var gatt: BluetoothGatt? = null
    private var phoneNotificationMode: NotificationMode = NotificationMode.FIRST_ONLY
    private var wearNotificationMode: NotificationMode = NotificationMode.FIRST_ONLY

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName = _connectedDeviceName.asStateFlow()

    private val _targets = MutableStateFlow<List<RadarTarget>>(emptyList())
    val targets = _targets.asStateFlow()

    private val _rawPacket = MutableStateFlow("")
    val rawPacket = _rawPacket.asStateFlow()

    // フィールドテスト用：直近パケットのリングバッファ（30秒ウィンドウ）
    private data class RecentPacket(val receivedAtMs: Long, val hex: String, val dec: String, val decoded: String)
    private val recentPackets = mutableListOf<RecentPacket>()
    private val RECENT_PACKET_WINDOW_MS = 30_000L

    private val _radarBatteryLevel = MutableStateFlow(-1)
    val radarBatteryLevel = _radarBatteryLevel.asStateFlow()

    private val _wearBatteryLevel = MutableStateFlow(-1)
    val wearBatteryLevel = _wearBatteryLevel.asStateFlow()

    private val _connectionElapsedSeconds = MutableStateFlow(0L)
    val connectionElapsedSeconds = _connectionElapsedSeconds.asStateFlow()

    private val _rssi = MutableStateFlow<Int?>(null)
    val rssi = _rssi.asStateFlow()

    val suppressionRemainingSeconds = notificationManager.suppressionRemainingSeconds

    private var batteryChar: BluetoothGattCharacteristic? = null

    // セッション管理
    private var currentSession: BatterySession? = null
    // 切断直後に保存したCONNECTEDセッションのID。
    // wear→phoneのウォッチ電池受信がsaveCurrentSessionより後に届くため、
    // 受信時にendWatchBatteryを反映するために保持する（接続・切断の2タイミングのみ通信）。
    private var pendingEndWatchBatterySessionId: String? = null
    private var pendingEndWatchBatteryDeadlineMs: Long = 0L
    private var elapsedTimerJob: kotlinx.coroutines.Job? = null

    // Target notification control
    private var lastNotifiedTargetState = false
    private var lastTargetNotificationTime = 0L
    private val TARGET_NOTIFICATION_COOLDOWN_MS = 30_000L

    // 接続監視タイマー（データ受信タイムアウト検知）
    private var lastDataReceivedTime = 0L
    private var connectionWatchdogJob: kotlinx.coroutines.Job? = null
    private val CONNECTION_TIMEOUT_MS = 15_000L // 15秒間データ受信がない場合は切断とみなす

    // RSSI読み取りタイマー
    private var rssiReadJob: kotlinx.coroutines.Job? = null
    private val RSSI_READ_INTERVAL_MS = 5000L // 5秒ごとにRSSIを読む

    // RSSI切断ロジック
    private var consecutivePoorRssiCount = 0 // RSSIがしきい値を下回った連続回数

    // 通信種別カウント（currentSessionとは独立して管理）
    @Volatile private var connectionStateCommCount = 0
    @Volatile private var batteryCommCount = 0
    @Volatile private var alertCommCount = 0
    @Volatile private var targetsCommCount = 0
    @Volatile private var powerSavingCommCount = 0

    // セッション遷移の排他制御
    private val sessionLock = Any()

    init {
        scope.launch {
            appSettingsRepository.getFlow(Settings.PHONE_NOTIFICATION_MODE).collectLatest { mode ->
                phoneNotificationMode = mode
            }
        }

        scope.launch {
            appSettingsRepository.getFlow(Settings.WEAR_NOTIFICATION_MODE).collectLatest { mode ->
                wearNotificationMode = mode
            }
        }

        // 走行セッション中のモード変更を監視
        scope.launch {
            appSettingsRepository.getFlow(Settings.WEAR_POWER_SAVING_MODE).collectLatest { enabled ->
                currentSession?.let { session ->
                    currentSession = if (enabled) {
                        session.copy(wasPowerSavingMode = true)
                    } else {
                        session.copy(wasNormalMode = true)
                    }
                }
            }
        }

        // ウォッチの音・振動設定変更を監視して同期
        scope.launch {
            kotlinx.coroutines.flow.combine(
                appSettingsRepository.getFlow(Settings.WEAR_ALERT_SOUND_ENABLED),
                appSettingsRepository.getFlow(Settings.WEAR_ALERT_VIBRATION_ENABLED)
            ) { soundEnabled, vibrationEnabled ->
                soundEnabled to vibrationEnabled
            }.distinctUntilChanged().collectLatest { (soundEnabled, vibrationEnabled) ->
                wearableDataHost.putAlertSettingsData(soundEnabled, vibrationEnabled)
            }
        }
    }

    // --- 通信種別カウント ---

    fun incrementConnectionStateCount() {
        connectionStateCommCount++
        Log.d(TAG, "Comm [接続状態]: $connectionStateCommCount")
    }

    fun incrementBatteryCount() {
        batteryCommCount++
        Log.d(TAG, "Comm [バッテリー]: $batteryCommCount")
    }

    fun incrementAlertCount() {
        alertCommCount++
        Log.d(TAG, "Comm [アラート]: $alertCommCount")
    }

    fun incrementTargetsCount() {
        targetsCommCount++
        Log.d(TAG, "Comm [ターゲット]: $targetsCommCount")
    }

    fun incrementPowerSavingCount() {
        powerSavingCommCount++
        Log.d(TAG, "Comm [省電力]: $powerSavingCommCount")
    }

    // --- RadarConnectionManager から呼ばれる公開メソッド ---

    fun setConnectionState(state: ConnectionState) {
        synchronized(sessionLock) {
            val oldState = _connectionState.value
            _connectionState.value = state

            if (oldState !is ConnectionState.Connected && state is ConnectionState.Connected) {
                // 接続: OFFセッション保存 → ONセッション開始
                lastNotifiedTargetState = false
                lastTargetNotificationTime = 0L
                saveCurrentSession()
                startConnectedSession()
                startElapsedTimer()
                startConnectionWatchdog()
                startRssiReader()
            } else if (oldState is ConnectionState.Connected && state !is ConnectionState.Connected) {
                // 切断: ONセッション保存 → OFFセッション開始
                saveCurrentSession()
                startDisconnectedSession()
                stopElapsedTimer()
                stopConnectionWatchdog()
                stopRssiReader()
            }
        }
    }

    fun setConnectedDeviceName(name: String?) {
        _connectedDeviceName.value = name
    }

    fun setRssi(rssi: Int) {
        _rssi.value = rssi
    }

    fun setWearBatteryLevel(level: Int) {
        _wearBatteryLevel.value = level
        val now = LocalDateTime.now()
        val session = currentSession
        // startWatchBattery の補完は CONNECTED セッション中のみ行う。
        // 切断後に startDisconnectedSession() が currentSession を設定するため、
        // session != null だけで判定すると、切断応答として遅延到着したウォッチ電池が
        // DISCONNECTED セッションの startWatchBattery を埋める処理に吸われ、
        // 直近の CONNECTED セッションの endWatchBattery 更新（pending 経路）へ到達しなくなる。
        // これが「切断時のウォッチ電池が開始時と同じ値になる」不具合の原因だった。
        if (session != null && session.type == SessionType.CONNECTED) {
            if (session.startWatchBattery < 0) {
                currentSession = session.copy(
                    startWatchBattery = level,
                    watchBatteryReceivedTime = now
                )
                Log.d(TAG, "Updated session startWatchBattery to: $level% at ${formatTime(now)}")
            }
            return
        }
        // 切断直後: saveCurrentSessionより後にwear→phoneのウォッチ電池が届くため、
        // 直近のCONNECTEDセッションのendWatchBatteryを反映する。
        val id = pendingEndWatchBatterySessionId ?: return
        pendingEndWatchBatterySessionId = null
        if (System.currentTimeMillis() > pendingEndWatchBatteryDeadlineMs) return
        batterySessionRepository.updateEndWatchBattery(id, level)
        Log.d(TAG, "Updated last session endWatchBattery to: $level% at ${formatTime(now)}")
    }

    fun resetState() {
        _connectionState.value = ConnectionState.Disconnected
        _connectedDeviceName.value = null
        _targets.value = emptyList()
        _rawPacket.value = ""
        _radarBatteryLevel.value = -1
        _wearBatteryLevel.value = -1
        _rssi.value = null
        batteryChar = null
        lastNotifiedTargetState = false
        lastTargetNotificationTime = 0L
    }

    fun connect(device: android.bluetooth.BluetoothDevice) {
        Log.d(TAG, "Connecting to device: ${device.address}")
        _connectedDeviceName.value = device.name
        _connectionState.value = ConnectionState.Connecting
        gatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null

        // ON→OFF セッション遷移（synchronizedで排他制御）
        synchronized(sessionLock) {
            if (currentSession?.type == SessionType.CONNECTED) {
                saveCurrentSession()
                startDisconnectedSession()
            }
        }

        _connectionState.value = ConnectionState.Disconnected
        _connectedDeviceName.value = null
        _targets.value = emptyList()
        _rawPacket.value = ""
        _radarBatteryLevel.value = -1
        _wearBatteryLevel.value = -1
        _rssi.value = null
        batteryChar = null
        notificationManager.handleBatteryUpdate(-1)
        stopElapsedTimer()
        stopConnectionWatchdog()
        stopRssiReader()
        _connectionElapsedSeconds.value = 0L
    }

    fun close() {
        gatt?.close()
        gatt = null
    }

    // --- セッション管理 ---

    private fun resetCommCounts() {
        connectionStateCommCount = 0
        batteryCommCount = 0
        alertCommCount = 0
        targetsCommCount = 0
        powerSavingCommCount = 0
    }

    private fun startConnectedSession() {
        resetCommCounts()

        val phoneBat = getPhoneBatteryLevel()
        val radarBat = _radarBatteryLevel.value
        val now = LocalDateTime.now()

        // 同期的にセッションを作成し即永続化する（race対策 + プロセス終了対策）。
        // 従来はscope.launch内でcurrentSessionを設定していたため、接続確立とcurrentSession代入の
        // 間に隙間が生じ、直後の切断でON/OFFセッションの対応が崩れるraceがあった。
        val session = BatterySession(
            id = UUID.randomUUID().toString(),
            startTime = now,
            sessionStartTime = now,
            startPhoneBattery = phoneBat,
            startWatchBattery = -1,
            startRadarBattery = radarBat,
            wasPowerSavingMode = false,
            wasNormalMode = true,
            type = SessionType.CONNECTED
        )
        currentSession = session
        batterySessionRepository.addSession(session)
        Log.d(TAG, "ON session started at ${formatTime(now)}: phone=$phoneBat, radar=$radarBat")

        // 省電力モード設定のみ非同期で取得し、セッションへ反映する
        scope.launch {
            val enabled = appSettingsRepository.getFlow(Settings.WEAR_POWER_SAVING_MODE).first()
            currentSession?.let { s ->
                val updated = s.copy(wasPowerSavingMode = enabled, wasNormalMode = !enabled)
                currentSession = updated
                batterySessionRepository.updateSession(updated)
            }
        }
    }

    private fun startDisconnectedSession() {
        resetCommCounts()

        val now = LocalDateTime.now()
        val session = BatterySession(
            id = UUID.randomUUID().toString(),
            startTime = now,
            startPhoneBattery = -1,
            startWatchBattery = -1,
            startRadarBattery = -1,
            type = SessionType.DISCONNECTED
        )
        // 即永続化: OFFセッションは「次回接続時」にしか保存されない設計だったため、
        // プロセス終了でcurrentSessionごと消滅しOFFだけ履歴に残らない主因だった。
        // 開始時にendTime=nullで保存し、接続時にupdateSessionで終了時刻を確定する。
        currentSession = session
        batterySessionRepository.addSession(session)
        Log.d(TAG, "OFF session started at ${formatTime(now)}")
    }

    private fun saveCurrentSession() {
        val session = currentSession ?: return

        val finalSession = when (session.type) {
            SessionType.CONNECTED -> {
                val phoneBat = getPhoneBatteryLevel()
                val watchBat = _wearBatteryLevel.value
                val radarBat = _radarBatteryLevel.value
                session.copy(
                    endTime = LocalDateTime.now(),
                    startWatchBattery = if (session.startWatchBattery < 0) watchBat else session.startWatchBattery,
                    startRadarBattery = if (session.startRadarBattery < 0) radarBat else session.startRadarBattery,
                    endPhoneBattery = phoneBat,
                    endWatchBattery = watchBat,
                    endRadarBattery = radarBat,
                    connectionStateCount = connectionStateCommCount,
                    batteryCount = batteryCommCount,
                    alertCount = alertCommCount,
                    targetsCount = targetsCommCount,
                    powerSavingCount = powerSavingCommCount
                )
            }
            SessionType.DISCONNECTED -> {
                session.copy(
                    endTime = LocalDateTime.now(),
                    connectionStateCount = connectionStateCommCount,
                    batteryCount = batteryCommCount,
                    alertCount = alertCommCount,
                    targetsCount = targetsCommCount,
                    powerSavingCount = powerSavingCommCount
                )
            }
        }

        if (finalSession.type == SessionType.CONNECTED) {
            // 切断後のwear→phoneウォッチ電池受信でendWatchBatteryを反映するため、
            // セッションIDを記録（通信ラグで受信が保存後に届く）。10秒間のみ有効。
            pendingEndWatchBatterySessionId = finalSession.id
            pendingEndWatchBatteryDeadlineMs = System.currentTimeMillis() + 10_000L
        }
        currentSession = null
        batterySessionRepository.updateSession(finalSession)
        Log.d(TAG, "${session.type} session saved: ${finalSession.durationSeconds}s, comm=${finalSession.totalCommunicationCount}")
    }

    private fun startElapsedTimer() {
        _connectionElapsedSeconds.value = 0L
        elapsedTimerJob?.cancel()
        elapsedTimerJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000L)
                val session = currentSession ?: return@launch
                val startInstant = java.time.ZonedDateTime.of(session.startTime, java.time.ZoneId.systemDefault()).toInstant()
                val nowInstant = java.time.ZonedDateTime.now().toInstant()
                val elapsedSeconds = java.time.Duration.between(startInstant, nowInstant).seconds
                _connectionElapsedSeconds.value = elapsedSeconds
            }
        }
        Log.d(TAG, "Elapsed timer started")
    }

    private fun stopElapsedTimer() {
        elapsedTimerJob?.cancel()
        elapsedTimerJob = null
        Log.d(TAG, "Elapsed timer stopped")
    }

    private fun startConnectionWatchdog() {
        lastDataReceivedTime = System.currentTimeMillis()
        connectionWatchdogJob?.cancel()
        connectionWatchdogJob = scope.launch {
            try {
                Log.d(TAG, "Connection watchdog loop started")
                while (true) {
                    delay(5000L) // 5秒ごとにチェック
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastDataReceivedTime
                    Log.d(TAG, "Watchdog check: elapsed=${elapsed}ms, timeout=${CONNECTION_TIMEOUT_MS}ms")

                    if (elapsed > CONNECTION_TIMEOUT_MS) {
                        Log.w(TAG, "Connection timeout: ${elapsed}ms without data. Forcing disconnect.")
                        gatt?.disconnect()
                        // disconnect()を呼んだ後はコールバックを待つ必要があるのでループを終了
                        break
                    }
                }
                Log.d(TAG, "Connection watchdog loop ended")
            } catch (e: Exception) {
                Log.e(TAG, "Watchdog exception: ${e.message}", e)
            }
        }
        Log.d(TAG, "Connection watchdog started (timeout=${CONNECTION_TIMEOUT_MS}ms)")
    }

    private fun stopConnectionWatchdog() {
        connectionWatchdogJob?.cancel()
        connectionWatchdogJob = null
        Log.d(TAG, "Connection watchdog stopped")
    }

    private fun startRssiReader() {
        rssiReadJob?.cancel()
        rssiReadJob = scope.launch {
            try {
                Log.d(TAG, "RSSI reader started")
                while (gatt != null) {
                    delay(RSSI_READ_INTERVAL_MS)
                    gatt?.let {
                        if (!it.readRemoteRssi()) {
                            Log.w(TAG, "Failed to request RSSI read")
                        }
                    }
                }
                Log.d(TAG, "GATT is null, stopping RSSI reader")
            } catch (e: Exception) {
                Log.e(TAG, "RSSI reader exception: ${e.message}", e)
            }
        }
    }

    private fun stopRssiReader() {
        rssiReadJob?.cancel()
        rssiReadJob = null
        Log.d(TAG, "RSSI reader stopped")
    }

    private fun updateLastDataReceivedTime() {
        lastDataReceivedTime = System.currentTimeMillis()
    }

    private fun formatTime(time: LocalDateTime): String {
        return try {
            time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        } catch (e: Exception) {
            "不明"
        }
    }

    private fun getPhoneBatteryLevel(): Int {
        val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100) / scale else -1
    }

    // --- GATT Callback（純粋なGATT通信処理のみ）---

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "Connected to GATT server. Requesting MTU...")
                    gatt.requestMtu(512)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "Successfully disconnected from GATT server.")
                    gatt.close()
                    this@RadarRepository.gatt = null
                    setConnectionState(ConnectionState.Disconnected)
                }
            } else {
                Log.e(TAG, "GATT Error: status=$status for ${gatt.device.address}")
                gatt.close()
                this@RadarRepository.gatt = null
                setConnectionState(ConnectionState.Error("GATT Error $status"))
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged: mtu=$mtu, status=$status.")
            scope.launch {
                delay(600)
                Log.d(TAG, "Discovering services...")
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered: status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "=== Device Service Enumeration ===")
                for (service in gatt.services) {
                    Log.d(TAG, "Service: ${service.uuid} (${service.characteristics.size} chars)")
                    for (char in service.characteristics) {
                        val props = mutableListOf<String>()
                        if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) props.add("READ")
                        if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) props.add("NOTIFY")
                        if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) props.add("WRITE")
                        if (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) props.add("INDICATE")
                        Log.d(TAG, "  Char: ${char.uuid} properties=[${props.joinToString(",")}]")
                    }
                }
                Log.d(TAG, "=== End Service Enumeration ===")

                var found = false
                for (service in gatt.services) {
                    val characteristic = service.getCharacteristic(TARGET_CHAR_UUID)
                    if (characteristic != null) {
                        Log.d(TAG, "Found Radar Characteristic: ${characteristic.uuid} in service: ${service.uuid}")
                        enableNotifications(gatt, characteristic)
                        setConnectionState(ConnectionState.Connected)
                        found = true
                    }

                    val battery = service.getCharacteristic(BATTERY_LEVEL_CHAR_UUID)
                    if (battery != null) {
                        val batProps = mutableListOf<String>()
                        if (battery.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) batProps.add("READ")
                        if (battery.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) batProps.add("NOTIFY")
                        Log.d(TAG, "Found Battery Level Characteristic in service: ${service.uuid}, properties=[${batProps.joinToString(",")}]")
                        batteryChar = battery
                    }
                }
                if (!found) {
                    Log.e(TAG, "Radar Characteristic NOT found in any service!")
                } else {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "onDescriptorWrite: uuid=${descriptor.uuid}, status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "SUCCESS: Notifications enabled on Gardia!")
                batteryChar?.let { battery ->
                    if (battery.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                        Log.d(TAG, "Reading battery level...")
                        gatt.readCharacteristic(battery)
                    } else {
                        Log.d(TAG, "Enabling battery level notifications (no READ property)...")
                        enableNotifications(gatt, battery)
                        batteryChar = null
                    }
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid == BATTERY_LEVEL_CHAR_UUID) {
                val value = characteristic.value
                if (status == BluetoothGatt.GATT_SUCCESS && value != null) {
                    updateBatteryLevel(value, "read")
                }
                enableBatteryNotificationIfNeeded(gatt)
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (characteristic.uuid == BATTERY_LEVEL_CHAR_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                updateBatteryLevel(value, "read")
                enableBatteryNotificationIfNeeded(gatt)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value
            if (value != null) {
                when (characteristic.uuid) {
                    TARGET_CHAR_UUID -> decodeRadarData(value)
                    BATTERY_LEVEL_CHAR_UUID -> updateBatteryLevel(value, "notification")
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            when (characteristic.uuid) {
                TARGET_CHAR_UUID -> decodeRadarData(value)
                BATTERY_LEVEL_CHAR_UUID -> updateBatteryLevel(value, "notification")
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "onReadRemoteRssi: rssi=$rssi dBm")
                _rssi.value = rssi

                // RSSI切断ロジックをチェック
                scope.launch {
                    checkRssiDisconnect(rssi)
                }
            } else {
                Log.w(TAG, "onReadRemoteRssi failed: status=$status")
            }
        }
    }

    /**
     * RSSIに基づく自動切断判定を実行します。
     * 接続後のRSSI監視用で、切断時のしきい値（RSSI_DISCONNECT_THRESHOLD）を使用します。
     * 接続時のフィルタリングは RadarConnectionManager で行われます。
     */
    private suspend fun checkRssiDisconnect(currentRssi: Int) {
        // 設定を取得
        val enabled = appSettingsRepository.getFlow(Settings.RSSI_DISCONNECT_ENABLED).first()
        if (!enabled) {
            consecutivePoorRssiCount = 0
            return
        }

        val threshold = appSettingsRepository.getFlow(Settings.RSSI_DISCONNECT_THRESHOLD).first()
        val requiredCount = appSettingsRepository.getFlow(Settings.RSSI_DISCONNECT_COUNT).first()

        if (currentRssi < threshold) {
            consecutivePoorRssiCount++
            Log.w(TAG, "Poor RSSI: $currentRssi dBm (threshold: $threshold dBm, count: $consecutivePoorRssiCount/$requiredCount)")

            if (consecutivePoorRssiCount >= requiredCount) {
                Log.e(TAG, "RSSI too poor for $requiredCount consecutive times. Disconnecting...")
                consecutivePoorRssiCount = 0
                gatt?.disconnect()
            }
        } else {
            // RSSIが改善した場合はカウントをリセット
            if (consecutivePoorRssiCount > 0) {
                Log.d(TAG, "RSSI improved: $currentRssi dBm (threshold: $threshold dBm). Resetting count.")
                consecutivePoorRssiCount = 0
            }
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun enableBatteryNotificationIfNeeded(gatt: BluetoothGatt) {
        batteryChar?.let { battery ->
            if (battery.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                Log.d(TAG, "Enabling battery level notifications after read...")
                enableNotifications(gatt, battery)
            }
            batteryChar = null
        }
    }

    private fun updateBatteryLevel(value: ByteArray, source: String) {
        if (value.isNotEmpty()) {
            updateLastDataReceivedTime()
            val level = value[0].toInt() and 0xFF
            val hex = value.joinToString(" ") { "%02x".format(it) }
            Log.d(TAG, "Radar battery level: $level% (source=$source, raw=[$hex], ${value.size} bytes)")
            _radarBatteryLevel.value = level
            incrementBatteryCount()
            wearableDataHost.putRadarBatteryLevel(level)
            notificationManager.handleBatteryUpdate(level)

            currentSession?.let { session ->
                val now = LocalDateTime.now()
                if (session.startRadarBattery < 0) {
                    currentSession = session.copy(
                        startRadarBattery = level,
                        radarBatteryReceivedTime = now
                    )
                    Log.d(TAG, "Updated session startRadarBattery to: $level% at ${formatTime(now)}")
                }
            }
        }
    }

    private fun decodeRadarData(data: ByteArray) {
        if (data.isEmpty()) return

        updateLastDataReceivedTime()

        // レーダーパケット受信回数をカウント
        currentSession?.let { session ->
            currentSession = session.copy(radarPacketCount = session.radarPacketCount + 1)
        }

        val hexString = data.joinToString("") { "%02x".format(it) }
        _rawPacket.value = hexString

        val newTargets = mutableListOf<RadarTarget>()
        val dataInt = data.map { it.toInt() and 0xFF }

        // Bryton Gardia は ANT+ RDR 互換のビットパック構造を使用
        // 詳細仕様・出典は docs/radar_packet_decoding.md を参照
        // （UUID 手がかり: pycycling Issue#42、ビット構造: ANT+ Bike Radar プロファイル）
        // byte[0]:    ページ番号 (0x30 等)
        // byte[1]:    4脅威のレベル（各2bit: bit0-1=脅威1, bit2-3=脅威2, bit4-5=脅威3, bit6-7=脅威4）
        // byte[3-5]:  4脅威の距離（各6bit、×3.125 で m）
        // byte[6-7]:  4脅威の速度（各4bit、×3.04×3.6 で km/h）
        // 存在判定はレベル > 0（レベル0 = 脅威なし）
        if (dataInt.size >= 8 && dataInt[0] == 0x30) {
            val levels = dataInt[1]
            val t1Level = (levels shr 0) and 0b11
            val t2Level = (levels shr 2) and 0b11
            val t3Level = (levels shr 4) and 0b11
            val t4Level = (levels shr 6) and 0b11

            // 距離: byte[3-5] に4脅威分が6bitずつリトルエンディアンでパック
            val d0 = dataInt[3]
            val d1 = dataInt[4]
            val d2 = dataInt[5]
            val t1Dist = (d0 shr 0) and 0b111111
            val t2Dist = ((d0 shr 6) and 0b11) or (((d1 shr 0) and 0b1111) shl 2)
            val t3Dist = ((d1 shr 4) and 0b1111) or (((d2 shr 0) and 0b11) shl 4)
            val t4Dist = (d2 shr 2) and 0b111111

            // 速度: byte[6-7] に4脅威分が4bitずつパック
            val s0 = dataInt[6]
            val s1 = dataInt[7]
            val t1Speed = (s0 shr 0) and 0b1111
            val t2Speed = (s0 shr 4) and 0b1111
            val t3Speed = (s1 shr 0) and 0b1111
            val t4Speed = (s1 shr 4) and 0b1111

            if (t1Level > 0) newTargets.add(RadarTarget(1, (t1Dist * 3.125).roundToInt(), (t1Speed * 10.944).roundToInt(), t1Level))
            if (t2Level > 0) newTargets.add(RadarTarget(2, (t2Dist * 3.125).roundToInt(), (t2Speed * 10.944).roundToInt(), t2Level))
            if (t3Level > 0) newTargets.add(RadarTarget(3, (t3Dist * 3.125).roundToInt(), (t3Speed * 10.944).roundToInt(), t3Level))
            if (t4Level > 0) newTargets.add(RadarTarget(4, (t4Dist * 3.125).roundToInt(), (t4Speed * 10.944).roundToInt(), t4Level))
        }

        _targets.value = newTargets
        val decodedStr = newTargets.joinToString(", ") { "id=${it.id} d=${it.distance} s=${it.speed} t=${it.threat}" }

        // 直近パケットをリングバッファに記録（フィールドテスト用ファイル保存向け）
        val nowMs = System.currentTimeMillis()
        synchronized(recentPackets) {
            recentPackets.add(RecentPacket(nowMs, hexString, dataInt.joinToString(","), decodedStr))
            val cutoff = nowMs - RECENT_PACKET_WINDOW_MS
            while (recentPackets.isNotEmpty() && recentPackets.first().receivedAtMs < cutoff) {
                recentPackets.removeAt(0)
            }
        }

        // 車両状態変化を検出してアラート通知
        val hasTargets = newTargets.isNotEmpty()
        val stateChanged = (lastNotifiedTargetState != hasTargets)

        if (stateChanged) {
            val now = System.currentTimeMillis()
            val elapsed = now - lastTargetNotificationTime

            if (elapsed >= TARGET_NOTIFICATION_COOLDOWN_MS) {
                lastNotifiedTargetState = hasTargets
                lastTargetNotificationTime = now
                incrementAlertCount()
                wearableDataHost.putAlertData(hasTargets)
                Log.d(TAG, "Alert notification sent: ${if(hasTargets) "detected" else "cleared"}")
            } else {
                Log.d(TAG, "Alert notification skipped: cooldown (${elapsed}ms < ${TARGET_NOTIFICATION_COOLDOWN_MS}ms)")
            }
        }

        // 省電力モードではターゲットデータ送信をスキップ
        val isPowerSaving = currentSession?.wasPowerSavingMode == true
        if (!isPowerSaving) {
            incrementTargetsCount()
            wearableDataHost.putTargetsData(newTargets)
        }

        notificationManager.handleRadarUpdate(newTargets, phoneNotificationMode, wearNotificationMode)
    }

    /**
     * 直近のレーダーパケットをファイルに保存（フィールドテスト用）。
     * 保存先: /Documents/GardiaRadar/logs/radar_yyyyMMdd_HHmmss.txt
     * ファイラーアプリから取り出して解析に渡すことを想定。
     * @param note 保存時の状況メモ（任意）。ファイルヘッダに記録される。空でも可。
     * @return 保存したファイルの絶対パス、失敗時は null
     */
    fun saveRecentPacketsToFile(note: String): String? {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "radar_$timestamp.txt"

            val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val radarDir = File(documentsDir, "GardiaRadar/logs")
            if (!radarDir.exists()) radarDir.mkdirs()

            val snapshots: List<RecentPacket>
            synchronized(recentPackets) {
                snapshots = recentPackets.toList()
            }

            val content = StringBuilder()
            content.append("=== GardiaRadar Raw Packet Snapshot ===\n")
            content.append("Saved: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
            content.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})\n")
            content.append("Window: last ${RECENT_PACKET_WINDOW_MS / 1000}s (${snapshots.size} packets)\n")
            if (note.isNotBlank()) {
                content.append("Note: $note\n")
            }
            content.append("\n=== Recent Packets ===\n")
            val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            snapshots.forEach { p ->
                content.append("[${timeFmt.format(Date(p.receivedAtMs))}] hex=${p.hex} dec=[${p.dec}] -> [${p.decoded}]\n")
            }

            val file = File(radarDir, fileName)
            file.writeText(content.toString())
            Log.d(TAG, "Saved ${snapshots.size} packets to ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save recent packets: ${e.message}", e)
            null
        }
    }
}
