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
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import io.lettuce.core.RedisClient
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RedisService : Service() {

    companion object {
        const val CONNECTION_SUCCESS = "REDIS_CONNECTION_SUCCESS"
        const val CONNECTION_FAILURE = "REDIS_CONNECTION_FAILURE"

        @SuppressLint("StaticFieldLeak")
        private var redisPubSubConnection:  StatefulRedisPubSubConnection<String, String>? = null

        fun isConnected(): Boolean {
            return redisPubSubConnection?.isOpen == true
        }
    }

    private var androidClientChannel: AndroidClientRedisChannel? = null

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, createNotification())

        val context = this
        coroutineScope.launch {
            val redisConfig: RedisConfig? = RedisConfig.loadFromPreferences(
                getSharedPreferences("redis_config", MODE_PRIVATE)
            )

            redisConfig?.let {
                try {
                    val redisClient = RedisClient.create(it.getRedisURI())
                    redisPubSubConnection = redisClient.connectPubSub()
                    androidClientChannel = AndroidClientRedisChannel(context, redisPubSubConnection!!)
                    androidClientChannel?.subscribe(MainActivity.getSubscriptions(context))

                    sendBroadcast(Intent("SERVICE_STATUS").putExtra(CONNECTION_SUCCESS, isConnected()))
                } catch (e: Exception) {
                    e.printStackTrace()

                    Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()

                    sendBroadcast(Intent("SERVICE_STATUS").putExtra(CONNECTION_FAILURE, true))
                }
            } ?: run {
                // Handle the case where RedisConfig is null
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.launch {
            androidClientChannel?.unsubscribe()
            redisPubSubConnection?.close()
            sendBroadcast(Intent("SERVICE_STATUS").putExtra(CONNECTION_FAILURE, true))
        }
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

        return NotificationCompat.Builder(this, "redis_client_channel")
            .setContentTitle("Client Connected")
            .setContentText("Your redis client is connected in the background.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "redis_client_channel",
                "Redis Client Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }
}