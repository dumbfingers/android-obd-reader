package com.github.pires.obd.reader.net

import java.util.HashMap

/**
 * DTO for OBD readings.
 */
class ObdReading {
    var latitude: Double = 0.0
    var longitude: Double = 0.0
    var altitude: Double = 0.0
    var timestamp: Long = 0
    var vin: String? = null // vehicle id
    var readings: Map<String, String> = HashMap()

    constructor()

    constructor(
        latitude: Double, longitude: Double, altitude: Double, timestamp: Long,
        vin: String?, readings: Map<String, String>
    ) {
        this.latitude = latitude
        this.longitude = longitude
        this.altitude = altitude
        this.timestamp = timestamp
        this.vin = vin
        this.readings = readings
    }

    override fun toString(): String {
        return "lat:$latitude;long:$longitude;alt:$altitude;vin:$vin;readings:${readings.toString().let { if(it.length > 10) it.substring(10) else it }.replace("}", "").replace(",", ";")}"
    }
}
