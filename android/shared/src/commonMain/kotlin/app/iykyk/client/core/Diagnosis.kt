package app.iykyk.client.core

/** The checks of "Не работает?", in the order they run. */
enum class DiagCheck { INTERNET, SUBSCRIPTION, SERVERS, TUNNEL }

enum class DiagState { WAITING, RUNNING, OK, WARN, FAIL, SKIPPED }

/** What the helper proposes after the checks. */
enum class DiagFix {
    /** Everything works, or nothing the app can change. */
    NONE,
    NO_INTERNET,
    DEVICE_LIMIT,
    RENEW,
    SWITCH_SERVER,
    /** No server answers from this network. */
    SERVERS_DOWN,
    ENABLE_FRAGMENT,
    SWITCH_ENGINE,
    CONNECT,
    RECONNECT,
}

data class Diagnosis(
    val states: Map<DiagCheck, DiagState> = DiagCheck.entries.associateWith { DiagState.WAITING },
    /** A short note per check ("3 из 6 серверов отвечают"). */
    val details: Map<DiagCheck, String> = emptyMap(),
    val done: Boolean = false,
    val fix: DiagFix = DiagFix.NONE,
    /** For [DiagFix.SWITCH_SERVER]: the server to switch to. */
    val serverIndex: Int = -1,
    val serverName: String = "",
) {
    fun with(check: DiagCheck, state: DiagState, detail: String? = null) = copy(
        states = states + (check to state),
        details = if (detail == null) details else details + (check to detail),
    )
}
