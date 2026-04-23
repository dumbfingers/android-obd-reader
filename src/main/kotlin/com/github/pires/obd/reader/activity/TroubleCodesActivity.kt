package com.github.pires.obd.reader.activity

import android.app.Activity
import android.app.ProgressDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.AsyncTask
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import com.github.pires.obd.commands.control.TroubleCodesCommand
import com.github.pires.obd.commands.protocol.*
import com.github.pires.obd.enums.ObdProtocols
import com.github.pires.obd.exceptions.MisunderstoodCommandException
import com.github.pires.obd.exceptions.NoDataException
import com.github.pires.obd.exceptions.UnableToConnectException
import com.github.pires.obd.reader.R
import com.github.pires.obd.reader.io.BluetoothManager
import java.io.IOException
import java.util.*

class TroubleCodesActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private var progressDialog: ProgressDialog? = null
    private var remoteDevice: String? = null
    private var gtct: GetTroubleCodesTask? = null
    private var dev: BluetoothDevice? = null
    private var sock: BluetoothSocket? = null

    private val mHandler = Handler(Looper.getMainLooper(), Handler.Callback { msg ->
        Log.d(TAG, "Message received on handler")
        when (msg.what) {
            NO_BLUETOOTH_DEVICE_SELECTED -> {
                makeToast(getString(R.string.text_bluetooth_nodevice))
                finish()
            }
            CANNOT_CONNECT_TO_DEVICE -> {
                makeToast(getString(R.string.text_bluetooth_error_connecting))
                finish()
            }
            OBD_COMMAND_FAILURE -> {
                makeToast(getString(R.string.text_obd_command_failure))
                finish()
            }
            OBD_COMMAND_FAILURE_IO -> {
                makeToast(getString(R.string.text_obd_command_failure) + " IO")
                finish()
            }
            OBD_COMMAND_FAILURE_IE -> {
                makeToast(getString(R.string.text_obd_command_failure) + " IE")
                finish()
            }
            OBD_COMMAND_FAILURE_MIS -> {
                makeToast(getString(R.string.text_obd_command_failure) + " MIS")
                finish()
            }
            OBD_COMMAND_FAILURE_UTC -> {
                makeToast(getString(R.string.text_obd_command_failure) + " UTC")
                finish()
            }
            OBD_COMMAND_FAILURE_NODATA -> {
                makeToastLong(getString(R.string.text_noerrors))
            }
            NO_DATA -> {
                makeToast(getString(R.string.text_dtc_no_data))
            }
            DATA_OK -> {
                dataOk(msg.obj as String?)
            }
        }
        false
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        remoteDevice = prefs.getString(ConfigActivity.BLUETOOTH_LIST_KEY, null)
        if (remoteDevice == null || "" == remoteDevice) {
            Log.e(TAG, "No Bluetooth device has been selected.")
            mHandler.obtainMessage(NO_BLUETOOTH_DEVICE_SELECTED).sendToTarget()
        } else {
            gtct = GetTroubleCodesTask()
            gtct!!.execute(remoteDevice)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.trouble_codes, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_clear_codes -> {
                try {
                    sock = BluetoothManager.connect(dev)
                } catch (e: Exception) {
                    Log.e(TAG, "There was an error while establishing connection. -> " + e.message)
                    mHandler.obtainMessage(CANNOT_CONNECT_TO_DEVICE).sendToTarget()
                    return true
                }
                try {
                    Log.d("TESTRESET", "Trying reset")
                    val clear = ResetTroubleCodesCommand()
                    clear.run(sock!!.inputStream, sock!!.outputStream)
                    val result = clear.formattedResult
                    Log.d("TESTRESET", "Trying reset result: $result")
                } catch (e: Exception) {
                    Log.e(TAG, "There was an error while establishing connection. -> " + e.message)
                }
                gtct?.closeSocket(sock)
                val refresh = Intent(this, TroubleCodesActivity::class.java)
                startActivity(refresh)
                finish()
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    private fun getDict(keyId: Int, valId: Int): Map<String, String> {
        val keys = resources.getStringArray(keyId)
        val vals = resources.getStringArray(valId)
        val dict = HashMap<String, String>()
        for (i in keys.indices) {
            dict[keys[i]] = vals[i]
        }
        return dict
    }

    fun makeToast(text: String?) {
        Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
    }

    fun makeToastLong(text: String?) {
        Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
    }

    private fun dataOk(res: String?) {
        setContentView(R.layout.trouble_codes)
        val lv = findViewById<ListView>(R.id.listView)
        val dtcVals = getDict(R.array.dtc_keys, R.array.dtc_values)
        val dtcCodes = ArrayList<String>()
        if (res != null && res.isNotEmpty()) {
            for (dtcCode in res.split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) {
                if (dtcCode.isNotEmpty()) {
                    dtcCodes.add(dtcCode + " : " + dtcVals[dtcCode])
                    Log.d("TEST", dtcCode + " : " + dtcVals[dtcCode])
                }
            }
        } else {
            dtcCodes.add("There are no errors")
        }
        val myarrayAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, dtcCodes)
        lv.adapter = myarrayAdapter
        lv.isTextFilterEnabled = true
    }

    class ModifiedTroubleCodesObdCommand : TroubleCodesCommand() {
        override fun getResult(): String {
            return rawData.replace("SEARCHING...", "").replace("NODATA", "")
        }
    }

    private inner class GetTroubleCodesTask : AsyncTask<String, Int, String>() {

        override fun onPreExecute() {
            progressDialog = ProgressDialog(this@TroubleCodesActivity).apply {
                setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
                setTitle(getString(R.string.dialog_loading_title))
                setMessage(getString(R.string.dialog_loading_body))
                setCancelable(false)
                isIndeterminate = false
                max = 5
                progress = 0
                show()
            }
        }

        override fun doInBackground(vararg params: String): String? {
            var result = ""
            synchronized(this) {
                Log.d(TAG, "Starting service..")
                val btAdapter = BluetoothAdapter.getDefaultAdapter()
                dev = btAdapter.getRemoteDevice(params[0])
                Log.d(TAG, "Stopping Bluetooth discovery.")
                btAdapter.cancelDiscovery()
                Log.d(TAG, "Starting OBD connection..")

                try {
                    sock = BluetoothManager.connect(dev)
                } catch (e: Exception) {
                    Log.e(TAG, "There was an error while establishing connection. -> " + e.message)
                    mHandler.obtainMessage(CANNOT_CONNECT_TO_DEVICE).sendToTarget()
                    return null
                }

                try {
                    publishProgress(1)
                    ObdResetCommand().run(sock!!.inputStream, sock!!.outputStream)
                    publishProgress(2)
                    EchoOffCommand().run(sock!!.inputStream, sock!!.outputStream)
                    publishProgress(3)
                    LineFeedOffCommand().run(sock!!.inputStream, sock!!.outputStream)
                    publishProgress(4)
                    SelectProtocolCommand(ObdProtocols.AUTO).run(sock!!.inputStream, sock!!.outputStream)
                    publishProgress(5)
                    val tcoc = ModifiedTroubleCodesObdCommand()
                    tcoc.run(sock!!.inputStream, sock!!.outputStream)
                    result = tcoc.formattedResult
                } catch (e: IOException) {
                    Log.e("DTCERR", e.message ?: "")
                    mHandler.obtainMessage(OBD_COMMAND_FAILURE_IO).sendToTarget()
                    return null
                } catch (e: InterruptedException) {
                    Log.e("DTCERR", e.message ?: "")
                    mHandler.obtainMessage(OBD_COMMAND_FAILURE_IE).sendToTarget()
                    return null
                } catch (e: UnableToConnectException) {
                    Log.e("DTCERR", e.message ?: "")
                    mHandler.obtainMessage(OBD_COMMAND_FAILURE_UTC).sendToTarget()
                    return null
                } catch (e: MisunderstoodCommandException) {
                    Log.e("DTCERR", e.message ?: "")
                    mHandler.obtainMessage(OBD_COMMAND_FAILURE_MIS).sendToTarget()
                    return null
                } catch (e: NoDataException) {
                    Log.e("DTCERR", e.message ?: "")
                    mHandler.obtainMessage(OBD_COMMAND_FAILURE_NODATA).sendToTarget()
                    return null
                } catch (e: Exception) {
                    Log.e("DTCERR", e.message ?: "")
                    mHandler.obtainMessage(OBD_COMMAND_FAILURE).sendToTarget()
                } finally {
                    closeSocket(sock)
                }
            }
            return result
        }

        fun closeSocket(sock: BluetoothSocket?) {
            if (sock != null) {
                try {
                    sock.close()
                } catch (e: IOException) {
                    Log.e(TAG, e.message ?: "")
                }
            }
        }

        override fun onProgressUpdate(vararg values: Int?) {
            progressDialog?.progress = values[0]!!
        }

        override fun onPostExecute(result: String?) {
            progressDialog?.dismiss()
            mHandler.obtainMessage(DATA_OK, result).sendToTarget()
        }
    }

    companion object {
        private val TAG = TroubleCodesActivity::class.java.name
        private const val NO_BLUETOOTH_DEVICE_SELECTED = 0
        private const val CANNOT_CONNECT_TO_DEVICE = 1
        private const val NO_DATA = 3
        private const val DATA_OK = 4
        private const val OBD_COMMAND_FAILURE = 10
        private const val OBD_COMMAND_FAILURE_IO = 11
        private const val OBD_COMMAND_FAILURE_UTC = 12
        private const val OBD_COMMAND_FAILURE_IE = 13
        private const val OBD_COMMAND_FAILURE_MIS = 14
        private const val OBD_COMMAND_FAILURE_NODATA = 15
    }
}
