package app.iykyk.client.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import app.iykyk.client.R
import app.iykyk.client.core.AppLog

/** Result of an installer session started by [Updater]. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The system's "Update this app?" screen.
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    Updater.failed(context.getString(R.string.update_bad_file))
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { Updater.failed(it.message ?: it.javaClass.simpleName) }
            }
            PackageInstaller.STATUS_SUCCESS -> AppLog.i("update: installed")
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                AppLog.i("update: installer status $status: $message")
                Updater.failed(
                    if (status == PackageInstaller.STATUS_FAILURE_ABORTED) context.getString(R.string.update_cancelled)
                    else message ?: context.getString(R.string.update_bad_file),
                )
            }
        }
    }
}
