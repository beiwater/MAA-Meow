package com.aliothmoon.maameow.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aliothmoon.maameow.BuildConfig
import com.aliothmoon.maameow.MainActivity
import com.aliothmoon.maameow.R
import com.aliothmoon.maameow.ai.mcp.MaaMcpServerFactory
import com.aliothmoon.maameow.ai.mcp.MaaToolBridge
import com.aliothmoon.maameow.data.preferences.AppSettingsManager
import com.aliothmoon.maameow.domain.service.SpecialUseFgsGate
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.UUID

/**
 * Foreground service hosting the thin MCP HTTP server (`:ai-mcp`).
 * Only started when the user explicitly enables AI Control in settings.
 * Zero cold-start impact when disabled.
 */
class AiControlService : Service() {

    companion object {
        private const val TAG = "AiControlService"
        private const val NOTIFICATION_ID = 9080
        private const val CHANNEL_ID = "ai_control_mcp"
        const val ACTION_START = "com.aliothmoon.maameow.ai.ACTION_START"
        const val ACTION_STOP = "com.aliothmoon.maameow.ai.ACTION_STOP"

        private val _runningEndpoint = MutableStateFlow<String?>(null)
        val runningEndpoint: StateFlow<String?> = _runningEndpoint.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, AiControlService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AiControlService::class.java)
            context.stopService(intent)
        }
    }

    private val appSettings: AppSettingsManager by inject()
    private val toolBridge: MaaToolBridge by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var httpServer: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServerAndSelf()
            return START_NOT_STICKY
        }

        ensureNotificationChannel()
        val host = if (appSettings.aiMcpLanEnabled.value) "0.0.0.0" else "127.0.0.1"
        val port = appSettings.aiMcpPort.value
        val endpointText = "http://$host:$port/mcp"

        startAsForeground(buildNotification(endpointText))

        serviceScope.launch {
            appSettings.awaitLoaded()
            if (!appSettings.aiControlEnabled.value) {
                Timber.i("$TAG: AI Control is disabled in settings; stopping service")
                stopServerAndSelf()
                return@launch
            }
            val resolvedHost = if (appSettings.aiMcpLanEnabled.value) "0.0.0.0" else "127.0.0.1"
            val resolvedPort = appSettings.aiMcpPort.value
            var token = appSettings.aiMcpBearerToken.value
            if (token.isBlank()) {
                token = UUID.randomUUID().toString().replace("-", "")
                appSettings.setAiMcpBearerToken(token)
            }
            restartHttpServer(resolvedHost, resolvedPort, token)
        }

        return START_STICKY
    }

    @Synchronized
    private fun restartHttpServer(host: String, port: Int, token: String) {
        runCatching { httpServer?.stop(500, 1000) }
        httpServer = null
        try {
            val server = MaaMcpServerFactory.createHttpServer(
                host = host,
                port = port,
                bearerToken = token,
                bridge = toolBridge,
                screenshotEnabled = { appSettings.aiScreenshotEnabled.value },
                version = BuildConfig.VERSION_NAME,
            )
            server.start(wait = false)
            httpServer = server
            val endpoint = "http://$host:$port/mcp"
            _runningEndpoint.value = endpoint
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(endpoint))
            Timber.i("$TAG: MCP server listening on %s", endpoint)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start MCP HTTP server on %s:%d", host, port)
            _runningEndpoint.value = null
        }
    }

    private fun stopServerAndSelf() {
        serviceScope.launch {
            runCatching { httpServer?.stop(500, 1000) }
            httpServer = null
            _runningEndpoint.value = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.ai_control_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.ai_control_notification_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startAsForeground(notification: Notification) {
        try {
            SpecialUseFgsGate.startForeground(this, NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Timber.w(e, "$TAG: startForeground denied, running without FGS")
        }
    }

    private fun buildNotification(endpoint: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AiControlService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_maa_logo)
            .setContentTitle(getString(R.string.ai_control_notification_title))
            .setContentText(getString(R.string.ai_control_notification_text, endpoint))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.common_cancel), stopIntent)
            .build()
    }

    override fun onDestroy() {
        runCatching { httpServer?.stop(200, 500) }
        httpServer = null
        _runningEndpoint.value = null
        serviceScope.cancel()
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }
}
