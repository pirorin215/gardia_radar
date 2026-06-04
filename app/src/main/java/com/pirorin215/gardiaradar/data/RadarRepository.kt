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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    private val _radarBatteryLevel = MutableStateFlow(-1)
    val radarBatteryLevel = _radarBatteryLevel.asStateFlow()

    private val _wearBatteryLevel = MutableStateFlow(-1)
    val wearBatteryLevel = _wearBatteryLevel.asStateFlow()

    private val _connectionElapsedSeconds = MutableStateFlow(0L)
    val connectionElapsedSeconds = _connectionElapsedSeconds.asStateFlow()

    val suppressionRemainingSeconds = notificationManager.suppressionRemainingSeconds

    private var batteryChar: BluetoothGattCharacteristic? = null

    // セッション管理
    private var currentSession: BatterySession? = null
    private var elapsedTimerJob: kotlinx.coroutines.Job? = null

    // Target notification control
    private var lastNotifiedTargetState = false
    private var lastTargetNotificationTime = 0L
    private val TARGET_NOTIFICATION_COOLDOWN_MS = 30_000L

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
            } else if (oldState is ConnectionState.Connected && state !is ConnectionState.Connected) {
                // 切断: ONセッション保存 → OFFセッション開始
                saveCurrentSession()
                startDisconnectedSession()
                stopElapsedTimer()
            }
        }
    }

    fun setConnectedDeviceName(name: String?) {
        _connectedDeviceName.value = name
    }

    fun setWearBatteryLevel(level: Int) {
        _wearBatteryLevel.value = level
        currentSession?.let { session ->
            if (session.startWatchBattery < 0) {
                val now = LocalDateTime.now()
                currentSession = session.copy(
                    startWatchBattery = level,
                    watchBatteryReceivedTime = now
                )
                Log.d(TAG, "Updated session startWatchBattery to: $level% at ${formatTime(now)}")
            }
        }
    }

    fun resetState() {
        _connectionState.value = ConnectionState.Disconnected
        _connectedDeviceName.value = null
        _targets.value = emptyList()
        _rawPacket.value = ""
        _radarBatteryLevel.value = -1
        _wearBatteryLevel.value = -1
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
        batteryChar = null
        notificationManager.handleBatteryUpdate(-1)
        stopElapsedTimer()
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

        scope.launch {
            val enabled = appSettingsRepository.getFlow(Settings.WEAR_POWER_SAVING_MODE).first()
            currentSession = BatterySession(
                id = UUID.randomUUID().toString(),
                startTime = now,
                sessionStartTime = now,
                startPhoneBattery = phoneBat,
                startWatchBattery = -1,
                startRadarBattery = radarBat,
                wasPowerSavingMode = enabled,
                wasNormalMode = !enabled,
                type = SessionType.CONNECTED
            )
            Log.d(TAG, "ON session started at ${formatTime(now)}: phone=$phoneBat, radar=$radarBat, ps=$enabled")
        }
    }

    private fun startDisconnectedSession() {
        resetCommCounts()

        val now = LocalDateTime.now()
        currentSession = BatterySession(
            id = UUID.randomUUID().toString(),
            startTime = now,
            startPhoneBattery = -1,
            startWatchBattery = -1,
            startRadarBattery = -1,
            type = SessionType.DISCONNECTED
        )
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

        currentSession = null
        batterySessionRepository.addSession(finalSession)
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

        // レーダーパケット受信回数をカウント
        currentSession?.let { session ->
            currentSession = session.copy(radarPacketCount = session.radarPacketCount + 1)
        }

        val hexString = data.joinToString("") { "%02x".format(it) }
        _rawPacket.value = hexString

        val newTargets = mutableListOf<RadarTarget>()
        val dataInt = data.map { it.toInt() and 0xFF }

        // Byte 0: Header 0x30
        if (dataInt[0] == 0x30) {
            if (dataInt[3] > 0) {
                newTargets.add(RadarTarget(1, dataInt[3], dataInt[2], dataInt[1]))
            }
            if (dataInt[6] > 1) {
                newTargets.add(RadarTarget(2, dataInt[6], dataInt[5], dataInt[4]))
            }
        }

        // Byte 8: Header 0x31
        if (dataInt.size >= 15 && dataInt[8] == 0x31) {
            if (dataInt[11] > 0) {
                newTargets.add(RadarTarget(3, dataInt[11], dataInt[10], dataInt[9]))
            }
            if (dataInt[14] > 0) {
                newTargets.add(RadarTarget(4, dataInt[14], dataInt[13], dataInt[12]))
            }
        }

        _targets.value = newTargets

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
}
