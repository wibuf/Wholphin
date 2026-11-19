package com.github.damontecres.wholphin.util

import android.content.Context
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.data.MostRecentServer
import com.github.damontecres.wholphin.services.hilt.AppModule
import com.github.damontecres.wholphin.services.hilt.DeviceModule
import com.google.auto.service.AutoService
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.acra.config.CoreConfiguration
import org.acra.data.CrashReportData
import org.acra.sender.ReportSender
import org.acra.sender.ReportSenderException
import org.acra.sender.ReportSenderFactory
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.clientLogApi
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.json.JSONObject
import timber.log.Timber
import java.util.Date

/**
 * Sends a crash report to the current server
 */
@AutoService(ReportSenderFactory::class)
class CrashReportSenderFactory : ReportSenderFactory {
    override fun create(
        context: Context,
        config: CoreConfiguration,
    ): ReportSender = CrashReportSender()

    override fun enabled(config: CoreConfiguration): Boolean = true
}

class CrashReportSender : ReportSender {
    override fun send(
        context: Context,
        errorContent: CrashReportData,
    ) {
        Timber.v("Attempting to send crash report")
        try {
            val recentServer = AppModule.mostRecentServerProvider(context).get()
            val api = recentServer.createApi(context)
            if (api != null) {
                val obj = JSONObject()
                for ((key, value) in errorContent.toMap()) {
                    // Fork (GooseFlix): a stack overflow repeats one frame thousands of times
                    obj.put(key, if (BuildConfig.GOOSEFLIX && value is String && key == "STACK_TRACE") LogUpload.collapseRepeats(value) else value)
                }
                // Fork (GooseFlix): over the server's 1 MB limit the report was rejected and lost
                val jsonStr = obj.toString(2).let { if (BuildConfig.GOOSEFLIX) LogUpload.fit(it) else it }
                runBlocking {
                    val filename =
                        api.clientLogApi
                            .logFile(
                                """
                                ---
                                Date: ${Date()}
                                ---

                                """.trimIndent() + jsonStr,
                            ).content.fileName
                    Timber.i("Sent report to %s, filename=%s", api.baseUrl, filename)
                }
            } else {
                throw ReportSenderException("Could not find valid server and/or credentials to use")
            }
        } catch (ex: Exception) {
            throw ReportSenderException("Exception while sending crash report", ex)
        }
    }
}

private fun MostRecentServer.createApi(context: Context): ApiClient? =
    (this as? MostRecentServer.ServerAndUser)?.let {
        val okHttpClient =
            OkHttpClient
                .Builder()
                .build()
        val okHttpFactory = OkHttpFactory(okHttpClient)
        val api =
            createJellyfin {
                this.context = context
                clientInfo = AppModule.clientInfo(context)
                deviceInfo = DeviceModule.deviceInfo(context)
                apiClientFactory = okHttpFactory
                socketConnectionFactory = okHttpFactory
                minimumServerVersion = Jellyfin.minimumVersion
            }.createApi(baseUrl = serverUrl, accessToken = accessToken)
        api
    }
