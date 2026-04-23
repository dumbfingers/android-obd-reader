package com.github.pires.obd.reader.trips

import java.text.SimpleDateFormat
import java.util.*

class TripRecord {

    /// record id for database use (primary key)
    var id: Int? = null

    /// the date the trip started
    var startDate: Date = Date()

    /// the date the trip ended
    var endDate: Date? = null

    var engineRpmMax: Int = 0
        set(value) {
            if (field < value) {
                field = value
            }
        }

    var speedMax: Int = 0
        set(value) {
            if (field < value) {
                field = value
            }
        }

    var engineRuntime: String? = null
        private set

    fun setSpeedMax(value: String) {
        speedMax = value.toInt()
    }

    fun setEngineRpmMax(value: String) {
        engineRpmMax = value.toInt()
    }

    val startDateString: String
        get() {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            return sdf.format(this.startDate)
        }

    fun setEngineRuntime(value: String) {
        if (value != "00:00:00") {
            this.engineRuntime = value
        }
    }
}
