package com.pirorin215.gardiaradar.wear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest

class RadarComplicationService : ComplicationDataSourceService() {

    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {
        super.onComplicationActivated(complicationInstanceId, type)
        Log.d("RadarComplication", "onComplicationActivated: instanceId=$complicationInstanceId")
        // コンプリケーションが追加された直後に更新を要求
        val requester = ComplicationDataSourceUpdateRequester.create(
            this,
            ComponentName(this, RadarComplicationService::class.java)
        )
        requester.requestUpdateAll()
    }

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        Log.d("RadarComplication", "onComplicationRequest: type=${request.complicationType}")

        // SharedPreferencesから接続状態を取得
        val prefs = getSharedPreferences("radar_prefs", MODE_PRIVATE)
        val isConnected = prefs.getBoolean("isConnected", false)
        Log.d("RadarComplication", "Connection state from prefs: connected=$isConnected")

        val iconRes = if (isConnected) {
            Log.d("RadarComplication", "Setting icon to connected (radar green)")
            R.drawable.ic_radar_connected_green
        } else {
            Log.d("RadarComplication", "Setting icon to disconnected (radar red)")
            R.drawable.ic_radar_disconnected_red
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            action = "com.pirorin215.gardiaradar.wear.ACTION_OPEN_RADAR"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        Log.d("RadarComplication", "Created PendingIntent with action: ${intent.action}")
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val monochromaticImage = MonochromaticImage.Builder(Icon.createWithResource(this, iconRes)).build()
        val contentDescription = PlainComplicationText.Builder("Radar Connection").build()

        val complicationData = when (request.complicationType) {
            ComplicationType.MONOCHROMATIC_IMAGE -> {
                MonochromaticImageComplicationData.Builder(
                    monochromaticImage = monochromaticImage,
                    contentDescription = contentDescription
                )
                    .setTapAction(pendingIntent)
                    .build()
            }
            ComplicationType.SMALL_IMAGE -> {
                SmallImageComplicationData.Builder(
                    smallImage = SmallImage.Builder(
                        image = Icon.createWithResource(this, iconRes),
                        type = SmallImageType.PHOTO
                    ).build(),
                    contentDescription = contentDescription
                )
                    .setTapAction(pendingIntent)
                    .build()
            }
            else -> null
        }
        listener.onComplicationData(complicationData)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val monochromaticImage = MonochromaticImage.Builder(
            Icon.createWithResource(this, R.drawable.ic_radar_connected_green)
        ).build()
        val contentDescription = PlainComplicationText.Builder("Radar Connection").build()

        return when (type) {
            ComplicationType.MONOCHROMATIC_IMAGE -> {
                MonochromaticImageComplicationData.Builder(
                    monochromaticImage = monochromaticImage,
                    contentDescription = contentDescription
                ).build()
            }
            ComplicationType.SMALL_IMAGE -> {
                SmallImageComplicationData.Builder(
                    smallImage = SmallImage.Builder(
                        image = Icon.createWithResource(this, R.drawable.ic_radar_connected_green),
                        type = SmallImageType.PHOTO
                    ).build(),
                    contentDescription = contentDescription
                ).build()
            }
            else -> null
        }
    }
}
