package com.pirorin215.gardiaradar

import android.app.Application
import android.content.Intent
import android.os.Build
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
        // Android 12+ のバックグラウンド開始制限に引っかかる場合は例外が飛ぶので安全に無視する
        // （その場合は既存の Boot / Activity / START_STICKY 経由で起動される）。
        try {
            val serviceIntent = Intent(this, RadarScanService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not start RadarScanService from Application onCreate", e)
        }
    }
}
