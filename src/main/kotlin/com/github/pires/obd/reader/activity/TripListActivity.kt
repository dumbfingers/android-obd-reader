package com.github.pires.obd.reader.activity

import android.app.Activity
import android.os.Bundle
import android.widget.ListView
import com.github.pires.obd.reader.R
import com.github.pires.obd.reader.trips.TripListAdapter
import com.github.pires.obd.reader.trips.TripLog

class TripListActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trips_list)

        val triplog = TripLog.getInstance(this.applicationContext)
        val records = triplog.allRecords

        val adapter = TripListAdapter(this, records)
        val list = findViewById<ListView>(R.id.tripList)
        list.adapter = adapter
    }
}
