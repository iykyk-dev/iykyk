package app.iykyk.client.core

import android.content.Context

/**
 * "Банки и Госуслуги напрямую": apps that often refuse to work from a foreign IP.
 * Only the installed ones are added to the exceptions.
 */
object BankPreset {
    val packages = listOf(
        // Banks and payments
        "ru.sberbankmobile",
        "com.idamob.tinkoff.android",
        "ru.vtb24.mobilebanking.android",
        "ru.alfabank.mobile.android",
        "ru.gazprombank.android.mobilebank.app",
        "ru.raiffeisennews",
        "ru.rosbank.android",
        "com.openbank",
        "ru.mts.money",
        "ru.psbank.mobile",
        "ru.sovcombank.halvacard",
        "ru.ozon.fintech.finance",
        "ru.nspk.mirpay",
        "ru.nspk.sbpay",
        "ru.yoo.money",
        "ru.yandex.money",
        // Government services
        "ru.rostel",
        "ru.gosuslugi.goskey",
        "ru.fns.lkfl",
        "ru.mos.app",
        // Marketplaces
        "ru.ozon.app.android",
        "com.wildberries.ru",
        "ru.beru.android",
        "com.avito.android",
    )

    /** Package name to label for the preset apps installed on this device. */
    fun installed(context: Context): Map<String, String> {
        val pm = context.packageManager
        return packages.mapNotNull { pkg ->
            runCatching { pkg to pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrNull()
        }.toMap()
    }
}
