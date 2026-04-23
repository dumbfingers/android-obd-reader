package com.github.pires.obd.reader.activity

import android.os.Bundle
import android.preference.CheckBoxPreference
import android.preference.ListPreference
import android.preference.Preference
import android.preference.PreferenceActivity
import android.preference.PreferenceScreen
import android.widget.Toast
import com.github.pires.obd.enums.ObdProtocols
import com.github.pires.obd.reader.R
import com.github.pires.obd.reader.config.ObdConfig

class ConfigActivity : PreferenceActivity(), Preference.OnPreferenceChangeListener {

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.preferences)

        val btDeviceList = findPreference(BLUETOOTH_LIST_KEY) as ListPreference
        val protocolsList = findPreference(PROTOCOLS_LIST_KEY) as ListPreference
        val obdCommandsScreen = findPreference(OBD_COMMANDS_SETTINGS_KEY) as PreferenceScreen

        val mBluetoothAdapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        if (mBluetoothAdapter == null) {
            btDeviceList.entries = arrayOf<CharSequence>()
            btDeviceList.entryValues = arrayOf<CharSequence>()
            return
        }

        val pairedDevices = mBluetoothAdapter.bondedDevices
        val deviceConfigEntries = arrayOfNulls<CharSequence>(pairedDevices.size)
        val deviceConfigEntryValues = arrayOfNulls<CharSequence>(pairedDevices.size)
        var i = 0
        for (dev in pairedDevices) {
            deviceConfigEntries[i] = dev.name
            deviceConfigEntryValues[i] = dev.address
            i++
        }

        btDeviceList.entries = deviceConfigEntries
        btDeviceList.entryValues = deviceConfigEntryValues

        val protocolEntries = ObdProtocols.values()
        val protocolConfigEntries = arrayOfNulls<CharSequence>(protocolEntries.size)
        val protocolConfigEntryValues = arrayOfNulls<CharSequence>(protocolEntries.size)
        for (j in protocolEntries.indices) {
            protocolConfigEntries[j] = protocolEntries[j].name
            protocolConfigEntryValues[j] = protocolEntries[j].name
        }

        protocolsList.entries = protocolConfigEntries
        protocolsList.entryValues = protocolConfigEntryValues

        for (command in ObdConfig.getCommands()) {
            val checkbox = CheckBoxPreference(this)
            checkbox.key = command.name
            checkbox.title = command.name
            // checkbox.summary = command.desc // desc does not exist in some versions of obd-java-api
            checkbox.setDefaultValue(true)
            obdCommandsScreen.addPreference(checkbox)
        }

        findPreference(ENABLE_BT_KEY).onPreferenceChangeListener = this
        findPreference(ENABLE_GPS_KEY).onPreferenceChangeListener = this
        findPreference(UPLOAD_DATA_KEY).onPreferenceChangeListener = this
        findPreference(ENABLE_FULL_LOGGING_KEY).onPreferenceChangeListener = this
        findPreference(DIRECTORY_FULL_LOGGING_KEY).onPreferenceChangeListener = this
        findPreference(VEHICLE_ID_KEY).onPreferenceChangeListener = this
        findPreference(UPLOAD_URL_KEY).onPreferenceChangeListener = this
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        if (UPLOAD_URL_KEY == preference.key || VEHICLE_ID_KEY == preference.key || DIRECTORY_FULL_LOGGING_KEY == preference.key) {
            try {
                val stringValue = newValue as String
                if (stringValue.isEmpty()) {
                    Toast.makeText(this, "The value cannot be empty.", Toast.LENGTH_LONG).show()
                    return false
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Invalid value.", Toast.LENGTH_LONG).show()
                return false
            }
        }
        return true
    }

    companion object {
        const val BLUETOOTH_LIST_KEY = "bluetooth_list_preference"
        const val UPLOAD_URL_KEY = "upload_url_preference"
        const val UPLOAD_DATA_KEY = "upload_data_preference"
        const val OBD_UPDATE_PERIOD_KEY = "obd_update_period_preference"
        const val VEHICLE_ID_KEY = "vehicle_id_preference"
        const val ENGINE_DISPLACEMENT_KEY = "engine_displacement_preference"
        const val VOLUMETRIC_EFFICIENCY_KEY = "volumetric_efficiency_preference"
        const val IMPERIAL_UNITS_KEY = "imperial_units_preference"
        const val COMMANDS_COMPLETED_KEY = "commands_completed_preference"
        const val MAX_FUEL_ECON_KEY = "max_fuel_econ_preference"
        const val CONFIG_READER_KEY = "reader_config_preference"
        const val ENABLE_GPS_KEY = "enable_gps_preference"
        const val GPS_UPDATE_PERIOD_KEY = "gps_update_period_preference"
        const val GPS_DISTANCE_PERIOD_KEY = "gps_distance_period_preference"
        const val ENABLE_BT_KEY = "enable_bluetooth_preference"
        const val ENABLE_FULL_LOGGING_KEY = "enable_full_logging_preference"
        const val DIRECTORY_FULL_LOGGING_KEY = "directory_full_logging_preference"
        const val DEV_EMAIL_KEY = "dev_email_preference"
        const val PROTOCOLS_LIST_KEY = "protocols_list_preference"
        const val OBD_COMMANDS_SETTINGS_KEY = "obd_commands_screen"

        fun getObdUpdatePeriod(prefs: android.content.SharedPreferences): Int {
            val periodString = prefs.getString(OBD_UPDATE_PERIOD_KEY, "4")
            val period = 4000 // default
            try {
                return (periodString!!.toDouble() * 1000).toInt()
            } catch (e: Exception) {
            }
            return period
        }

        fun getGpsUpdatePeriod(prefs: android.content.SharedPreferences): Int {
            val periodString = prefs.getString(GPS_UPDATE_PERIOD_KEY, "1")
            val period = 1000 // default
            try {
                return (periodString!!.toDouble() * 1000).toInt()
            } catch (e: Exception) {
            }
            return period
        }

        fun getGpsDistanceUpdatePeriod(prefs: android.content.SharedPreferences): Int {
            val periodString = prefs.getString(GPS_DISTANCE_PERIOD_KEY, "5")
            val period = 5 // default
            try {
                return periodString!!.toInt()
            } catch (e: Exception) {
            }
            return period
        }
    }
}
