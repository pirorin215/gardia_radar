package com.pirorin215.gardiaradar.wear

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

class WearableDataListener : WearableListenerService() {

    companion object {
        private const val TAG = "WearableDataListener"
        const val ACTION_TARGETS_UPDATED = "com.pirorin215.gardiaradar.wear.ACTION_TARGETS_UPDATED"
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem != null) {
                val path = event.dataItem.uri.path
                if (path == "/radar-targets") {
                    val dataMap: DataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val targetCount = dataMap.getInt("targetCount", 0)
                    val distancesArrayList = dataMap.getIntegerArrayList("distances")
                    val distances = distancesArrayList?.map { it.toInt() } ?: emptyList()

                    Log.d(TAG, "Targets received: count=$targetCount, distances=$distances")

                    val intent = Intent(ACTION_TARGETS_UPDATED).apply {
                        putExtra("targetCount", targetCount)
                        putIntegerArrayListExtra("distances", java.util.ArrayList(distances))
                    }
                    sendBroadcast(intent)
                }
            }
        }
    }
}
