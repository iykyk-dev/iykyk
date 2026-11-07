package app.iykyk.client.core

import android.content.Context

/** Android entry to [DevicesApi] with this install's device ID. */
object Devices {
    suspend fun load(context: Context, sub: SubscriptionInfo): DevicesInfo? =
        DevicesApi.load(sub, ProfileStore.deviceId(context))

    suspend fun rename(context: Context, sub: SubscriptionInfo, id: String, name: String): DevicesInfo? =
        DevicesApi.rename(sub, ProfileStore.deviceId(context), id, name)

    suspend fun delete(context: Context, sub: SubscriptionInfo, id: String): DevicesInfo? =
        DevicesApi.delete(sub, ProfileStore.deviceId(context), id)
}
