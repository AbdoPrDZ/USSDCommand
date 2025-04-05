package dz.abdopr.apps.ussdcommand

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {

    private lateinit var ipEditText: TextView
    private lateinit var portEditText: TextView
    private lateinit var usernameEditText: TextView
    private lateinit var passwordEditText: TextView
    private lateinit var connectButton: Button
    private lateinit var serverBtn: Button
    private lateinit var urlTxt: TextView

    companion object {
        fun getSubscriptions(context: Context): List<SubscriptionInfo> {
            val subscriptionManager =
                context.getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            if (ActivityCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.READ_PHONE_STATE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                return subscriptionManager.activeSubscriptionInfoList ?: emptyList()
            }
            return emptyList()
        }

        @RequiresApi(Build.VERSION_CODES.O)
        fun executeCommand(context: Context, code: String, subscriptionId: Int, timeOutCount: Long = 30_000): String {
            try {
                // Use a CompletableDeferred to wait for the USSD response
                val responseDeferred = CompletableDeferred<String>()

                // Use reflection to call sendUssdRequest
                val telephonyManager =
                    context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val subTelephonyManager = telephonyManager.createForSubscriptionId(subscriptionId)
                val telephonyClass = Class.forName(subTelephonyManager.javaClass.name)

                val method = telephonyClass.getDeclaredMethod(
                    "sendUssdRequest",
                    String::class.java,
                    TelephonyManager.UssdResponseCallback::class.java,
                    Handler::class.java
                )
                method.isAccessible = true
                method.invoke(
                    subTelephonyManager,
                    code,
                    @RequiresApi(Build.VERSION_CODES.O)
                    object : TelephonyManager.UssdResponseCallback() {
                        override fun onReceiveUssdResponse(
                            telephonyManager: TelephonyManager?,
                            request: String?,
                            response: CharSequence?
                        ) {
                            Log.d("USSDResponse", "Response: $response")
                            responseDeferred.complete(response.toString())
                        }

                        override fun onReceiveUssdResponseFailed(
                            telephonyManager: TelephonyManager?,
                            request: String?,
                            failureCode: Int
                        ) {
                            Log.d("USSDResponse", "Failed: $failureCode")
                            responseDeferred.complete("Failed: $failureCode")
                        }
                    },
                    Handler(Looper.getMainLooper())
                )

                // Wait for the response
                return runBlocking {
                    withTimeoutOrNull(timeOutCount) {
                        responseDeferred.await()
                    } ?: "Timeout: USSD response not received within ${timeOutCount / 1000} seconds"
                }
            } catch (e: Exception) {
                Log.e("USSDCommand", "Error executing command: ${e.message}")
                e.printStackTrace()
                return "Error: ${e.message}"
            }
        }
    }

    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            intent?.let {
                if (it.action == "SERVICE_STATUS") {
                    val isConnectionFailure = it.getBooleanExtra(RedisService.CONNECTION_FAILURE, false)
                    val isServerStopped = it.getBooleanExtra(ServerService.SERVER_STOPPED, false)
                    val isServerFailure = it.getBooleanExtra(ServerService.SERVER_FAILURE, false)

                    if (isConnectionFailure) {
                        stopService(RedisService::class.java)
                    } else if (isServerStopped || isServerFailure) {
                        stopService(ServerService::class.java)
                    }

                    updateUI()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) {
                showPermissionDeniedDialog()
            }
        }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipEditText = findViewById(R.id.ipEditText)
        portEditText = findViewById(R.id.portEditText)
        usernameEditText = findViewById(R.id.usernameEditText)
        passwordEditText = findViewById(R.id.passwordEditText)
        connectButton = findViewById(R.id.connectButton)

        serverBtn = findViewById(R.id.serverBtn)
        urlTxt = findViewById(R.id.urlTxt)

        val filter = IntentFilter("SERVICE_STATUS")
        registerReceiver(connectionReceiver, filter)

        updateUI()

        connectButton.setOnClickListener {
            if (RedisService.isConnected()) {
                stopService(RedisService::class.java)
            } else {
                val ip = ipEditText.text.toString()
                val port = portEditText.text.toString().toIntOrNull() ?: 0
                val username = usernameEditText.text.toString()
                val password = passwordEditText.text.toString()
                val redisConfig = RedisConfig(
                    host = ip,
                    port = port,
                    username = username,
                    password = password,
                    ssl = false,
                    database = 0,
                    timeout = 2000L,
                    channelName = "android-client"
                )
                redisConfig.saveToPreferences(
                    getSharedPreferences("redis_config", MODE_PRIVATE)
                )
                startService(RedisService::class.java)
            }
        }

        serverBtn.setOnClickListener {
            if (ServerService.isRunning()) {
                stopService(ServerService::class.java)
            } else {
                startService(ServerService::class.java)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startService(service: Class<*>): ComponentName? {
        if (checkPermissions()) {
            val res = startForegroundService(Intent(this, service))
            updateUI()
            return res
        }
        return null
    }

    @SuppressLint("ImplicitSamInstance")
    private fun stopService(service: Class<*>): Boolean {
        val res = stopService(Intent(this, service))
        updateUI()
        return res
    }

    private fun updateUI() {
        if (ServerService.isRunning()) {
            serverBtn.text = "Stop Server"
            serverBtn.backgroundTintList = getColorStateList(R.color.red)
            urlTxt.text = "http://${getLocalIPAddress()}:8080"
            urlTxt.visibility = VISIBLE
        } else {
            serverBtn.text = "Start Server"
            serverBtn.backgroundTintList = getColorStateList(R.color.green)
            urlTxt.visibility = GONE
        }

        if (RedisService.isConnected()) {
            connectButton.text = "Disconnect"
            connectButton.backgroundTintList = getColorStateList(R.color.red)
            ipEditText.isEnabled = false
            portEditText.isEnabled = false
            usernameEditText.isEnabled = false
            passwordEditText.isEnabled = false
        } else {
            connectButton.text = "Connect"
            connectButton.backgroundTintList = getColorStateList(R.color.green)
            ipEditText.isEnabled = true
            portEditText.isEnabled = true
            usernameEditText.isEnabled = true
            passwordEditText.isEnabled = true
        }
    }

    private fun getLocalIPAddress(): String? {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (inf in interfaces) {
            for (address in Collections.list(inf.inetAddresses)) {
                if (!address.isLoopbackAddress && address is Inet4Address) {
                    return address.hostAddress
                }
            }
        }
        return null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun checkPermissions(): Boolean {
        val permissions = arrayOf(
            android.Manifest.permission.CALL_PHONE,
            android.Manifest.permission.READ_PHONE_STATE,
        )
        for (permission in permissions)
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                requestPermission(permission)
                return false
            }
        return true
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun requestPermission(permission: String) {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
            showPermissionRationaleDialog(permission)
        } else {
            permissionLauncher.launch(permission)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun showPermissionRationaleDialog(permission: String) {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("This app requires access to your phone state to function properly.")
            .setPositiveButton("OK") { _, _ ->
                permissionLauncher.launch(permission)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Denied")
            .setMessage("You have denied the permission. Go to settings to enable it manually.")
            .setPositiveButton("Open Settings") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = "package:$packageName".toUri()
        startActivity(intent)
    }
}