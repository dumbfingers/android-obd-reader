package com.github.pires.obd.reader.activity

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class MainViewModel : ViewModel() {
    private val _gpsStatus = MutableLiveData<String>()
    val gpsStatus: LiveData<String> = _gpsStatus

    private val _btStatus = MutableLiveData<String>()
    val btStatus: LiveData<String> = _btStatus

    private val _obdStatus = MutableLiveData<String>()
    val obdStatus: LiveData<String> = _obdStatus

    private val _compassDirection = MutableLiveData<String>()
    val compassDirection: LiveData<String> = _compassDirection

    private val _obdData = MutableLiveData<Map<String, String>>(emptyMap())
    val obdData: LiveData<Map<String, String>> = _obdData

    fun updateGpsStatus(status: String) {
        _gpsStatus.postValue(status)
    }

    fun updateBtStatus(status: String) {
        _btStatus.postValue(status)
    }

    fun updateObdStatus(status: String) {
        _obdStatus.postValue(status)
    }

    fun updateCompass(direction: String) {
        _compassDirection.postValue(direction)
    }

    fun updateObdData(id: String, value: String) {
        val current = _obdData.value ?: emptyMap()
        _obdData.postValue(current + (id to value))
    }

    fun clearObdData() {
        _obdData.postValue(emptyMap())
    }
}
