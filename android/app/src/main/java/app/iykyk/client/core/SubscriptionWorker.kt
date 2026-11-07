package app.iykyk.client.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.iykyk.client.MainActivity
import app.iykyk.client.R
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Background check every few hours: refreshes a stale subscription (so traffic and
 * expiry stay current) and reminds the user when it is about to end.
 */
class SubscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (ProfileStore.profiles.value.servers.isEmpty()) ProfileStore.load(context)
        SettingsStore.load(context)
        ProfileStore.refreshIfStale(context)
        ExpiryReminder.check(context)
        TrafficReminder.check(context)
        app.iykyk.client.update.Updater.checkInBackground(context)
        return Result.success()
    }

    companion object {
        private const val NAME = "subscription-check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SubscriptionWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** "Your subscription ends in N days" notification, at most once a day, from 3 days before. */
object ExpiryReminder {
    private const val CHANNEL_ID = "subscription"
    private const val NOTIFICATION_ID = 2
    private const val PREFS = "reminders"
    private const val KEY_LAST_DAY = "last_day"
    const val REMIND_DAYS = EXPIRY_REMIND_DAYS

    fun check(context: Context) {
        if (!SettingsStore.settings.value.expiryReminders) return
        val sub = ProfileStore.profiles.value.subscription ?: return
        val days = sub.daysLeft() ?: return
        if (days > REMIND_DAYS) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        if (prefs.getString(KEY_LAST_DAY, null) == today) return
        if (!canNotify(context)) return

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_subscription),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val text = when {
            days < 0 -> context.getString(R.string.expiry_ended)
            days == 0 -> context.getString(R.string.expiry_today)
            else -> context.resources.getQuantityString(R.plurals.expiry_in_days, days, days)
        }
        val openApp = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(sub.title.ifBlank { context.getString(R.string.app_name) })
            .setContentText(text)
            .setContentIntent(openApp)
            .setAutoCancel(true)
        val renewUrl = Purchase.renewUrl(context, sub)
        if (renewUrl != null) {
            val renew = PendingIntent.getActivity(
                context,
                11,
                Intent(Intent.ACTION_VIEW, Uri.parse(renewUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(R.drawable.ic_tile, context.getString(R.string.action_renew), renew)
        }
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        prefs.edit().putString(KEY_LAST_DAY, today).apply()
    }

    internal fun channel(context: Context): String {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_subscription),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        return CHANNEL_ID
    }

    internal fun canNotify(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED ||
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU
}

/**
 * "Traffic is almost used up" at 90% and "traffic is used up" at 100%, once per
 * subscription period and threshold (the key changes when the period or limit does).
 */
object TrafficReminder {
    private const val NOTIFICATION_ID = 4
    private const val PREFS = "reminders"
    private const val KEY_SENT = "traffic_sent"
    private const val LOW_PERCENT = 90

    fun check(context: Context) {
        if (!SettingsStore.settings.value.expiryReminders) return
        val sub = ProfileStore.profiles.value.subscription ?: return
        if (sub.total <= 0) return
        val used = sub.upload + sub.download
        val percent = (used * 100 / sub.total).toInt()
        val out = used >= sub.total
        if (!out && percent < LOW_PERCENT) return

        val key = "${sub.url.hashCode()}:${sub.expire}:${sub.total}:${if (out) 100 else LOW_PERCENT}"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_SENT, null) == key) return
        if (!ExpiryReminder.canNotify(context)) return

        val channel = ExpiryReminder.channel(context)
        val openApp = PendingIntent.getActivity(
            context,
            12,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(context.getString(if (out) R.string.traffic_out_title else R.string.traffic_low_title))
            .setContentText(
                if (out) context.getString(R.string.traffic_out_text)
                else context.getString(R.string.traffic_low_text, percent.coerceAtMost(99)),
            )
            .also { b ->
                val text = if (out) context.getString(R.string.traffic_out_text)
                else context.getString(R.string.traffic_low_text, percent.coerceAtMost(99))
                b.setStyle(NotificationCompat.BigTextStyle().bigText(text))
            }
            .setContentIntent(openApp)
            .setAutoCancel(true)
        val renewUrl = Purchase.renewUrl(context, sub)
        if (renewUrl != null) {
            val renew = PendingIntent.getActivity(
                context,
                13,
                Intent(Intent.ACTION_VIEW, Uri.parse(renewUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(R.drawable.ic_tile, context.getString(R.string.action_renew), renew)
        }
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        prefs.edit().putString(KEY_SENT, key).apply()
    }
}
