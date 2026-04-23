package com.github.pires.obd.reader.io

import java.io.IOException

class MockObdGatewayService : AbstractGatewayService() {

    @Throws(IOException::class)
    override fun startService() {
        isRunning = true
        showNotification("Mock Service", "Mock Service Started", com.github.pires.obd.reader.R.drawable.ic_btcar, true, true, false)
    }

    override fun stopService() {
        isRunning = false
        stopSelf()
    }

    @Throws(InterruptedException::class)
    override fun executeQueue() {
        while (!Thread.currentThread().isInterrupted) {
            val job = jobsQueue.take()
            if (job.state == ObdCommandJob.ObdCommandJobState.NEW) {
                job.state = ObdCommandJob.ObdCommandJobState.RUNNING
                Thread.sleep(500)
                job.state = ObdCommandJob.ObdCommandJobState.FINISHED
            }
            (ctx as? com.github.pires.obd.reader.activity.MainActivity)?.runOnUiThread {
                (ctx as? com.github.pires.obd.reader.activity.MainActivity)?.stateUpdate(job)
            }
        }
    }
}
