package com.pirorin215.gardiaradar

import android.app.Application
import android.content.Intent
import android.util.Log
import com.pirorin215.gardiaradar.di.appModule
import com.pirorin215.gardiaradar.service.RadarScanService
import com.pirorin215.gardiaradar.viewModel.RadarConnectionManager
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

class MainApplication : Application() {
    companion object {
        private const val TAG = "MainApplication"
    }

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MainApplication)
            modules(appModule)
        }

        // プロセス起動直後に接続ライフサイクル管理を初期化する。
        // RadarConnectionManager は Koin の single だが、初回 get() まで生成されない。
        // UI を開かずにプロセスが再開された場合（Boot / START_STICKY /
        // NotificationListenerService による復帰）、スキャンがデバイスを検出して
        // emitDeviceFound() を呼んでも、それを受けて connect() するコレクタが不在になり
        // 自動接続が失敗する主因だった。ここで確実に生成し、常駐させる。
        GlobalContext.get().get<RadarConnectionManager>()

        // スキャンサービスも確実に起動する。
        // BootCompletedReceiver や START_STICKY で再開されるケースを補完し、
        // プロセスが生きている限りスキャンが稼働し続けるようにする。
        // ただし権限未付与状態で起動すると Service 側で SecurityException クラッシュするため、
        // 共有モジュール経由で権限チェック＋安全起動する（不足時は起動せず Log 出力）。
        val serviceIntent = Intent(this, RadarScanService::class.java)
        val started = com.pirorin215.permissioncore.PermissionGuard.safeStartBleScanService(this, serviceIntent)
        if (!started) {
            Log.w(TAG, "Could not start RadarScanService (permissions missing or bg-start blocked).")
        }
    }
}
