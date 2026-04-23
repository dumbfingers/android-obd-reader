package com.github.pires.obd.reader.io

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.preference.PreferenceManager
import android.util.Log
import android.widget.Toast
import com.github.pires.obd.commands.protocol.*
import com.github.pires.obd.commands.temperature.AmbientAirTemperatureCommand
import com.github.pires.obd.enums.ObdProtocols
import com.github.pires.obd.exceptions.UnsupportedCommandException
import com.github.pires.obd.reader.R
import com.github.pires.obd.reader.activity.ConfigActivity
import com.github.pires.obd.reader.activity.MainActivity
import com.github.pires.obd.reader.io.ObdCommandJob.ObdCommandJobState
import java.io.File
import java.io.IOException

/**
 * This service is primarily responsible for establishing and maintaining a
 * permanent connection between the device where the application runs and a more
 * OBD Bluetooth interface.
 * <p/>
 * Secondarily, it will serve as a repository of ObdCommandJobs and at the same
 * time the application state-machine.
 */
class ObdGatewayService : AbstractGatewayService() {

    private lateinit var prefs: SharedPreferences
    private var dev: BluetoothDevice? = null
    private var sock: BluetoothSocket? = null

    override fun onCreate() {
        super.onCreate()
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
    }

    @Throws(IOException::class)
    override fun startService() {
        Log.d(TAG, "Starting service..")

        // get the remote Bluetooth device
        val remoteDevice = prefs.getString(ConfigActivity.BLUETOOTH_LIST_KEY, null)
        if (remoteDevice == null || "" == remoteDevice) {
            Toast.makeText(ctx, getString(R.string.text_bluetooth_nodevice), Toast.LENGTH_LONG).show()

            // log error
            Log.e(TAG, "No Bluetooth device has been selected.")

            // TODO kill this service gracefully
            stopService()
            throw IOException()
        } else {
            val btAdapter = BluetoothAdapter.getDefaultAdapter()
            dev = btAdapter.getRemoteDevice(remoteDevice)

            /*
             * Establish Bluetooth connection
             *
             * Because discovery is a heavyweight procedure for the Bluetooth adapter,
             * this method should always be called before attempting to connect to a
             * remote device with connect(). Discovery is not managed by the Activity,
             * but is run as a system service, so an application should always call
             * cancel discovery even if it did not directly request a discovery, just to
             * be sure. If Bluetooth state is not STATE_ON, this API will return false.
             *
             * see
             * http://developer.android.com/reference/android/bluetooth/BluetoothAdapter
             * .html#cancelDiscovery()
             */
            Log.d(TAG, "Stopping Bluetooth discovery.")
            btAdapter.cancelDiscovery()

            showNotification(
                getString(R.string.notification_action),
                getString(R.string.service_starting),
                R.drawable.ic_btcar,
                true,
                true,
                false
            )

            try {
                startObdConnection()
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "There was an error while establishing connection. -> "
                            + e.message
                )

                // in case of failure, stop this service.
                stopService()
                throw IOException()
            }
            showNotification(
                getString(R.string.notification_action),
                getString(R.string.service_started),
                R.drawable.ic_btcar,
                true,
                true,
                false
            )
        }
    }

    /**
     * Start and configure the connection to the OBD interface.
     * <p/>
     * See http://stackoverflow.com/questions/18657427/ioexception-read-failed-socket-might-closed-bluetooth-on-android-4-3/18786701#18786701
     *
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun startObdConnection() {
        Log.d(TAG, "Starting OBD connection..")
        isRunning = true
        try {
            sock = BluetoothManager.connect(dev)
        } catch (e2: Exception) {
            Log.e(TAG, "There was an error while establishing Bluetooth connection. Stopping app..", e2)
            stopService()
            throw IOException()
        }

        // Let's configure the connection.
        Log.d(TAG, "Queueing jobs for connection configuration..")
        queueJob(ObdCommandJob(ObdResetCommand()))

        //Below is to give the adapter enough time to reset before sending the commands, otherwise the first startup commands could be ignored.
        try {
            Thread.sleep(500)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        queueJob(ObdCommandJob(EchoOffCommand()))

        /*
         * Will send second-time based on tests.
         *
         * TODO this can be done w/o having to queue jobs by just issuing
         * command.run(), command.getResult() and validate the result.
         */
        queueJob(ObdCommandJob(EchoOffCommand()))
        queueJob(ObdCommandJob(LineFeedOffCommand()))
        queueJob(ObdCommandJob(TimeoutCommand(62)))

        // Get protocol from preferences
        val protocol = prefs.getString(ConfigActivity.PROTOCOLS_LIST_KEY, "AUTO")
        queueJob(ObdCommandJob(SelectProtocolCommand(ObdProtocols.valueOf(protocol!!))))

        // Job for returning dummy data
        queueJob(ObdCommandJob(AmbientAirTemperatureCommand()))

        queueCounter = 0L
        Log.d(TAG, "Initialization jobs queued.")
    }

    /**
     * This method will add a job to the queue while setting its ID to the
     * internal queue counter.
     *
     * @param job the job to queue.
     */
    override fun queueJob(job: ObdCommandJob) {
        // This is a good place to enforce the imperial units option
        job.command.useImperialUnits(prefs.getBoolean(ConfigActivity.IMPERIAL_UNITS_KEY, false))

        // Now we can pass it along
        super.queueJob(job)
    }

    /**
     * Runs the queue until the service is stopped
     */
    @Throws(InterruptedException::class)
    override fun executeQueue() {
        Log.d(TAG, "Executing queue..")
        while (!Thread.currentThread().isInterrupted) {
            var job: ObdCommandJob? = null
            try {
                job = jobsQueue.take()

                // log job
                Log.d(TAG, "Taking job[" + job.id + "] from queue..")

                if (job.state == ObdCommandJobState.NEW) {
                    Log.d(TAG, "Job state is NEW. Run it..")
                    job.state = ObdCommandJobState.RUNNING
                    if (sock!!.isConnected) {
                        job.command.run(sock!!.inputStream, sock!!.outputStream)
                    } else {
                        job.state = ObdCommandJobState.EXECUTION_ERROR
                        Log.e(TAG, "Can't run command on a closed socket.")
                    }
                } else
                    Log.e(
                        TAG,
                        "Job state was not new, so it shouldn't be in queue. BUG ALERT!"
                    )
            } catch (i: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (u: UnsupportedCommandException) {
                if (job != null) {
                    job.state = ObdCommandJobState.NOT_SUPPORTED
                }
                Log.d(TAG, "Command not supported. -> " + u.message)
            } catch (io: IOException) {
                if (job != null) {
                    if (io.message != null && io.message!!.contains("Broken pipe"))
                        job.state = ObdCommandJobState.BROKEN_PIPE
                    else
                        job.state = ObdCommandJobState.EXECUTION_ERROR
                }
                Log.e(TAG, "IO error. -> " + io.message)
            } catch (e: Exception) {
                if (job != null) {
                    job.state = ObdCommandJobState.EXECUTION_ERROR
                }
                Log.e(TAG, "Failed to run command. -> " + e.message)
            }

            if (job != null) {
                val job2 = job
                (ctx as MainActivity?)?.runOnUiThread { (ctx as MainActivity?)?.stateUpdate(job2) }
            }
        }
    }

    /**
     * Stop OBD connection and queue processing.
     */
    override fun stopService() {
        Log.d(TAG, "Stopping service..")

        notificationManager.cancel(NOTIFICATION_ID)
        jobsQueue.clear()
        isRunning = false

        if (sock != null)
            try {
                sock!!.close()
            } catch (e: IOException) {
                Log.e(TAG, e.message ?: "")
            }

        // kill service
        stopSelf()
    }

    companion object {
        private val TAG = ObdGatewayService::class.java.name

        fun saveLogcatToFile(context: Context, devemail: String) {
            val emailIntent = Intent(Intent.ACTION_SEND)
            emailIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            emailIntent.type = "text/plain"
            emailIntent.putExtra(Intent.EXTRA_EMAIL, arrayOf(devemail))
            emailIntent.putExtra(Intent.EXTRA_SUBJECT, "OBD2 Reader Debug Logs")

            val sb = StringBuilder()
            sb.append("\nManufacturer: ").append(Build.MANUFACTURER)
            sb.append("\nModel: ").append(Build.MODEL)
            sb.append("\nRelease: ").append(Build.VERSION.RELEASE)

            emailIntent.putExtra(Intent.EXTRA_TEXT, sb.toString())

            val fileName = "OBDReader_logcat_" + System.currentTimeMillis() + ".txt"
            val sdCard = Environment.getExternalStorageDirectory()
            val dir = File(sdCard.absolutePath + File.separator + "OBD2Logs")
            if (dir.mkdirs()) {
                val outputFile = File(dir, fileName)
                val uri = Uri.fromFile(outputFile)
                emailIntent.putExtra(Intent.EXTRA_STREAM, uri)

                Log.d("savingFile", "Going to save logcat to $outputFile")
                context.startActivity(
                    Intent.createChooser(emailIntent, "Pick an Email provider")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )

                try {
                    Runtime.getRuntime().exec("logcat -f " + outputFile.absolutePath)
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }
}
