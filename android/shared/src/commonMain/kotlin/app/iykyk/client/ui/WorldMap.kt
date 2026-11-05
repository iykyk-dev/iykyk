package app.iykyk.client.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.iykyk.client.vpn.VpnStatus
import java.util.Base64

// A dot-matrix world map behind the connect button. It glides to the country
// of the selected server, and waves spread from the button while connecting
// (fast) and connected (slow). Land mask: 2° cells, 82°N to 58°S, from the
// global-land-mask data set, packed as bits (1 = land).
private const val MASK =
    "AAAAAAA///////AAFAAjQADgAAAAAAAAAAAHd//////4AB/gAAAAD+AAAAAAAAAAB/e/4f///4AAbAAAOAAf4AAAAAAAAAD//pwA///4AAAAAHwAf/4APgAAAAAAH/3/+Af//wAAAAAODr///+HwAAAH8AL/73/wP//gAAA8AcH///////wAg/////59vwP//AAAf/if//////////+f//////3/H/wkAA//////////////+///////l+H9A+AB+/////////////Cf/////x98D8AIAP//////////////Af/////gHgB4AAAP9///////////v4AH4P///wH+AAAAOP9//////////+cAADwD///8P+AAAAOD//////////8A8AAEAB////v/gAAAfDv/////////4A4AAAAB//////wAAAbv///////////AwIAAAAP/////wAAAH////////////AgAAAAAP////+8AAAH////////////AAAAAAAD////+YAAAD///////////9AAAAAAAD/////AAAAB//7////////5YAAAAAAH////kAAAAf9v4P///////zgAAAAAAD////gAAAAfy/////////+CAAAAAAAD///+AAAAAfSLf///////+GAAAAAAAD///8AAAAAf/zf///////mOAAAAAAAB///8AAAAAP/BB///////H+AAAAAAAAf//4AAAAAf/zB///////hwAAAAAAAAf//wAAAAAf//////////hAAAAAAAAAP/4wAAAAE///////////iAAAAAAAAAP/AwAAAAB/////v/////gAAAAAAAAAD/AQAAAAD/////3/////gAAAAAAAAAD+B4AAAAH//////D////gAAAAAAAAAAfOOAAAAH//////B/7/QAAAAAAAgAAAf8HoAAAD////v+A/j+wAAAAAAAAAAAH+AAAAAH////38A/B/AgAAAAAAAAAAAfgAAAAH/////wAcAfggAAAAAAAAAAAHggAAAH/////IAcAfgYAAAAAAAAAAABj6AAAD/////wAcAXgYAAAAAAAAAAAB//AAAB/////wAOATBYAAAAAAAAAAAAX/gAAB/////gACAYCcAAAAAAAAAAAAH/8AAAfP///gAABsHAAAAAAAAAAAAAP/+AAAAB///AAAA8eCAAAAAAAAAAAAP/+AAAAD//+AAAAc+WAAAAAAAAAAAIf//wAAAD//8AAAAe/koAAAAAAAAAAAf//+AAAB//4AAAAOfnfAAAAAAAAAAAf///gAAA//wAAAAGBwfsAAAAAAAAAAf///gAAA//wAAAAB4FXxQAAAAAAAAAP///gAAA//wAAAAAP4PY4AAAAAAAAAH///AAAA//4AAAAAABBCIAEAAAAAAAH//+AAAA//4gAAAAAB7AAAIAAAAAAAD//+AAAA//5wAAAAAPzgAAAAEAAAAAB//+AAAA//zgAAAAAf/gBjAAAAAAAAAf/8AAAA//jgAAAAA//wEAAAAAAAAAAf/8AAAA//HiAAAAH//4CAAAAAAAAAA//4AAAAf/HAAAAAH//8AAAAAAAAAAA//gAAAAf/DAAAAAP//+AAAAAAAAAAA//AAAAAf+AAAAAAH//+AAAAAAAAAAA//AAAAAP+AAAAAAH//+AAAAAAAAAAA/+AAAAAP8AAAAAAH//+AAAAAAAAAAA/8AAAAAH4AAAAAAH4f+AAAAAAAAAAB/4AAAAAGAAAAAAAHAf8AMAAAAAAAAB/wAAAAAAAAAAAAAAAD4AEAAAAAAAAB/gAAAAAAAAAAAAAAABwAGAAAAAAAAB+AAAAAAAAAAAAAAAAA4AOAAAAAAAAB8AAAAAAAAAAAAAAAAAwA4AAAAAAAAD8AAAAAAAAAAAAAAAAAABwAAAAAAAAD4AAAAAAAAAAAAAAAAAAAgAAAAAAAAD4AAAAAAAAAACAAAAAAAAAAAAAAAAABwgAAAAAAAAAAAAAAAAAAAAAAAAAAABwAAAAAAAAAAAAAAAAAAAAAAAAAAAAA4ABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
private const val COLS = 180
private const val ROWS = 70
private const val STEP = 2f
private const val NORTH = 82f

/** Land cells as map coordinates (1 unit = one 2° cell). */
private val landPoints: List<Offset> by lazy {
    val bytes = Base64.getDecoder().decode(MASK)
    buildList {
        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                val i = row * COLS + col
                if ((bytes[i / 8].toInt() shr (7 - i % 8)) and 1 == 1) add(Offset(col + 0.5f, row + 0.5f))
            }
        }
    }
}

private fun toMap(lat: Float, lon: Float) = Offset((lon + 180f) / STEP, (NORTH - lat) / STEP)

