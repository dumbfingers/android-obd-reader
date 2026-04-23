package com.github.pires.obd.reader.activity

import android.Manifest
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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.github.pires.obd.reader.ui.theme.ObdReaderTheme
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity(), ObdProgressListener, LocationListener, GpsStatus.Listener {

    private var mGpsIsStarted = false
    private var mLocService: LocationManager? = null
    private var mLocProvider: LocationProvider? = null
    private var myCSVWriter: LogCSVWriter? = null
    private var mLastLocation: Location? = null
    private lateinit var triplog: TripLog
    private var currentTrip: TripRecord? = null

    private lateinit var sensorManager: SensorManager
    private lateinit var powerManager: PowerManager
    private lateinit var prefs: SharedPreferences

    private val viewModel: MainViewModel by viewModels()

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
            viewModel.updateCompass(dir)
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
                    sb.append(mLastLocation!!.latitude.toString().let { if (it.length > posLen) it.substring(0, posLen) else it })
                    sb.append(" Lon: ")
                    sb.append(mLastLocation!!.longitude.toString().let { if (it.length > posLen) it.substring(0, posLen) else it })
                    sb.append(" Alt: ")
                    sb.append(mLastLocation!!.altitude)
                    viewModel.updateGpsStatus(sb.toString())
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
                    viewModel.updateBtStatus(getString(R.string.status_bluetooth_connected))
            } catch (ioe: IOException) {
                Log.e(TAG, "Failure Starting live data")
                viewModel.updateBtStatus(getString(R.string.status_bluetooth_error_connecting))
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
            showObdDialog(NO_ORIENTATION_SENSOR)

        triplog = TripLog.getInstance(this.applicationContext)
        viewModel.updateObdStatus(getString(R.string.status_obd_disconnected))

        checkAndRequestPermissions()

        setContent {
            ObdReaderTheme {
                MainScreen(viewModel, onMenuAction = { action ->
                    when (action) {
                        MenuAction.START_LIVE_DATA -> startLiveData()
                        MenuAction.STOP_LIVE_DATA -> stopLiveData()
                        MenuAction.GET_DTC -> getTroubleCodes()
                        MenuAction.TRIPS_LIST -> startActivity(Intent(this, TripListActivity::class.java))
                        MenuAction.SETTINGS -> updateConfig()
                    }
                }, isServiceRunning = service?.isRunning ?: false)
            }
        }
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
            showObdDialog(BLUETOOTH_DISABLED)
            viewModel.updateBtStatus(getString(R.string.status_bluetooth_disabled))
        } else {
            viewModel.updateBtStatus(getString(R.string.status_bluetooth_ok))
        }
    }

    private fun updateConfig() {
        startActivity(Intent(this, ConfigActivity::class.java))
    }

    private fun getTroubleCodes() {
        startActivity(Intent(this, TroubleCodesActivity::class.java))
    }

    private fun startLiveData() {
        Log.d(TAG, "Starting live data..")
        viewModel.clearObdData()
        doBindService()

        currentTrip = triplog.startTrip()
        if (currentTrip == null)
            showObdDialog(SAVE_TRIP_NOT_AVAILABLE)

        Handler(Looper.getMainLooper()).post(mQueueCommands)

        if (prefs.getBoolean(ConfigActivity.ENABLE_GPS_KEY, false))
            gpsStart()
        else
            viewModel.updateGpsStatus(getString(R.string.status_gps_not_used))

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

    private fun showObdDialog(id: Int) {
        val build = AlertDialog.Builder(this)
        when (id) {
            NO_BLUETOOTH_ID -> {
                build.setMessage(getString(R.string.text_no_bluetooth_id))
                build.show()
            }
            BLUETOOTH_DISABLED -> {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            }
            NO_ORIENTATION_SENSOR -> {
                build.setMessage(getString(R.string.text_no_orientation_sensor))
                build.show()
            }
            NO_GPS_SUPPORT -> {
                build.setMessage(getString(R.string.text_no_gps_support))
                build.show()
            }
            SAVE_TRIP_NOT_AVAILABLE -> {
                build.setMessage(getString(R.string.text_save_trip_not_available))
                build.show()
            }
        }
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
                viewModel.updateBtStatus(getString(R.string.status_bluetooth_connecting))
                val serviceIntent = Intent(this, ObdGatewayService::class.java)
                bindService(serviceIntent, serviceConn, BIND_AUTO_CREATE)
            } else {
                viewModel.updateBtStatus(getString(R.string.status_bluetooth_disabled))
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
                    viewModel.updateBtStatus(getString(R.string.status_bluetooth_ok))
            }
            Log.d(TAG, "Unbinding OBD service..")
            unbindService(serviceConn)
            isServiceBound = false
            viewModel.updateObdStatus(getString(R.string.status_obd_disconnected))
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
            GpsStatus.GPS_EVENT_STARTED -> viewModel.updateGpsStatus(getString(R.string.status_gps_started))
            GpsStatus.GPS_EVENT_STOPPED -> viewModel.updateGpsStatus(getString(R.string.status_gps_stopped))
            GpsStatus.GPS_EVENT_FIRST_FIX -> viewModel.updateGpsStatus(getString(R.string.status_gps_fix))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                viewModel.updateBtStatus(getString(R.string.status_bluetooth_connected))
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
            viewModel.updateGpsStatus(getString(R.string.status_gps_no_support))
        }
    }

    @Synchronized
    private fun gpsStop() {
        if (mGpsIsStarted) {
            mLocService?.removeUpdates(this)
            mGpsIsStarted = false
            viewModel.updateGpsStatus(getString(R.string.status_gps_stopped))
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
                    viewModel.updateObdStatus(cmdResult.lowercase())
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
                    viewModel.updateObdStatus(getString(R.string.status_obd_data))
            }
        }

        viewModel.updateObdData(cmdID, cmdResult)
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
                    viewModel.updateGpsStatus(getString(R.string.status_gps_ready))
                    return true
                }
            }
        }
        viewModel.updateGpsStatus(getString(R.string.status_gps_no_support))
        showObdDialog(NO_GPS_SUPPORT)
        Log.e(TAG, "Unable to get GPS PROVIDER")
        return false
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
        private const val NO_ORIENTATION_SENSOR = 8
        private const val NO_GPS_SUPPORT = 9
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

