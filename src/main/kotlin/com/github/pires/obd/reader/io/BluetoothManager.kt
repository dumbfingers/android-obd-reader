package com.github.pires.obd.reader.io

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.util.*

object BluetoothManager {

    private val TAG = BluetoothManager::class.java.name

    /*
     * http://developer.android.com/reference/android/bluetooth/BluetoothDevice.html
     * #createRfcommSocketToServiceRecord(java.util.UUID)
     *
     * "Hint: If you are connecting to a Bluetooth serial board then try using the
     * well-known SPP UUID 00001101-0000-1000-8000-00805F9B34FB. However if you
     * are connecting to an Android peer then please generate your own unique
     * UUID."
     */
    private val MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /**
     * Instantiates a BluetoothSocket for the remote device and connects it.
     * <p/>
     * See http://stackoverflow.com/questions/18657427/ioexception-read-failed-socket-might-closed-bluetooth-on-android-4-3/18786701#18786701
     *
     * @param dev The remote device to connect to
     * @return The BluetoothSocket
     * @throws IOException
     */
    @Throws(IOException::class)
    fun connect(dev: BluetoothDevice?): BluetoothSocket? {
        var sock: BluetoothSocket? = null
        var sockFallback: BluetoothSocket? = null
        Log.d(TAG, "Starting Bluetooth connection..")
        try {
            sock = dev!!.createRfcommSocketToServiceRecord(MY_UUID)
            sock.connect()
        } catch (e1: Exception) {
            Log.e(TAG, "There was an error while establishing Bluetooth connection. Falling back..", e1)
            val clazz = sock!!.remoteDevice.javaClass
            val paramTypes = arrayOf<Class<*>>(Int::class.javaPrimitiveType!!)
            try {
                val m = clazz.getMethod("createRfcommSocket", *paramTypes)
                val params = arrayOf<Any>(1)
                sockFallback = m.invoke(sock.remoteDevice, *params) as BluetoothSocket
                sockFallback.connect()
                sock = sockFallback
            } catch (e2: Exception) {
                Log.e(TAG, "Couldn't fallback while establishing Bluetooth connection.", e2)
                throw IOException(e2.message)
            }
        }
        return sock
    }
}
