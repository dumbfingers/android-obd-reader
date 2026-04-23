package com.github.pires.obd.reader.activity

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.*
import android.os.*
import android.preference.PreferenceManager
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.pires.obd.commands.SpeedCommand
import com.github.pires.obd.commands.engine.RPMCommand
import com.github.pires.obd.commands.engine.RuntimeCommand
import com.github.pires.obd.enums.AvailableCommandNames
import com.github.pires.obd.reader.R
import com.github.pires.obd.reader.config.ObdConfig
import com.github.pires.obd.reader.io.*
import com.github.pires.obd.reader.net.ObdReading
import com.github.pires.obd.reader.net.ObdService
import com.github.pires.obd.reader.trips.TripLog
import com.github.pires.obd.reader.trips.TripRecord
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity(), ObdProgressListener, LocationListener, GpsStatus.Listener {

    private var mGpsIsStarted = false
    private var mLocService: LocationManager? = null
    private var mLocProvider: LocationProvider? = null
    private var myCSVWriter: LogCSVWriter? = null
    private var mLastLocation: Location? = null
    private lateinit var triplog: TripLog
    private var currentTrip: TripRecord? = null

    private lateinit var compass: TextView
    private lateinit var btStatusTextView: TextView
    private lateinit var obdStatusTextView: TextView
    private lateinit var gpsStatusTextView: TextView
    private lateinit var vv: LinearLayout
    private lateinit var tl: TableLayout

    private lateinit var sensorManager: SensorManager
    private lateinit var powerManager: PowerManager
    private lateinit var prefs: SharedPreferences

    private var isServiceBound = false
    private var service: AbstractGatewayService? = null

    private val orientListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val dir = when {
                x >= 337.5 || x < 22.5 -> "N"
                x >= 22.5 && x < 67.5 -> "NE"
                x >= 67.5 && x < 112.5 -> "E"
                x >= 112.5 && x < 157.5 -> "SE"
                x >= 157.5 && x < 202.5 -> "S"
                x >= 202.5 && x < 247.5 -> "SW"
                x >= 247.5 && x < 292.5 -> "W"
                x >= 292.5 && x < 337.5 -> "NW"
                else -> ""
            }
            updateTextView(compass, dir)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private val mQueueCommands = object : Runnable {
        override fun run() {
            if (service != null && service!!.isRunning && service!!.queueEmpty()) {
                queueCommands()

                var lat = 0.0
                var lon = 0.0
                var alt = 0.0
                val posLen = 7
                if (mGpsIsStarted && mLastLocation != null) {
                    lat = mLastLocation!!.latitude
                    lon = mLastLocation!!.longitude
                    alt = mLastLocation!!.altitude

                    val sb = StringBuilder()
                    sb.append("Lat: ")
                    sb.append(mLastLocation!!.latitude.toString().let { if(it.length > posLen) it.substring(0, posLen) else it })
                    sb.append(" Lon: ")
                    sb.append(mLastLocation!!.longitude.toString().let { if(it.length > posLen) it.substring(0, posLen) else it })
                    sb.append(" Alt: ")
                    sb.append(mLastLocation!!.altitude)
                    gpsStatusTextView.text = sb.toString()
                }
                if (prefs.getBoolean(ConfigActivity.UPLOAD_DATA_KEY, false)) {
                    val vin = prefs.getString(ConfigActivity.VEHICLE_ID_KEY, "UNDEFINED_VIN")
                    val temp = HashMap(commandResult)
                    val reading = ObdReading(lat, lon, alt, System.currentTimeMillis(), vin, temp)
                    UploadAsyncTask(prefs).execute(reading)
                } else if (prefs.getBoolean(ConfigActivity.ENABLE_FULL_LOGGING_KEY, false)) {
                    val vin = prefs.getString(ConfigActivity.VEHICLE_ID_KEY, "UNDEFINED_VIN")
                    val temp = HashMap(commandResult)
                    val reading = ObdReading(lat, lon, alt, System.currentTimeMillis(), vin, temp)
                    myCSVWriter?.writeLineCSV(reading)
                }
                commandResult.clear()
            }
            Handler(Looper.getMainLooper()).postDelayed(this, ConfigActivity.getObdUpdatePeriod(prefs).toLong())
        }
    }

    private var orientSensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var preRequisites = true

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            Log.d(TAG, "$className service is bound")
            isServiceBound = true
            service = (binder as AbstractGatewayService.AbstractGatewayServiceBinder).service
            service!!.setContext(this@MainActivity)
            Log.d(TAG, "Starting live data")
            try {
                service!!.startService()
                if (preRequisites)
                    btStatusTextView.text = getString(R.string.status_bluetooth_connected)
            } catch (ioe: IOException) {
                Log.e(TAG, "Failure Starting live data")
                btStatusTextView.text = getString(R.string.status_bluetooth_error_connecting)
                doUnbindService()
            }
        }

        override fun onServiceDisconnected(className: ComponentName) {
            Log.d(TAG, "$className service is unbound")
            isServiceBound = false
        }
    }

    private val commandResult = HashMap<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)

        // Initialize views
        compass = findViewById(R.id.compass_text)
        btStatusTextView = findViewById(R.id.BT_STATUS)
        obdStatusTextView = findViewById(R.id.OBD_STATUS)
        gpsStatusTextView = findViewById(R.id.GPS_POS)
        vv = findViewById(R.id.vehicle_view)
        tl = findViewById(R.id.data_table)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        if (btAdapter != null)
            bluetoothDefaultIsEnable = btAdapter.isEnabled

        val sensors = sensorManager.getSensorList(Sensor.TYPE_ORIENTATION)
        if (sensors.size > 0)
            orientSensor = sensors[0]
        else
            showDialog(NO_ORIENTATION_SENSOR)

        triplog = TripLog.getInstance(this.applicationContext)
        obdStatusTextView.text = getString(R.string.status_obd_disconnected)

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mLocService?.removeGpsStatusListener(this)
        mLocService?.removeUpdates(this)
        releaseWakeLockIfHeld()
        if (isServiceBound) {
            doUnbindService()
        }
        endTrip()
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        if (btAdapter != null && btAdapter.isEnabled && !bluetoothDefaultIsEnable) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                btAdapter.disable()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "Pausing..")
        releaseWakeLockIfHeld()
    }

    private fun releaseWakeLockIfHeld() {
        if (wakeLock?.isHeld == true)
            wakeLock?.release()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "Resuming..")
        sensorManager.registerListener(orientListener, orientSensor, SensorManager.SENSOR_DELAY_UI)
        wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "ObdReader")

        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        preRequisites = btAdapter != null && btAdapter.isEnabled
        if (!preRequisites && prefs.getBoolean(ConfigActivity.ENABLE_BT_KEY, false)) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                preRequisites = btAdapter != null && btAdapter.enable()
            }
        }

        gpsInit()

        if (!preRequisites) {
            showDialog(BLUETOOTH_DISABLED)
            btStatusTextView.text = getString(R.string.status_bluetooth_disabled)
        } else {
            btStatusTextView.text = getString(R.string.status_bluetooth_ok)
        }
    }

    private fun updateConfig() {
        startActivity(Intent(this, ConfigActivity::class.java))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, START_LIVE_DATA, 0, getString(R.string.menu_start_live_data))
        menu.add(0, STOP_LIVE_DATA, 0, getString(R.string.menu_stop_live_data))
        menu.add(0, GET_DTC, 0, getString(R.string.menu_get_dtc))
        menu.add(0, TRIPS_LIST, 0, getString(R.string.menu_trip_list))
        menu.add(0, SETTINGS, 0, getString(R.string.menu_settings))
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            START_LIVE_DATA -> {
                startLiveData()
                return true
            }
            STOP_LIVE_DATA -> {
                stopLiveData()
                return true
            }
            SETTINGS -> {
                updateConfig()
                return true
            }
            GET_DTC -> {
                getTroubleCodes()
                return true
            }
            TRIPS_LIST -> {
                startActivity(Intent(this, TripListActivity::class.java))
                return true
            }
        }
        return false
    }

    private fun getTroubleCodes() {
        startActivity(Intent(this, TroubleCodesActivity::class.java))
    }

    private fun startLiveData() {
        Log.d(TAG, "Starting live data..")
        tl.removeAllViews()
        doBindService()

        currentTrip = triplog.startTrip()
        if (currentTrip == null)
            showDialog(SAVE_TRIP_NOT_AVAILABLE)

        Handler(Looper.getMainLooper()).post(mQueueCommands)

        if (prefs.getBoolean(ConfigActivity.ENABLE_GPS_KEY, false))
            gpsStart()
        else
            gpsStatusTextView.text = getString(R.string.status_gps_not_used)

        wakeLock?.acquire()

        if (prefs.getBoolean(ConfigActivity.ENABLE_FULL_LOGGING_KEY, false)) {
            val mils = System.currentTimeMillis()
            val sdf = SimpleDateFormat("_dd_MM_yyyy_HH_mm_ss")
            try {
                myCSVWriter = LogCSVWriter(
                    "Log" + sdf.format(Date(mils)) + ".csv",
                    prefs.getString(ConfigActivity.DIRECTORY_FULL_LOGGING_KEY, getString(R.string.default_dirname_full_logging))!!,
                    this
                )
            } catch (e: Exception) {
                Log.e(TAG, "Can't enable logging to file.", e)
            }
        }
    }

    private fun stopLiveData() {
        Log.d(TAG, "Stopping live data..")
        gpsStop()
        doUnbindService()
        endTrip()
        releaseWakeLockIfHeld()

        val devemail = prefs.getString(ConfigActivity.DEV_EMAIL_KEY, null)
        if (!devemail.isNullOrEmpty()) {
            val dialogClickListener = DialogInterface.OnClickListener { _, which ->
                when (which) {
                    DialogInterface.BUTTON_POSITIVE -> ObdGatewayService.saveLogcatToFile(applicationContext, devemail)
                }
            }
            AlertDialog.Builder(this)
                .setMessage("Where there issues?\nThen please send us the logs.\nSend Logs?")
                .setPositiveButton("Yes", dialogClickListener)
                .setNegativeButton("No", dialogClickListener).show()
        }

        myCSVWriter?.closeLogCSVWriter()
    }

    private fun endTrip() {
        currentTrip?.let {
            it.endDate = Date()
            triplog.updateRecord(it)
        }
    }

    override fun onCreateDialog(id: Int): AlertDialog? {
        val build = AlertDialog.Builder(this)
        when (id) {
            NO_BLUETOOTH_ID -> {
                build.setMessage(getString(R.string.text_no_bluetooth_id))
                return build.create()
            }
            BLUETOOTH_DISABLED -> {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
                return build.create()
            }
            NO_ORIENTATION_SENSOR -> {
                build.setMessage(getString(R.string.text_no_orientation_sensor))
                return build.create()
            }
            NO_GPS_SUPPORT -> {
                build.setMessage(getString(R.string.text_no_gps_support))
                return build.create()
            }
            SAVE_TRIP_NOT_AVAILABLE -> {
                build.setMessage(getString(R.string.text_save_trip_not_available))
                return build.create()
            }
        }
        return null
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val startItem = menu.findItem(START_LIVE_DATA)
        val stopItem = menu.findItem(STOP_LIVE_DATA)
        val settingsItem = menu.findItem(SETTINGS)
        val getDTCItem = menu.findItem(GET_DTC)

        if (service?.isRunning == true) {
            getDTCItem.isEnabled = false
            startItem.isEnabled = false
            stopItem.isEnabled = true
            settingsItem.isEnabled = false
        } else {
            getDTCItem.isEnabled = true
            stopItem.isEnabled = false
            startItem.isEnabled = true
            settingsItem.isEnabled = true
        }
        return true
    }

    private fun addTableRow(id: String, key: String, valStr: String) {
        val tr = TableRow(this)
        val params = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(TABLE_ROW_MARGIN, TABLE_ROW_MARGIN, TABLE_ROW_MARGIN, TABLE_ROW_MARGIN)
        tr.layoutParams = params

        val name = TextView(this)
        name.gravity = Gravity.RIGHT
        name.text = "$key: "
        val value = TextView(this)
        value.gravity = Gravity.LEFT
        value.text = valStr
        value.tag = id
        tr.addView(name)
        tr.addView(value)
        tl.addView(tr, params)
    }

    private fun queueCommands() {
        if (isServiceBound) {
            for (Command in ObdConfig.getCommands()) {
                if (prefs.getBoolean(Command.name, true))
                    service!!.queueJob(ObdCommandJob(Command))
            }
        }
    }

    private fun doBindService() {
        if (!isServiceBound) {
            Log.d(TAG, "Binding OBD service..")
            if (preRequisites) {
                btStatusTextView.text = getString(R.string.status_bluetooth_connecting)
                val serviceIntent = Intent(this, ObdGatewayService::class.java)
                bindService(serviceIntent, serviceConn, BIND_AUTO_CREATE)
            } else {
                btStatusTextView.text = getString(R.string.status_bluetooth_disabled)
                val serviceIntent = Intent(this, MockObdGatewayService::class.java)
                bindService(serviceIntent, serviceConn, BIND_AUTO_CREATE)
            }
        }
    }

    private fun doUnbindService() {
        if (isServiceBound) {
            if (service!!.isRunning) {
                service!!.stopService()
                if (preRequisites)
                    btStatusTextView.text = getString(R.string.status_bluetooth_ok)
            }
            Log.d(TAG, "Unbinding OBD service..")
            unbindService(serviceConn)
            isServiceBound = false
            obdStatusTextView.text = getString(R.string.status_obd_disconnected)
        }
    }

    override fun onLocationChanged(location: Location) {
        mLastLocation = location
    }

    override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {}

    override fun onGpsStatusChanged(event: Int) {
        when (event) {
            GpsStatus.GPS_EVENT_STARTED -> gpsStatusTextView.text = getString(R.string.status_gps_started)
            GpsStatus.GPS_EVENT_STOPPED -> gpsStatusTextView.text = getString(R.string.status_gps_stopped)
            GpsStatus.GPS_EVENT_FIRST_FIX -> gpsStatusTextView.text = getString(R.string.status_gps_fix)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                btStatusTextView.text = getString(R.string.status_bluetooth_connected)
            } else {
                Toast.makeText(this, R.string.text_bluetooth_disabled, Toast.LENGTH_LONG).show()
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    @Synchronized
    private fun gpsStart() {
        if (!mGpsIsStarted && mLocProvider != null && mLocService != null && mLocService!!.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                mLocService!!.requestLocationUpdates(mLocProvider!!.name, ConfigActivity.getGpsUpdatePeriod(prefs).toLong(), ConfigActivity.getGpsDistanceUpdatePeriod(prefs).toFloat(), this)
                mGpsIsStarted = true
            }
        } else {
            gpsStatusTextView.text = getString(R.string.status_gps_no_support)
        }
    }

    @Synchronized
    private fun gpsStop() {
        if (mGpsIsStarted) {
            mLocService?.removeUpdates(this)
            mGpsIsStarted = false
            gpsStatusTextView.text = getString(R.string.status_gps_stopped)
        }
    }

    override fun stateUpdate(job: ObdCommandJob) {
        val cmdName = job.command.name
        var cmdResult = ""
        val cmdID = lookUpCommand(cmdName)

        when (job.state) {
            ObdCommandJob.ObdCommandJobState.EXECUTION_ERROR -> {
                cmdResult = job.command.result ?: ""
                if (isServiceBound) {
                    obdStatusTextView.text = cmdResult.lowercase()
                }
            }
            ObdCommandJob.ObdCommandJobState.BROKEN_PIPE -> {
                if (isServiceBound)
                    stopLiveData()
            }
            ObdCommandJob.ObdCommandJobState.NOT_SUPPORTED -> {
                cmdResult = getString(R.string.status_obd_no_support)
            }
            else -> {
                cmdResult = job.command.formattedResult
                if (isServiceBound)
                    obdStatusTextView.text = getString(R.string.status_obd_data)
            }
        }

        val existingTV = vv.findViewWithTag<TextView>(cmdID)
        if (existingTV != null) {
            existingTV.text = cmdResult
        } else {
            addTableRow(cmdID, cmdName, cmdResult)
        }
        commandResult[cmdID] = cmdResult
        updateTripStatistic(job, cmdID)
    }

    private fun updateTripStatistic(job: ObdCommandJob, cmdID: String) {
        currentTrip?.let {
            if (cmdID == AvailableCommandNames.SPEED.toString()) {
                val command = job.command as SpeedCommand
                it.speedMax = command.metricSpeed
            } else if (cmdID == AvailableCommandNames.ENGINE_RPM.toString()) {
                val command = job.command as RPMCommand
                it.engineRpmMax = command.rpm
            } else if (cmdID.endsWith(AvailableCommandNames.ENGINE_RUNTIME.toString())) {
                val command = job.command as RuntimeCommand
                it.setEngineRuntime(command.formattedResult)
            }
        }
    }

    private fun gpsInit(): Boolean {
        mLocService = getSystemService(LOCATION_SERVICE) as LocationManager
        if (mLocService != null) {
            mLocProvider = mLocService!!.getProvider(LocationManager.GPS_PROVIDER)
            if (mLocProvider != null) {
                mLocService!!.addGpsStatusListener(this)
                if (mLocService!!.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    gpsStatusTextView.text = getString(R.string.status_gps_ready)
                    return true
                }
            }
        }
        gpsStatusTextView.text = getString(R.string.status_gps_no_support)
        showDialog(NO_GPS_SUPPORT)
        Log.e(TAG, "Unable to get GPS PROVIDER")
        return false
    }

    private fun updateTextView(view: TextView, txt: String) {
        Handler(Looper.getMainLooper()).post { view.text = txt }
    }

    private inner class UploadAsyncTask(val prefs: SharedPreferences) : AsyncTask<ObdReading, Void, Void>() {

        private val retrofit by lazy {
            val endpoint = prefs.getString(ConfigActivity.UPLOAD_URL_KEY, "") ?: ""
            if (endpoint.isNotEmpty()) {
                Retrofit.Builder()
                    .baseUrl(endpoint)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
            } else null
        }

        override fun doInBackground(vararg readings: ObdReading): Void? {
            Log.d(TAG, "Uploading " + readings.size + " readings..")
            val service = retrofit?.create(ObdService::class.java) ?: return null

            for (reading in readings) {
                try {
                    val response = service.uploadReading(reading).execute()
                    assert(response.code() == 200)
                } catch (re: Exception) {
                    Log.e(TAG, re.toString())
                }
            }
            Log.d(TAG, "Done")
            return null
        }
    }

    companion object {
        private val TAG = MainActivity::class.java.name
        private const val NO_BLUETOOTH_ID = 0
        private const val BLUETOOTH_DISABLED = 1
        private const val START_LIVE_DATA = 2
        private const val STOP_LIVE_DATA = 3
        private const val SETTINGS = 4
        private const val GET_DTC = 5
        private const val TABLE_ROW_MARGIN = 7
        private const val NO_ORIENTATION_SENSOR = 8
        private const val NO_GPS_SUPPORT = 9
        private const val TRIPS_LIST = 10
        private const val SAVE_TRIP_NOT_AVAILABLE = 11
        private const val REQUEST_ENABLE_BT = 1234
        private const val REQUEST_PERMISSIONS = 1235
        private var bluetoothDefaultIsEnable = false

        fun lookUpCommand(txt: String): String {
            for (item in AvailableCommandNames.values()) {
                if (item.value == txt) return item.name
            }
            return txt
        }
    }
}
