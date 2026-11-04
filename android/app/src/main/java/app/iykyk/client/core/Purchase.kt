package app.iykyk.client.core

import android.content.Context
import android.telephony.TelephonyManager
import app.iykyk.client.BuildConfig
import java.util.Locale

/**
 * Buying and renewing happen in the Telegram bot, never inside the app, so
 * there is no store commission. Whether the app may link to the bot depends on
 * where it came from:
 * - the APK from the project page: always;
 * - Google Play: only for users in Russia and Belarus, where Google exempts
 *   developers from Play billing (Play billing is paused there since 2022);
 * - App Store: never (outside the US, Apple forbids links to other payment methods).
 */
object Purchase {
    val botUrl: String get() = BuildConfig.BOT_URL

    fun allowed(context: Context): Boolean = when (BuildConfig.DISTRIBUTION) {
        "site" -> true
        "play" -> country(context) in setOf("ru", "by")
        else -> false
    }

    /** Where "Renew" leads, or null if this build must not show it. */
    fun renewUrl(context: Context, sub: SubscriptionInfo?): String? =
        if (allowed(context)) botUrl else null

    private fun country(context: Context): String {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        return listOfNotNull(telephony?.simCountryIso, telephony?.networkCountryIso, Locale.getDefault().country)
            .map { it.lowercase(Locale.ROOT) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }
}
