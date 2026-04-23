package com.github.pires.obd.reader.io

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.util.Log
import com.github.pires.obd.reader.activity.MainActivity
import java.io.IOException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

abstract class AbstractGatewayService : Service() {
    private val binder = AbstractGatewayServiceBinder()

    protected lateinit var notificationManager: NotificationManager
    protected var ctx: Context? = null
    var isRunning = false
        protected set
    protected var queueCounter = 0L
    protected var jobsQueue: BlockingQueue<ObdCommandJob> = LinkedBlockingQueue()

    // Run the executeQueue in a different thread to lighten the UI thread
    private val t = Thread {
        try {
            executeQueue()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Creating service..")
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        t.start()
        Log.d(TAG, "Service created.")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Destroying service...")
        notificationManager.cancel(NOTIFICATION_ID)
        t.interrupt()
        Log.d(TAG, "Service destroyed.")
    }

    fun queueEmpty(): Boolean {
        return jobsQueue.isEmpty()
    }

    /**
     * This method will add a job to the queue while setting its ID to the
     * internal queue counter.
     *
     * @param job the job to queue.
     */
    open fun queueJob(job: ObdCommandJob) {
        queueCounter++
        Log.d(TAG, "Adding job[$queueCounter] to queue..")
        job.id = queueCounter
        try {
            jobsQueue.put(job)
            Log.d(TAG, "Job queued successfully.")
        } catch (e: InterruptedException) {
            job.state = ObdCommandJob.ObdCommandJobState.QUEUE_ERROR
            Log.e(TAG, "Failed to queue job.")
        }
    }

    /**
     * Show a notification while this service is running.
     */
    protected fun showNotification(
        contentTitle: String,
        contentText: String,
        icon: Int,
        ongoing: Boolean,
        notify: Boolean,
        vibrate: Boolean
    ) {
        val channelId = "obd_reader_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "OBD Reader Service",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(ctx, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentIntent = PendingIntent.getActivity(ctx, 0, intent, flags)
        val notificationBuilder = NotificationCompat.Builder(ctx!!, channelId)
        notificationBuilder.setContentTitle(contentTitle)
            .setContentText(contentText).setSmallIcon(icon)
            .setContentIntent(contentIntent)
            .setWhen(System.currentTimeMillis())
        // can cancel?
        if (ongoing) {
            notificationBuilder.setOngoing(true)
        } else {
            notificationBuilder.setAutoCancel(true)
        }
        if (vibrate) {
            notificationBuilder.setDefaults(Notification.DEFAULT_VIBRATE)
        }
        if (notify) {
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
        }
    }

    fun setContext(c: Context?) {
        ctx = c
    }

    @Throws(InterruptedException::class)
    protected abstract fun executeQueue()

    @Throws(IOException::class)
    abstract fun startService()

    abstract fun stopService()

    inner class AbstractGatewayServiceBinder : Binder() {
        val service: AbstractGatewayService
            get() = this@AbstractGatewayService
    }

    companion object {
        const val NOTIFICATION_ID = 1
        private val TAG = AbstractGatewayService::class.java.name
    }
}
