package com.pirorin215.gardiaradar.wear

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest

class RadarComplicationService : ComplicationDataSourceService() {

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
            R.drawable.ic_radar_connected
        } else {
            R.drawable.ic_radar_disconnected
        }

        val intent = Intent(this, MainActivity::class.java)
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
            ComplicationType.SHORT_TEXT -> {
                createShortTextComplication(isConnected, pendingIntent, monochromaticImage, contentDescription)
            }
            else -> {
                Log.w("RadarComplication", "Unsupported complication type: ${request.complicationType}, falling back to SHORT_TEXT")
                createShortTextComplication(isConnected, pendingIntent, monochromaticImage, contentDescription)
            }
        }
        listener.onComplicationData(complicationData)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val monochromaticImage = MonochromaticImage.Builder(
            Icon.createWithResource(this, R.drawable.ic_radar_connected)
        ).build()
        val contentDescription = PlainComplicationText.Builder("Radar Connection").build()

        return when (type) {
            ComplicationType.MONOCHROMATIC_IMAGE -> {
                MonochromaticImageComplicationData.Builder(
                    monochromaticImage = monochromaticImage,
                    contentDescription = contentDescription
                ).build()
            }
            ComplicationType.SHORT_TEXT -> {
                createPreviewShortText(monochromaticImage, contentDescription)
            }
            else -> {
                Log.w("RadarComplication", "Unsupported preview type: $type, falling back to SHORT_TEXT")
                createPreviewShortText(monochromaticImage, contentDescription)
            }
        }
    }

    private fun createShortTextComplication(
        isConnected: Boolean,
        pendingIntent: PendingIntent,
        monochromaticImage: MonochromaticImage,
        contentDescription: PlainComplicationText
    ): ShortTextComplicationData {
        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(if (isConnected) "ON" else "OFF").build(),
            contentDescription = contentDescription
        )
            .setMonochromaticImage(monochromaticImage)
            .setTapAction(pendingIntent)
            .build()
    }

    private fun createPreviewShortText(
        monochromaticImage: MonochromaticImage,
        contentDescription: PlainComplicationText
    ): ShortTextComplicationData {
        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder("ON").build(),
            contentDescription = contentDescription
        )
            .setMonochromaticImage(monochromaticImage)
            .build()
    }
}
