package com.github.pires.obd.reader.trips

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class TripLogOpenHelper(context: Context) :
    SQLiteOpenHelper(context, TripLog.DATABASE_NAME, null, TripLog.DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        execSQL(db, TripLog.DATABASE_CREATE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    }

    private fun execSQL(db: SQLiteDatabase, statements: Array<String>) {
        for (sql in statements) {
            Log.d(TAG, sql)
            db.execSQL(sql)
        }
    }

    companion object {
        private val TAG = TripLogOpenHelper::class.java.name
    }
}
