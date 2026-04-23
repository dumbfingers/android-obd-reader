package com.github.pires.obd.reader.trips

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.SQLException
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.util.*

class TripLog private constructor(context: Context) {

    private val dbHelper: TripLogOpenHelper = TripLogOpenHelper(context)
    private val db: SQLiteDatabase = dbHelper.writableDatabase

    val allRecords: List<TripRecord>
        get() {
            val list = ArrayList<TripRecord>()
            var cursor: Cursor? = null
            try {
                val orderBy = RECORD_START_DATE
                cursor = db.query(
                    RECORDS_TABLE,
                    RECORDS_TABLE_COLUMNS,
                    null,
                    null, null, null,
                    orderBy,
                    null
                )

                if (cursor != null) {
                    if (cursor.moveToFirst()) {
                        do {
                            val record = getRecordFromCursor(cursor)
                            if (record != null) {
                                list.add(record)
                            }
                        } while (cursor.moveToNext())
                    }
                }
            } catch (e: SQLException) {
                Log.e(TAG, "SQLException: " + e.message)
                list.clear()
            } finally {
                cursor?.close()
            }
            return list
        }

    fun startTrip(): TripRecord {
        val record = TripRecord()
        val id = db.insert(RECORDS_TABLE, null, getContentValues(record))
        record.id = id.toInt()
        return record
    }

    fun updateRecord(record: TripRecord): Boolean {
        if (record.id == null) return false
        var success = false
        try {
            val values = getContentValues(record)
            values.remove(RECORD_ID)
            val whereClause = "$RECORD_ID=${record.id}"
            val count = db.update(RECORDS_TABLE, values, whereClause, null)
            success = count > 0
        } catch (e: SQLiteConstraintException) {
            Log.e(TAG, "SQLiteConstraintException: " + e.message)
        } catch (e: SQLException) {
            Log.e(TAG, "SQLException: " + e.message)
        }
        return success
    }

    private fun getContentValues(record: TripRecord): ContentValues {
        val values = ContentValues()
        values.put(RECORD_ID, record.id)
        values.put(RECORD_START_DATE, record.startDate.time)
        record.endDate?.let {
            values.put(RECORD_END_DATE, it.time)
        }
        values.put(RECORD_RPM_MAX, record.engineRpmMax)
        values.put(RECORD_SPEED_MAX, record.speedMax)
        record.engineRuntime?.let {
            values.put(RECORD_ENGINE_RUNTIME, it)
        }
        return values
    }

    fun deleteTrip(id: Long): Boolean {
        var success = false
        try {
            val whereClause = "$RECORD_ID=$id"
            val count = db.delete(RECORDS_TABLE, whereClause, null)
            success = count == 1
        } catch (e: SQLException) {
            Log.e(TAG, "SQLException: " + e.message)
        }
        return success
    }

    private fun getRecordFromCursor(c: Cursor?): TripRecord? {
        if (c == null) return null
        val record = TripRecord()
        val idIndex = c.getColumnIndex(RECORD_ID)
        val startDateIndex = c.getColumnIndex(RECORD_START_DATE)
        val endDateIndex = c.getColumnIndex(RECORD_END_DATE)
        val rpmMaxIndex = c.getColumnIndex(RECORD_RPM_MAX)
        val speedMaxIndex = c.getColumnIndex(RECORD_SPEED_MAX)
        val engineRuntimeIndex = c.getColumnIndex(RECORD_ENGINE_RUNTIME)

        if (idIndex != -1) record.id = c.getInt(idIndex)
        if (startDateIndex != -1) record.startDate = Date(c.getLong(startDateIndex))
        if (endDateIndex != -1) record.endDate = Date(c.getLong(endDateIndex))
        if (rpmMaxIndex != -1) record.engineRpmMax = c.getInt(rpmMaxIndex)
        if (speedMaxIndex != -1) record.speedMax = c.getInt(speedMaxIndex)
        if (engineRuntimeIndex != -1 && !c.isNull(engineRuntimeIndex))
            record.setEngineRuntime(c.getString(engineRuntimeIndex))

        return record
    }

    companion object {
        private val TAG = TripLog::class.java.name
        const val DATABASE_NAME = "tripslog.db"
        const val DATABASE_VERSION = 1
        const val RECORDS_TABLE = "records"
        const val RECORD_ID = "id"
        const val RECORD_START_DATE = "startDate"
        const val RECORD_END_DATE = "endDate"
        const val RECORD_RPM_MAX = "rmpMax"
        const val RECORD_SPEED_MAX = "speed"
        const val RECORD_ENGINE_RUNTIME = "engineRuntime"

        val RECORDS_TABLE_COLUMNS = arrayOf(
            RECORD_ID, RECORD_START_DATE, RECORD_END_DATE,
            RECORD_RPM_MAX, RECORD_SPEED_MAX, RECORD_ENGINE_RUNTIME
        )

        val DATABASE_CREATE = arrayOf(
            "create table " + RECORDS_TABLE + " ("
                    + RECORD_ID + " integer primary key autoincrement, "
                    + RECORD_START_DATE + " integer not null, "
                    + RECORD_END_DATE + " integer, "
                    + RECORD_RPM_MAX + " integer, "
                    + RECORD_SPEED_MAX + " integer, "
                    + RECORD_ENGINE_RUNTIME + " text"
                    + ");"
        )

        private var instance: TripLog? = null

        fun getInstance(context: Context): TripLog {
            if (instance == null) {
                instance = TripLog(context)
            }
            return instance!!
        }
    }
}
