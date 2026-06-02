package com.pirorin215.gardiaradar.data

data class RadarTarget(
    val id: Int,
    val distance: Int, // meters
    val speed: Int,    // relative speed
    val threat: Int    // threat level
)

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Scanning : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
}
