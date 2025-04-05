package dz.abdopr.apps.ussdcommand

import android.content.SharedPreferences
import com.google.gson.Gson

data class RedisConfig(
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    val ssl: Boolean = false,
    val database: Int = 0,
    val timeout: Long = 2000L,
    val channelName: String = "android-client",
) {
    fun getRedisURI(): String {
        val auth = if (username != null && password != null) {
            "$username:$password@"
        } else if (password != null) {
            ":$password@"
        } else {
            ""
        }
        val protocol = if (ssl) "rediss" else "redis"

        return "$protocol://$auth$host:$port/$database"
    }

    fun toMap(): Map<String, Any?> {
        return mapOf(
            "host" to host,
            "port" to port,
            "password" to password,
            "ssl" to ssl,
            "database" to database,
            "timeout" to timeout,
            "channelName" to channelName
        )
    }

    fun toJson(): String {
        return Gson().toJson(toMap())
    }

    fun saveToPreferences(preferences: SharedPreferences) {
        val editor = preferences.edit()
        editor.putString("redis_config", toJson())
        editor.apply()
    }

    companion object {
        fun fromJson(json: String): RedisConfig {
            val map = Gson().fromJson(json, Map::class.java)
            return RedisConfig(
                host = map["host"] as String,
                port = (map["port"] as Double).toInt(),
                password = map["password"] as? String,
                ssl = map["ssl"] as Boolean,
                database = (map["database"] as Double).toInt(),
                timeout = (map["timeout"] as Double).toLong(),
                channelName = map["channelName"] as String
            )
        }

        fun loadFromPreferences(preferences: SharedPreferences): RedisConfig? {
            val json = preferences.getString("redis_config", null) ?: return null
            return fromJson(json)
        }
    }

}