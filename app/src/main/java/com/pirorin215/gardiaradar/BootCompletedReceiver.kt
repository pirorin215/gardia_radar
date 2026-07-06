package com.pirorin215.gardiaradar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.d(TAG, "Boot completed received. Starting RadarScanService.")

        val pendingResult = goAsync()
        try {
            // 権限未付与のまま boot → Service 起動 → SecurityException クラッシュ、を防ぐ。
            // 共有モジュール経由で権限チェック＋安全起動（不足時は起動せず false を返す）。
            val serviceIntent = Intent(context, com.pirorin215.gardiaradar.service.RadarScanService::class.java)
            val started = com.pirorin215.permissioncore.PermissionGuard.safeStartBleScanService(
                context, serviceIntent
            )
            if (!started) {
                Log.w(TAG, "Skipped RadarScanService start on boot (permissions missing or bg-start blocked).")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RadarScanService on boot", e)
        } finally {
            pendingResult.finish()
        }
    }
}