/** Where Europe is: the default when the server's country is unknown. */
private val DEFAULT_LOCATION = 50f to 15f

@Composable
fun WorldMapBackground(
    serverName: String?,
    status: VpnStatus,
    /** Distance from the top of this area to the centre of the connect button. */
    anchorY: Dp,
    modifier: Modifier = Modifier,
) {
    val (lat, lon) = remember(serverName) { serverName?.let(::serverLocation) ?: DEFAULT_LOCATION }
    val target = toMap(lat, lon)
    val x by animateFloatAsState(target.x, tween(1400, easing = FastOutSlowInEasing), label = "mapX")
    val y by animateFloatAsState(target.y, tween(1400, easing = FastOutSlowInEasing), label = "mapY")
    val waves = rememberInfiniteTransition(label = "waves")
    val phase by waves.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(if (status == VpnStatus.CONNECTING) 1200 else 3200, easing = LinearEasing),
            RepeatMode.Restart,
        ),
        label = "phase",
    )
    val dotColor = IykykColors.Muted.copy(alpha = 0.22f)
    val accent = IykykColors.Accent
    val points = landPoints

    Canvas(modifier) {
        // About 55 cells (110° of longitude) across the screen.
        val cell = size.width / 55f
        val anchor = Offset(size.width / 2f, anchorY.toPx())
        withTransform({
            translate(anchor.x - x * cell, anchor.y - y * cell)
            scale(cell, cell, pivot = Offset.Zero)
        }) {
            drawPoints(points, PointMode.Points, dotColor, strokeWidth = 0.46f, cap = StrokeCap.Round)
        }
        if (status == VpnStatus.CONNECTED || status == VpnStatus.CONNECTING) {
            // Waves from behind the button (radius 80 dp) outwards.
            val start = 80.dp.toPx()
            val reach = size.width * 0.62f
            for (k in 0 until 3) {
                val t = (phase + k / 3f) % 1f
                val radius = start + (reach - start) * t
                drawCircle(
                    color = accent.copy(alpha = 0.28f * (1f - t)),
                    radius = radius,
                    center = anchor,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
    }
}

/** Latitude and longitude for a server, from its flag emoji or a city/country word in its name. */
fun serverLocation(name: String): Pair<Float, Float>? {
    val text = name.trim()
    if (text.length >= 4) {
        val a = text.codePointAt(0)
        val b = text.codePointAt(2)
        if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) {
            val code = "" + ('A' + (a - 0x1F1E6)) + ('A' + (b - 0x1F1E6))
            COUNTRIES[code]?.let { return it }
        }
    }
    val lower = text.lowercase()
    return NAMES.entries.firstOrNull { (word, _) -> word in lower }?.let { COUNTRIES[it.value] }
}

private val COUNTRIES = mapOf(
    "EU" to (50.5f to 10f), "NL" to (52.2f to 5.3f), "DE" to (50.5f to 9.5f), "FI" to (61.9f to 25.7f),
    "SE" to (60.1f to 15.6f), "NO" to (60.5f to 8.5f), "DK" to (56.0f to 10.0f), "PL" to (52.0f to 19.4f),
    "RU" to (55.75f to 37.6f), "GB" to (52.5f to -1.5f), "FR" to (46.6f to 2.4f), "ES" to (40.2f to -3.7f),
    "IT" to (42.8f to 12.5f), "CH" to (46.8f to 8.2f), "AT" to (47.6f to 14.1f), "CZ" to (49.8f to 15.5f),
    "EE" to (58.7f to 25.0f), "LV" to (56.9f to 24.6f), "LT" to (55.2f to 23.9f), "BE" to (50.6f to 4.6f),
    "LU" to (49.8f to 6.1f), "IE" to (53.2f to -8.0f), "PT" to (39.6f to -8.0f), "RO" to (45.9f to 25.0f),
    "BG" to (42.7f to 25.3f), "HU" to (47.2f to 19.5f), "MD" to (47.2f to 28.5f), "UA" to (49.0f to 31.4f),
    "BY" to (53.7f to 28.0f), "TR" to (39.0f to 35.2f), "GE" to (42.3f to 43.4f), "AM" to (40.1f to 45.0f),
    "KZ" to (48.0f to 67.0f), "IL" to (31.5f to 34.9f), "AE" to (24.3f to 54.4f), "US" to (39.8f to -98.6f),
    "CA" to (56.1f to -106.3f), "JP" to (36.2f to 138.3f), "SG" to (1.35f to 103.8f), "HK" to (22.3f to 114.2f),
    "IS" to (64.9f to -18.6f), "RS" to (44.0f to 20.9f), "GR" to (39.1f to 22.0f), "CY" to (35.1f to 33.4f),
)

private val NAMES = mapOf(
    "frankfurt" to "DE", "germany" to "DE", "герман" to "DE", "netherlands" to "NL", "amsterdam" to "NL",
    "нидерланд" to "NL", "finland" to "FI", "helsinki" to "FI", "финлянд" to "FI", "stockholm" to "SE",
    "sweden" to "SE", "швец" to "SE", "poland" to "PL", "warsaw" to "PL", "польш" to "PL", "росси" to "RU",
    "russia" to "RU", "moscow" to "RU", "европ" to "EU", "europe" to "EU", "london" to "GB", "uk" to "GB",
    "paris" to "FR", "france" to "FR", "turkey" to "TR", "istanbul" to "TR", "usa" to "US", "kazakh" to "KZ",
)
