package dz.abdopr.apps.ussdcommand

import android.os.Build
import android.util.Log
import com.google.gson.Gson
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.math.BigInteger
import java.security.MessageDigest

open class RedisChannel(
    private val redisPubSubConnection: StatefulRedisPubSubConnection<String, String>,
    private val channelName: String
) {
    private val listeners: HashMap<String, MutableList<(data: Any?) -> Unit>> = HashMap()

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        fun md5(input:String): String {
            val md = MessageDigest.getInstance("MD5")
            return BigInteger(1, md.digest(input.toByteArray())).toString(16).padStart(32, '0')
        }

        fun getDeviceId(): String {
            return md5(Build.FINGERPRINT)
        }
    }

    init {
        redisPubSubConnection.addListener(object : RedisPubSubAdapter<String, String>() {
            override fun message(channel: String, message: String) {
                Log.d("Redis", "Received message: $message from channel: $channel")

                if (channel == channelName && message.isNotEmpty()) {
                    try {
                        val payload = Gson().fromJson(message, Map::class.java)

                        val source = payload["source"] as? String
                        if (source != null && source == getDeviceId()) {
                            Log.d("Redis", "Ignoring message from self: $message")
                            return
                        }
                        val target = payload["target"] as? String
                        if (target != null && target != getDeviceId()) {
                            Log.d("Redis", "Ignoring message not for this device: $message")
                            return
                        }

                        val dataJson = payload["data"] as? String
                        val event = payload["event"] as? String

                        if (event != null && dataJson != null) {
                            val data = Gson().fromJson(dataJson, Any::class.java)
                            listeners[event]?.forEach { listener ->
                                listener(data)
                            }
                        } else {
                            Log.e("Redis", "Invalid payload: $message")
                        }
                    } catch (e: Exception) {
                        Log.e("Redis", "Error processing message: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
        })
    }

    open fun subscribe() {
        coroutineScope.launch {
            try {
                Log.d("Redis", "Subscribing to channel: $channelName")
                redisPubSubConnection.sync().subscribe(channelName)
            } catch (e: Exception) {
                Log.e("Redis", "Error subscribing: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    open fun unsubscribe() {
        coroutineScope.launch {
            try {
                Log.d("Redis", "Unsubscribing from channel: $channelName")
                redisPubSubConnection.sync().unsubscribe(channelName)
            } catch (e: Exception) {
                Log.e("Redis", "Error unsubscribing: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    fun bind(event: String, listener: (data: Any?) -> Unit) {
        Log.d("Redis", "$channelName - Binding event: $event")
        listeners.computeIfAbsent(event) { mutableListOf() }.add(listener)
    }

    fun unbind(event: String, listener: (data: Any?) -> Unit) {
        Log.d("Redis", "$channelName - Unbinding event: $event")
        listeners[event]?.remove(listener)
        if (listeners[event]?.isEmpty() == true) {
            listeners.remove(event)
        }
    }

    fun emit(event: String, data: Any?) {
        coroutineScope.launch {
            try {
                val payload = mapOf(
                    "source" to getDeviceId(),
                    "event" to event,
                    "data" to Gson().toJson(data)
                )
                Log.d("Redis", "$channelName - Emitting event: $event with payload: $payload")
                redisPubSubConnection.sync().publish(channelName, Gson().toJson(payload))
            } catch (e: Exception) {
                Log.e("Redis", "$channelName - Error emitting event: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}