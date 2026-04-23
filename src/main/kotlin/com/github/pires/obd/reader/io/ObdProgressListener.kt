package com.github.pires.obd.reader.io

interface ObdProgressListener {
    fun stateUpdate(job: ObdCommandJob)
}
