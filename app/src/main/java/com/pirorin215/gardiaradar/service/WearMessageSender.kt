package com.pirorin215.gardiaradar.service

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import com.pirorin215.gardiaradar.data.RadarTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

class WearMessageSender(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "WearMessageSender"
        const val PATH_RADAR_ALERT = "/radar-alert"
    }

    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val nodeClient: NodeClient = Wearable.getNodeClient(context)

    /**
     * 車両検知時に全接続済みWatchへアラート送信
     */
    fun sendRadarAlert(targets: List<RadarTarget>) {
        scope.launch {
            try {
                val nodes = nodeClient.connectedNodes.await()
                if (nodes.isEmpty()) {
                    Log.d(TAG, "No connected wear nodes, skipping alert")
                    return@launch
                }

                val json = JSONObject().apply {
                    put("targetCount", targets.size)
                    val arr = JSONArray()
                    targets.forEach { t ->
                        arr.put(JSONObject().apply {
                            put("id", t.id)
                            put("distance", t.distance)
                            put("speed", t.speed)
                            put("threat", t.threat)
                        })
                    }
                    put("targets", arr)
                }
                val data = json.toString().toByteArray(Charsets.UTF_8)

                nodes.forEach { node ->
                    messageClient.sendMessage(node.id, PATH_RADAR_ALERT, data)
                        .addOnSuccessListener {
                            Log.d(TAG, "Alert sent to ${node.displayName}")
                        }
                        .addOnFailureListener { e ->
                            Log.w(TAG, "Failed to send to ${node.displayName}", e)
                        }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending radar alert to wear", e)
            }
        }
    }

}
