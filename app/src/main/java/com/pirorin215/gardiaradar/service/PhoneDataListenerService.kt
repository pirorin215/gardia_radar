package com.pirorin215.gardiaradar.service

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.pirorin215.gardiaradar.data.RadarRepository
import org.koin.android.ext.android.inject

class PhoneDataListenerService : WearableListenerService() {
    private val TAG = "PhoneDataListenerService"
    private val radarRepository: RadarRepository by inject()

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem != null) {
                val path = event.dataItem.uri.path
                if (path == "/wear-battery") {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val level = dataMap.getInt("level", -1)
                    Log.d(TAG, "Wear OS battery level received: $level%")
                    radarRepository.setWearBatteryLevel(level)
                }
            }
        }
    }
}
