package dev.zain.agentremote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.zain.agentremote.data.BackendKind
import dev.zain.agentremote.data.displayName

enum class AgentFinishOutcome {
    COMPLETED,
    FAILED,
    CANCELLED,
}

/**
 * Local notifications when a durable host turn finishes while the user is not
 * watching that chat. Channels are created lazily; posting is a no-op without
 * [Manifest.permission.POST_NOTIFICATIONS] or when the user disabled system
 * notifications for the app.
 */
object AgentFinishNotifier {
    const val EXTRA_SESSION_ID = "dev.zain.agentremote.extra.SESSION_ID"
    const val EXTRA_BACKEND = "dev.zain.agentremote.extra.BACKEND"

    private const val CHANNEL_ID = "agent_finished"
    private const val NOTIFICATION_BASE_ID = 30_000

    fun ensureChannels(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_agent_finished),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_agent_finished_desc)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(true)
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun canPostNotifications(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED &&
            manager.areNotificationsEnabled()
    }

    fun notificationId(backend: BackendKind, sessionId: String): Int {
        return NOTIFICATION_BASE_ID + (31 * backend.ordinal + sessionId.hashCode())
    }

    @SuppressLint("MissingPermission")
    fun notifyFinished(
        context: Context,
        backend: BackendKind,
        sessionId: String,
        title: String,
        outcome: AgentFinishOutcome,
        errorMessage: String? = null,
    ) {
        ensureChannels(context)
        if (!canPostNotifications(context)) return

        val body = when (outcome) {
            AgentFinishOutcome.COMPLETED ->
                context.getString(R.string.notification_agent_finished_body)
            AgentFinishOutcome.FAILED ->
                errorMessage?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.notification_agent_failed_body)
            AgentFinishOutcome.CANCELLED ->
                context.getString(R.string.notification_agent_stopped_body)
        }
        val displayTitle = title.ifBlank {
            context.getString(
                R.string.notification_agent_default_title,
                backend.displayName,
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(displayTitle)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openSessionIntent(context, backend, sessionId))
            .build()

        NotificationManagerCompat.from(context).notify(
            notificationId(backend, sessionId),
            notification,
        )
    }

    fun cancel(context: Context, backend: BackendKind, sessionId: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(backend, sessionId))
    }

    private fun openSessionIntent(
        context: Context,
        backend: BackendKind,
        sessionId: String,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_BACKEND, backend.name)
        }
        return PendingIntent.getActivity(
            context,
            notificationId(backend, sessionId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
