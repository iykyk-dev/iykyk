package app.iykyk.client.core

/** Public source of every release (GPL): also where updates are published. */
const val SOURCE_URL = "https://github.com/iykyk-dev/iykyk"

/** Where the app reads the newest release description (see .github/workflows/build.yml). */
const val UPDATE_MANIFEST_URL = "https://github.com/iykyk-dev/iykyk/releases/latest/download/version.json"

/** What the update banner and "About" show. */
sealed interface UpdateState {
    /** Not checked yet, or this build does not update itself (debug, store builds). */
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val version: String, val notes: String) : UpdateState
    /** [progress] is 0..1, or null while the size is unknown / the installer is opening. */
    data class Downloading(val version: String, val progress: Float?) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** About this copy of the app, for Settings. */
data class AboutInfo(
    val version: String,
    /** SHA-256 of the signing certificate, "AB:CD:…", to compare with the site. */
    val signature: String,
)
