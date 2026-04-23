package com.github.pires.obd.reader.trips

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.github.pires.obd.reader.R
import java.util.*

class TripListAdapter(private val activity: Activity, private val records: List<TripRecord>) :
    ArrayAdapter<TripRecord>(activity, R.layout.row_trip_list, records) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        var view = convertView
        if (view == null) {
            val inflater = activity.layoutInflater
            view = inflater.inflate(R.layout.row_trip_list, parent, false)
        }

        val startDate = view!!.findViewById<TextView>(R.id.startDate)
        val columnDuration = view.findViewById<TextView>(R.id.columnDuration)
        val rowEngine = view.findViewById<TextView>(R.id.rowEngine)
        val rowOther = view.findViewById<TextView>(R.id.rowOther)

        val record = records[position]

        startDate.text = record.startDateString
        columnDuration.text = calcDiffTime(record.startDate, record.endDate ?: record.startDate)

        val rpmMax = record.engineRpmMax.toString()
        val engineRuntime = record.engineRuntime ?: "None"
        rowEngine.text = "Engine Runtime: $engineRuntime\tMax RPM: $rpmMax"
        rowOther.text = "Max speed: ${record.speedMax}"

        return view
    }

    private fun calcDiffTime(start: Date, end: Date): String {
        val diff = end.time - start.time
        val diffSeconds = diff / 1000 % 60
        val diffMinutes = diff / (60 * 1000) % 60
        val diffHours = diff / (60 * 60 * 1000) % 24
        val diffDays = diff / (24 * 60 * 60 * 1000)

        val res = StringBuilder()
        if (diffDays > 0) res.append("${diffDays}d")
        if (diffHours > 0) {
            if (res.isNotEmpty()) res.append(" ")
            res.append("${diffHours}h")
        }
        if (diffMinutes > 0) {
            if (res.isNotEmpty()) res.append(" ")
            res.append("${diffMinutes}m")
        }
        if (diffSeconds > 0) {
            if (res.isNotEmpty()) res.append(" ")
            res.append("${diffSeconds}s")
        }
        return res.toString()
    }
}
