package com.github.pires.obd.reader.net

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Definition of REST service available in OBD Server.
 */
interface ObdService {
    @POST("/")
    fun uploadReading(@Body reading: ObdReading): Call<ResponseBody>
}
