package dz.abdopr.apps.ussdcommand

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionInfo
import androidx.annotation.RequiresApi
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import java.math.BigInteger
import java.security.MessageDigest

@RequiresApi(Build.VERSION_CODES.O)
class AndroidClientRedisChannel(
    context: Context,
    redisPubSubConnection: StatefulRedisPubSubConnection<String, String>,
):  RedisChannel(
    redisPubSubConnection,
    channelName = "android-client"
) {
    init {
        bind("ping") { _ ->
            emit("pong", mapOf(
                "message" to "Yes, I am alive",
                "date" to System.currentTimeMillis(),
                "deviceFingerprint" to Build.FINGERPRINT,
            ))
        }
        bind("command") { data ->
            data as? Map<*, *> ?: let {
                emit("error", mapOf(
                    "message" to "Invalid data format",
                    "date" to System.currentTimeMillis(),
                ))
                return@bind
            }

            val code = data["code"] as? String ?: let {
                emit("error", mapOf(
                    "message" to "Code is missing",
                    "date" to System.currentTimeMillis(),
                ))
                return@bind
            }
            val subscriptionId = data["subscriptionId"] as? Int ?: MainActivity.getSubscriptions(context).firstOrNull()?.subscriptionId ?: let {
                emit("error", mapOf(
                    "message" to "No active subscription found",
                    "date" to System.currentTimeMillis(),
                ))
                return@bind
            }

            val response = MainActivity.executeCommand(context, code, subscriptionId)
            emit("response", mapOf(
                "message" to response,
                "date" to System.currentTimeMillis(),
            ))
        }
    }

    fun subscribe(subscriptions: List<SubscriptionInfo>) {
        subscribe()
        val payload = mapOf(
            "deviceName" to Build.MODEL,
            "deviceVersion" to Build.VERSION.RELEASE,
            "deviceManufacturer" to Build.MANUFACTURER,
            "sdkVersion" to Build.VERSION.SDK_INT,
            "deviceBrand" to Build.BRAND,
            "deviceProduct" to Build.PRODUCT,
            "deviceHardware" to Build.HARDWARE,
            "deviceFingerprint" to Build.FINGERPRINT,
            "subscriptions" to subscriptions.map {
                mapOf(
                    "id" to it.subscriptionId,
                    "number" to it.number,
                    "name" to it.displayName.toString(),
                )
            },
        )
        emit("connect", payload)
    }

    override fun unsubscribe() {
        emit("disconnect", mapOf(
            "deviceName" to Build.MODEL,
            "deviceFingerprint" to Build.FINGERPRINT,
            "reason" to "User disconnected",
        ))
        super.unsubscribe()
    }
}