enum class MenuAction {
    START_LIVE_DATA, STOP_LIVE_DATA, GET_DTC, TRIPS_LIST, SETTINGS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel, onMenuAction: (MenuAction) -> Unit, isServiceRunning: Boolean) {
    val gpsStatus by viewModel.gpsStatus.observeAsState("")
    val btStatus by viewModel.btStatus.observeAsState("")
    val obdStatus by viewModel.obdStatus.observeAsState("")
    val compassDirection by viewModel.compassDirection.observeAsState("")
    val obdData by viewModel.obdData.observeAsState(emptyMap())

    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OBD Reader") },
                actions = {
                    IconButton(onClick = { showMenu = !showMenu }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Start Live Data") },
                            onClick = {
                                showMenu = false
                                onMenuAction(MenuAction.START_LIVE_DATA)
                            },
                            enabled = !isServiceRunning
                        )
                        DropdownMenuItem(
                            text = { Text("Stop Live Data") },
                            onClick = {
                                showMenu = false
                                onMenuAction(MenuAction.STOP_LIVE_DATA)
                            },
                            enabled = isServiceRunning
                        )
                        DropdownMenuItem(
                            text = { Text("Get DTC") },
                            onClick = {
                                showMenu = false
                                onMenuAction(MenuAction.GET_DTC)
                            },
                            enabled = !isServiceRunning
                        )
                        DropdownMenuItem(
                            text = { Text("Trip List") },
                            onClick = {
                                showMenu = false
                                onMenuAction(MenuAction.TRIPS_LIST)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = {
                                showMenu = false
                                onMenuAction(MenuAction.SETTINGS)
                            },
                            enabled = !isServiceRunning
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = obdData["SPEED"] ?: "0",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = compassDirection.ifEmpty { "N/A" },
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                InfoItem(label = "Fuel", value = obdData["FUEL_CONSUMPTION"] ?: "N/A")
                InfoItem(label = "Runtime", value = obdData["ENGINE_RUNTIME"] ?: "N/A")
                InfoItem(label = "RPM", value = obdData["ENGINE_RPM"] ?: "N/A")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium
            ) {
                LazyColumn(
                    modifier = Modifier.padding(8.dp)
                ) {
                    items(obdData.toList()) { (key, value) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "$key:", fontWeight = FontWeight.SemiBold)
                            Text(text = value)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatusItem(label = "GPS", status = gpsStatus)
                StatusItem(label = "BT", status = btStatus)
                StatusItem(label = "OBD", status = obdStatus)
            }
        }
    }
}

@Composable
fun InfoItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun StatusItem(label: String, status: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(100.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(text = status, style = MaterialTheme.typography.bodySmall, maxLines = 2)
    }
}
