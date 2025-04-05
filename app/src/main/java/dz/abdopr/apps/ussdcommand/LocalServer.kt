package dz.abdopr.apps.ussdcommand

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileNotFoundException
import java.io.InputStream


@Suppress("DEPRECATION")
class LocalServer(
    private val context: Context,
) : NanoHTTPD(8080) {

    @RequiresApi(Build.VERSION_CODES.O)
    override fun serve(session: IHTTPSession): Response {
        val url = session.uri.toString()
        Log.d("HTTPServe", "Request: $url")

        val response = when {
            session.method == Method.OPTIONS -> newFixedLengthResponse(
                Response.Status.OK,
                "text/plain",
                ""
            )

            url.startsWith("/assets/") -> handleAssets(session)
            session.method == Method.GET && url == "/" -> sendAssetFile("index.html")
            session.method == Method.GET && url == "/subscriptions" -> handleSubscriptions()
            session.method == Method.POST && url == "/command" -> handleCommand(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
        }
        // Add CORS headers
        response.addHeader("Access-Control-Allow-Origin", "*") // Allow all origins
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS") // Allowed methods
        response.addHeader("Access-Control-Allow-Headers", "Content-Type") // Allowed headers
        return response
    }

    private fun parseBody(session: IHTTPSession): Map<*, *>? {
        // Parse the JSON body from the POST request
        val files: Map<String, String> = HashMap()
        session.parseBody(files);

        // get the POST body
        val postBody = files.get("postData") ?: return null

        // parse the JSON body
        val gson = Gson()
        return gson.fromJson(postBody, Map::class.java)
    }

    private fun handleAssets(session: IHTTPSession): Response {
        Log.d("AssetServe", "Serving asset file: ${session.uri}")
        val uri = session.uri.toString()
        val name = uri.substringAfterLast("/assets/")
        return sendAssetFile(name)
    }

    private fun sendAssetFile(filename: String): Response {
        return try {
            val inputStream: InputStream = context.assets.open(filename)
            val fileContent = inputStream.bufferedReader().use { it.readText() }
            val fileMemeType = when {
                filename.endsWith(".html") -> "text/html"
                filename.endsWith(".css") -> "text/css"
                filename.endsWith(".js") -> "application/javascript"
                filename.endsWith(".json") -> "application/json"
                else -> "text/plain"
            }

            newFixedLengthResponse(Response.Status.OK, fileMemeType, fileContent)
        } catch (e: FileNotFoundException) {
            Log.e("LocalNotFoundError", "Cannot find file", e)
            newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "File cannot be found"
            )
        } catch (e: Exception) {
            Log.e("ServeError", "Error serving file", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Error: ${e.message}"
            )
        }
    }

    private fun handleSubscriptions(): Response {
        Log.d("HTTPSubscriptions", "Handling subscriptions")

        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            Gson().toJson(
                mapOf(
                    "subscriptions" to MainActivity.getSubscriptions(context).map {
                        mapOf(
                            "id" to it.subscriptionId,
                            "name" to it.displayName,
                            "number" to (it.number?.toString() ?: "Unknown")
                        )
                    }
                )
            )
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun handleCommand(session: IHTTPSession): Response {
        Log.d("HTTPCommand", "Handling command")
        val body = try {
            parseBody(session)
        } catch (e: Exception) {
            Log.e("HTTPError", "Error parsing body", e)
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "text/plain",
                "Error parsing body"
            )
        } ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST,
            "text/plain",
            "Missing body parameters"
        )

        val code = body["code"] ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST,
            "text/plain",
            "Missing 'code' parameter"
        )

        val subscriptionId =
            body["subscriptionId"] as? Int ?: MainActivity.getSubscriptions(context).firstOrNull()?.subscriptionId
            ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "text/plain",
                "Missing 'subscriptionId' parameter"
            )

        return try {
            Log.d("USSDCommand", "code: $code")

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

            // Wait for the USSD response with a timeout (e.g., 30 seconds)
            val responseText = runBlocking {
                withTimeoutOrNull(30_000) { // 30 seconds timeout
                    responseDeferred.await()
                } ?: "Timeout: USSD response not received within 30 seconds"
            }

            // Return the response to the client
            newFixedLengthResponse(
                Response.Status.OK,
                "text/plain",
                responseText
            )
        } catch (e: Exception) {
            Log.e("USSDError", "Error executing USSD command", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Error: ${e.message}"
            )
        }
    }

    override fun start() {
        try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.d("LocalServer", "Server started at http://localhost:8080/")
        } catch (e: Exception) {
            Log.e("LocalServerError", "Error starting server", e)
        }
    }
}