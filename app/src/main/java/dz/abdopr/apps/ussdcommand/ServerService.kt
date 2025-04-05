package dz.abdopr.apps.ussdcommand

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ServerService : Service() {

    companion object {
        const val SERVER_STARTED = "SERVER_STARTED"
        const val SERVER_STOPPED = "SERVER_STOPPED"
        const val SERVER_FAILURE = "SERVER_FAILURE"

        @SuppressLint("StaticFieldLeak")
        private var server: LocalServer? = null

        // Check the server status on rerun the app
        fun isRunning(): Boolean {
            return server?.isAlive == true
        }
    }

    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, createNotification())

        try {
            server = LocalServer(applicationContext)
            server?.start()
            sendBroadcast(Intent("SERVICE_STATUS").putExtra(SERVER_STARTED, isRunning()))
        } catch (e: Exception) {
            e.printStackTrace()
            sendBroadcast(Intent("SERVICE_STATUS").putExtra(SERVER_FAILURE, true))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        sendBroadcast(Intent("SERVICE_STATUS").putExtra(SERVER_STOPPED, true))
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "server_channel")
            .setContentTitle("Server Running")
            .setContentText("Your server is running in the background.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "server_channel",
                "Server Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }
}