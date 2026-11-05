package app.iykyk.client.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.iykyk.client.vpn.VpnStatus
import kotlinx.coroutines.launch
import java.util.Base64

// A dot-matrix world map behind the connect button. It glides to the country
// of the selected server (pulling out and zooming back in on the region), and
// waves spread from the button while connecting (fast) and connected (slow).
// Land mask: 1° cells, 82°N to 58°S, from the global-land-mask data set,
// packed as bits (1 = land).
private const val MASK =
    "AAAAAAAAAAAAAAN/////////////AAAAAAAAAAAfAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAA/////////////8AAAAAf0cAB7/+AAAAPwAAAAAAAAAAAAAAAAAAAAAAAAAON///9g////////gAAAB//4AAAAAAAAAAP9gAAAAAAAAAAAAAAAAAAAAAAAAH/v//9////////+AAAAD/+AAAAAAAAAAAAP6AAAAAAAAAAAAAAAAAAAAAA98NgjP/g/////////gAAAAPngAAAAAAAAAAAB8AAAAAAAAAAAAAAAAAAAAAH9zH////gP////////AAAAACAAAAAAAf4AAAD///AAAAAAAAAAAAAAAAAAAAAf/////+AAAf//////AAAAAAAAAAAAH+AAAD////AAAB/vAAAAAAAAAAAAAAf48AA7/8AAAH/////8AAAAAAAAAAAAfgAAAf///+AAAA4DAAAAAAAAAAAAAAf/A8e/f7wAAH/////8AAAAAAAAAAAB8ADAP/////+H4AHAAAAAAAAAAAAAAA///5/8//8AAD/////wAAAAAAAAAAADwAPt3///////8Af/AAAAA4AABAAAAAff/8P4///gAD/////4AAAAAAAAAAAHwAf/////////8///wAAABAAA//4AACAP//h+///+AB/////4AAAAAAT/gAAB9A/v/////////////8AACAAD////P/////n/j9//AB/////gAAAAAB//+AA4B/f//////////////////4Af/////////8n/34f/AA////8AAAAAAX///4O/f//v/////////////////+AH/////////////4//oA///8AAAAAAAP////f////v//////////////////8N/////////////wH/+A///wAQYAAAAf////////////////////////////8//////////////g//8Af/+AA/+AAAA///+j///////////////////////Dwf/////////////4//gAP/wAA/8AAAD/5//////////////////////////AHH////////////H8h/wAP/wAAHwAAAP////////////////////////////AAP///////////8Ay+fgAD/AAAAADAB//n//////////////////////////AAP///////////4AG/ggAD/AAAAAAAB//H///////////////////////v/AAA////////////wAA/4QAA+AAAAAADB/////////////////////////C/8AAAW/7oH///////wAA/44AAAAAAAAAAB//h/////////////////////+DwAAAAAv+AA///////4AB//8AAAAAAAAB4B9/D///////////////////8AAPwAAAEAH8AAf///////AAf/+AAAAAAAAB8AH+n///////////////////4AAfwAAAAAfAAAP///////4Df/+AAAAAAAAA8AO+H///////////////////gAB/wAAAABwAAAB////////g///gAAAAAAADeAP4H//////////////////+AAB/CAAAAGIAAABf///////z///4AAAAAAAPvAP3////////////////////iAB/AAAAAQAAAAB////////x///+AAAAAAAPfh///////////////////////AA8AAABAAAAAABn///////9///+AAAAAAAffz///////////////////////AA4ACAYAAAAAAAD///////////+AAAAAAAMf3///////////////////////AAwAAAAAAAAAAAH///////////sAAAAAAAA////////////////////////7AAAAAAAAAAAAAAB/////////++PgAAAAAAAH///////////////////////7gAAAAAAAAAAAAAAf/////////wfgAAAAAAAf///////////////////////6AAAAAAAAAAAAAAAf/////////w/wAAAAAAAf///////////////////////yAAAAAAAAAAAAAAAP/////////3AgAAAAAAAD//////v////////////////jAQAAAAAAAAAAAAAP//////////AAAAAAAAAB/////H/////////////////GHAAAAAAAAAAAAAAP////////+8AAAAAAAAAD//n//Gf///////////////+HQAAAAAAAAAAAAAAf////////4gAAAAAAAAH//z3/+AH///////////////8H4AAAAAAAAAAAAAAf////////wAAAAAAAAAH/4P5/8AB///////////////QPAAAAAAAAAAAAAAAf////////wAAAAAAAAAH/4M+f/fh//////////////8AIAAAAAAAAAAAAAAAf////////AAAAAAAAAAH/gMP/////////////////v8AMAAAAAAAAAAAAAAAP///////8AAAAAAAAAAP/IMGPf///////////////fwAMAAAAAAAAAAAAAAAP///////4AAAAAAAAAAP/AAGPv//////////////8R4AcAAAAAAAAAAAAAAAH///////wAAAAAAACAAH/AG8OP//////////////+48C4AAAAAAAAAAAAAAAD///////wAAAAAAAAAAB8/+ICX///////////////w8T4AAAAAAAAAAAAAAAD///////4AAAAAAAAAAA7/+AA8E//////////////A8/4AAAAAAAAAAAAAAAB///////wAAAAAAAAAAB//+AAAN//////////////g1/gAAAAAAAAAAAAAAAAf//////AAAAAAAAAAAH///AAAB//////////////gH8AAAAAAAAAAAAAAAAAP/////+AAAAAAAAAAgP///8PAB//////////////wHgAAAAAAAAAAAAAAAAAH/////4AAAAAAAAAAAP///+P87//////////////wDAAAAAAAAAAAAAAAAAADf////4AAAAAAAAAAAP/////////////////////wAAAAAAAAAAAAAAAAAAAD////j4AAAAAAAAAAAf/////////v///////////wAAAAAAAAAAAAAAAAAAAD///gAcAAAAAAAAAA0//////////n///////////wAAAAAAAAAAAAAAAAAAAB3/+AAcAAAAAAAAAAD//////////z///////////gAAAAAAAAAAAAAAAAAAAA7/+AAcgAAAAAAAAAH///////+//5///////////AAAAAAAAAAAAAAAAAAAAAL/+AANAAAAAAAAAAH///////+//8f//////////AAAAAAAAAAAAAAAAAAAAAc/+AABwAAAAAAAAAP////////f/94Af///////+xAAAAAAAAAAAAAAAAAAAAGf+AAEAAAAAAAAAAP////////P//+AP///////8wAAAAAAAAAAAAAAAAAAAAAP+AB/gAAAAAAAAAf////////n///AH///////wgAAAAAAAAAAAASAAAAAAAAP+A4zwgAAAAAAAAf////////3///AH///v//8AAAAAAAAAAAAAABgAAAAAAAP/B4A8AAAAAAAAAf////////3//+AD//4P/+YAAAAAAAAAAAAAAAgAAAAAAAP/B4AD4AAAAAAAAf////////z//8AA//wH/84AAAAAAAAAAAAAAAAAAAAAAAD//4Az9gAAAAAAAP////////5//4AAf/gD/+4A4AAAAAAAAAAAAAAAAAAAAAA//wAgAAAAAAAAAf////////4//wAAf/AD/+AA4AAAAAAAAAAAAAAAAAAAAAAP/wAAAEAAAAAAQf////////9//AAAf+AD//AB4AAAAAAAAAAAAAAAAAAAAAABP/gAAAAAAAAAgf////////8/8AAAf4ADf/gAwAAAAAAAAAAAAAAAAAAAAAAAH/gAACAAAAAAA//////////fwAAAPwAAP/gA4AAAAAAAAAAAAAAAAAAAAAAAB/gAACAAAAAAAf//////////AAAAP4AAP/wA+AAAAAAAAAAAAAAAAAAAAAAAAPgAgAAAAAAAAf/////////4CAAAH4AIN/wA3AAAAAAAAAAAAAAAAAAAAAAAAHgH8BAAAAAAAP/////////w8AAAHwAAMfgALAAAAAAAAAAAAAAAAAAAAAAAADgP/+AAAAAAAH//////////4AAADwAAMfABOAAAAAAAAAAAAAAAAAAAAAAAADzP//AAAAAAAD//////////4AAADoAIMGACPAAAAAAAAAAAAAAAAAAAAAAAAA////gAAAAAAD//////////4AAADMAAOMAEHgAAAAAAAAAAAAAAAAAAAAAAAAN///wAAAAAAB//////////wAAAAcAAGAAEPgAAAAAAAAAAAAAAAAAAAAAAAAA///4AAAAAAA//////////wAAAAMAAHAAMDgAAAAIAAAAAAAAAAAAAAAAAAAA////wAAAAAAP/h///////gAAAAAABjwAfxAAAAAAAAAAAAAAAAAAAAAAAAAA////4AAAAAAHAB///////AAAAAAAB7wA9AgAAAAAAAAAAAAAAAAAAAAAAAAA////4AAAAAAAAAH//////AAAAAAAA9yh8AgAAAAAAAAAAAAAAAAAAAAAAAAB////8AAAAAAAAAH/////+AAAAAAABf6H8AIAAAAAAAAAAAAAAAAAAAAAAAAB////8AAAAAAAAAH/////4AAAAAAAAPwf+SYAAAAAAAAAAAAAAAAAAAAAAAAD////+AAAAAAAAAH/////wAAAAAAAAX4f9+QAAAAAAAAAAAAAAAAAAAAAADAH/////gAAAAAAAAP/////gAAAAAAAAH4f9MT0AAAAAAAAAAAAAAAAAAAAAAAH/////8AAAAAAAAH/////AAAAAAAAAL8P5/H/gAAAAAAAAAAAAAAAAAAAAAAH//////gAAAAAAAH////+AAAAAAAAAD/v7wA78EgAAAAAAAAAAAAAAAAAAAAH//////8AAAAAAAD////8AAAAAAAAAB8H54u//gQAAAAAAAAAAAAAAAAAAAAP//////+AAAAAAAB////8AAAAAAAAAA8AR8AP/4YAAAAAAAAAAAAAAAAAAAAP///////gAAAAAAA////8AAAAAAAAAAMABoAJ/7yAAAAAAAAAAAAAAAAAAAAH///////gAAAAAAA////8AAAAAAAAAAHsAgAI/9hgAAAAAAAAAAAAAAAAAAAD///////gAAAAAAAf///8AAAAAAAAAAD/wAhA/8AYAAAAAAAAAAAAAAAAAAAB///////gAAAAAAAf///8AAAAAAAAAAAP+/0BvOAeAAAAAAAAAAAAAAAAAAAB///////gAAAAAAAf///8AAAAAAAAAAAABnAAGHoHAAAAAAAAAAAAAAAAAAAA///////AAAAAAAAf///+AAAAAAAAAAAAAsAACDgAkAAAAAAAAAAAAAAAAAAA//////+AAAAAAAAf///+AAAAAAAAAAAAAACwCAAAAAAAAAAAAAAAAAAAAAAAf/////+AAAAAAAAf///+EQAAAAAAAAAAAAD+HAAAAAAAAAAAAAAAAAAAAAAAf/////4AAAAAAAA////+AwAAAAAAAAAAAAn+HAAAAAAAAAAAAIAAAAAAAAAAf/////4AAAAAAAA////+B4AAAAAAAAAAAB3+HgAAAAAAAAAAAAAAAAAAAAAAP/////4AAAAAAAB////+D4AAAAAAAAAAAD/+HwAACgAAAAAAAAAAAAAAAAAAD/////4AAAAAAAB////8PwAAAAAAAAAAAP//nwAAAgCAAAAAgAAAAAAAAAAAA/////4AAAAAAAB////4PwAAAAAAAAAAAP///wAAAAGQAAAAAAAAAAAAAAAAAf////4AAAAAAAA////gPwAAAAAAAAAAAf///4AAAAGAAAAAAAAAAAAAAAAAAf////4AAAAAAAA////APgAAAAAAAAAAA////8AAAAAAAAAAAAAAAAAAAAAAAf////wAAAAAAAAf//+APgAAAAAAAAAAP////+AANQACAAIAAAAAAAAAAAAAAP////gAAAAAAAAf///AfgQAAAAAAAAA//////AAGAAAAAAAAAAAAAAAAAAAAf////gAAAAAAAAP///AfAAAAAAAAAAB//////gACAAAAAAAAAAAAAAAAAAAAf///8AAAAAAAAAP///AfAAAAAAAAAAB//////gAAAAAAAAAAAAAAAAAAAAAAf///gAAAAAAAAAP///AfAAAAAAAAAAB//////8AAAAAAAAAAAAAAAAAAAAAAf///AAAAAAAAAAH//8AOAAAAAAAAAAA//////8AAAAAAAAAAAAAAAAAAAAAAf///AAAAAAAAAAH//4AAAAAAAAAAAAB//////4AAAAAAAAAAAAAAAAAAAAAAf///AAAAAAAAAAH//4AAAAAAAAAAAAA//////8AAAAAAAAAAAAAAAAAAAAAA////AAAAAAAAAAD//4AAAAAAAAAAAAA//////8AAAAAAAAAAAAAAAAAAAAAA///+AAAAAAAAAAB//wAAAAAAAAAAAAAf/////8AAAAAAAAAAAAAAAAAAAAAA///8AAAAAAAAAAB//gAAAAAAAAAAAAAf/////4AAAAAAAAAAAAAAAAAAAAAA///4AAAAAAAAAAB//AAAAAAAAAAAAAAf/////4AAAAAAAAAAAAAAAAAAAAAA///wAAAAAAAAAAA/+AAAAAAAAAAAAAAf/wf//4AAAAAAAAAAAAAAAAAAAAAA///wAAAAAAAAAAA/8AAAAAAAAAAAAAAf8AP//wAAAAAAAAAAAAAAAAAAAAAA///gAAAAAAAAAAAcgAAAAAAAAAAAAAAfAAH//gAAAAAAAAAAAAAAAAAAAAAB//4AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD//gAABgAAAAAAAAAAAAAAAAAB//8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAf/AAAAgAAAAAAAAAAAAAAAAAD//4AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAf/AAAA+AAAAAAAAAAAAAAAAAD//4AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAH8AAAA+AAAAAAAAAAAAAAAAAD/8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB4AAAAAAAAAAAAAAAAAD/8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAiAAACYAAAAAAAAAAAAAAAAAD/gAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA+AAAHwAAAAAAAAAAAAAAAAAD/4AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAcAAAHAEAAAAAAAAAAAAAAAAH/gAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAcAAAeAAAAAAAAAAAAAAAAAAH/gAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB8AAAAAAAAAAAAAAAAAAH/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD4AAAAAAAAAAAAAAAAAAH+AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADwAAAAAAAAAAAAAAAAAAP/gAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAP/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAH+AAAAAAAAAAAAAAAAAAAAAAOAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAH8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAAAAAAAAAAAAAAAAAAH4BwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAH8AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD+AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/wAAADgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAOAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
private const val COLS = 360
private const val ROWS = 140
private const val STEP = 1f
private const val NORTH = 82f

/** Land cells as map coordinates (1 unit = one 1° cell). */
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

/** Degrees of longitude across the screen: close on the region, wide in flight. */
private const val CLOSE_SPAN = 44f
private const val WIDE_SPAN = 120f

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
    val x = remember { Animatable(target.x) }
    val y = remember { Animatable(target.y) }
    val span = remember { Animatable(CLOSE_SPAN) }
    // Fly to the new country like a map app: pull out, glide over, zoom into the region.
    LaunchedEffect(target) {
        if (x.value == target.x && y.value == target.y) return@LaunchedEffect
        val distance = kotlin.math.hypot(x.value - target.x, y.value - target.y)
        val wide = (CLOSE_SPAN + distance * 1.4f).coerceAtMost(WIDE_SPAN)
        launch { x.animateTo(target.x, tween(1600, easing = FastOutSlowInEasing)) }
        launch { y.animateTo(target.y, tween(1600, easing = FastOutSlowInEasing)) }
        span.animateTo(wide, tween(650, easing = FastOutSlowInEasing))
        span.animateTo(CLOSE_SPAN, tween(950, easing = FastOutSlowInEasing))
    }
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
    val dotColor = IykykColors.Muted.copy(alpha = 0.2f)
    val accent = IykykColors.Accent
    val points = landPoints

    Canvas(modifier) {
        val cell = size.width / span.value
        val anchor = Offset(size.width / 2f, anchorY.toPx())
        // The world repeats left and right, so the screen is never cut at 180°.
        for (copy in -1..1) {
            val visible = when (copy) {
                -1 -> x.value < span.value / 2f
                1 -> x.value > COLS - span.value / 2f
                else -> true
            }
            if (!visible) continue
            withTransform({
                translate(anchor.x - (x.value - copy * COLS) * cell, anchor.y - y.value * cell)
                scale(cell, cell, pivot = Offset.Zero)
            }) {
                drawPoints(points, PointMode.Points, dotColor, strokeWidth = 0.42f, cap = StrokeCap.Round)
            }
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
