package com.example.hideseek.ui.screens

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hideseek.engine.RULES
import com.example.hideseek.engine.Timings
import com.example.hideseek.ui.Body
import com.example.hideseek.ui.HsButton
import com.example.hideseek.ui.HsCard
import com.example.hideseek.ui.HsPage
import com.example.hideseek.ui.Label
import com.example.hideseek.ui.Variant
import com.example.hideseek.ui.theme.Hs
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val T = Timings.NORMAL
private val HUNT_MIN = T.gameSec / 60
private val PULSE_MIN = T.radarPulseEverySec / 60
private val FINAL_MIN = T.finalHuntSec / 60

private class Slide(
    val title: String,
    val text: String,
    val accent: Color,
    val art: @Composable (animate: Boolean) -> Unit,
)

private val SLIDES = listOf(
    Slide(
        "No internet needed",
        "Phones talk directly over Bluetooth and Wi-Fi. One phone hosts; everyone else stays within about 30 m while joining. Keep Bluetooth, Wi-Fi and Location on.",
        Hs.Police,
    ) { ConnectArt(it) },
    Slide(
        "Make a room",
        "One player taps Create room and their phone becomes the host. Free game or a test-money fee (₹${RULES.MIN_FEE_RS}–₹${RULES.MAX_FEE_RS}). Friends tap the room under \"Join a game nearby\" or type the 6-letter code. ${RULES.MIN_PLAYERS}–${RULES.MAX_PLAYERS} players.",
        Hs.Primary,
    ) { RoomArt() },
    Slide(
        "Get ready",
        "Everyone taps \"I'm in\". Entry fees are taken only when the host starts — from everyone at once, or from nobody.",
        Hs.Green,
    ) { ReadyArt() },
    Slide(
        "Shake to flip",
        "Shake your phone to flip your card. One player becomes SIPAHI (police); everyone else is CHOR (thief). Only you see your card until everyone has flipped.",
        Hs.CardBack,
    ) { FlipArt(it) },
    Slide(
        "Hide",
        "Thieves get a ${T.hideSec}-second head start while the police screen is blacked out. Stay within ${RULES.BOUNDARY_RADIUS_M} m of the police phone the whole game.",
        Hs.Thief,
    ) { HideArt(it) },
    Slide(
        "The hunt · $HUNT_MIN min",
        "The police phone vibrates when a thief is within 1 m; a thief sees a green dot when police is that close. Every $PULSE_MIN min the police gets a radar pulse. In the last $FINAL_MIN min the radar widens to ${RULES.FINAL_HUNT_CLOSE_RADIUS_M.toInt()} m.",
        Hs.Police,
    ) { MiniMap(Modifier.fillMaxSize(), animate = it) },
    Slide(
        "Catching",
        "Police holds the CAUGHT button on the thief's phone, or scans the QR catch code on the thief's screen. Codes change every ${RULES.QR_TOKEN_ROTATE_SEC} s, so screenshots don't work.",
        Hs.Green,
    ) { CatchArt(it) },
    Slide(
        "Out of the zone",
        "Step outside ${RULES.BOUNDARY_RADIUS_M} m and you get a ${RULES.OUT_OF_RANGE_GRACE_SEC}-second warning to come back. Stay out longer, or leave more than ${RULES.MAX_OUT_OF_RANGE_STRIKES} times, and you're out.",
        Hs.Warning,
    ) { MiniMap(Modifier.fillMaxSize(), animate = it, outside = true) },
    Slide(
        "Winning",
        "Police catches every thief before time runs out → police takes the whole pot. Otherwise the thieves who were never caught split it equally.",
        Hs.Primary,
    ) { WinArt() },
)

private val FAIR = listOf(
    "Nobody can choose the police" to "The host locks in a secret seed (shown as the \"fairness seal\" in the lobby) before anyone joins, and every player adds their own. The police is calculated from all of them; the secret is revealed after the game so anyone can check.",
    "No police twice in a row" to "The same person can't be police twice in a row in a rematch.",
    "Fake GPS is caught" to "Fake-GPS apps are detected and the player is disqualified.",
    "Refund if police drops" to "If the police loses GPS or connection for ${RULES.POLICE_OFFLINE_VOID_SEC / 60} minutes, the game is cancelled and everyone is refunded. If the host leaves, the room closes, so the host keeps the app open.",
    "GPS drift is forgiven" to "Zone checks give thieves the benefit of GPS error, so nobody is punished for drift at the edge.",
    "Play money only" to "This test build uses play money kept on each phone (max ₹${RULES.DAILY_DEPOSIT_LIMIT_RS} added per day). Free games are unlimited.",
)

/** Honour the system "Remove animations" setting. */
@Composable
private fun rememberAnimate(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}

@Composable
fun HowToPlayScreen(onBack: () -> Unit) {
    val animate = rememberAnimate()
    HsPage("How to play", onBack) {
        Hero()
        StatTiles()
        Slides(animate)
        HsCard {
            Label("The hunt map")
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(12.dp)),
            ) { MiniMap(Modifier.fillMaxSize(), animate = animate, labels = true) }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Legend(Hs.Police, "Police")
                Legend(Hs.Thief, "Thief")
                Legend(Hs.Primary, "${RULES.BOUNDARY_RADIUS_M} m zone")
            }
            Body("The zone moves with the police (or stays at the start point — the host picks). Thieves must stay inside it.", dim = true, size = 13)
        }
        TimelineCard()
        ProximityCard()
        HeatCard()
        PotCard()
        HsCard {
            Label("Fair play")
            FAIR.forEachIndexed { i, (title, text) -> NumberedTile(i + 1, title, text) }
        }
        NightCard()
    }
}

// ================================================================= hero + tiles

@Composable
private fun Hero() {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(Hs.PoliceDark, Hs.CardBack, Hs.ThiefDark)))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Label("Chor Sipahi", color = Hs.Primary)
        Text(
            "Real-world hide & seek.\nOne cop. Everyone else runs.",
            color = Hs.Text, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoleTile("👮", "Sipahi", "1 player", "Hunts with radar & vibration alerts", Hs.Police)
            RoleTile("🦹", "Chor", "Everyone else", "Hides, dodges, survives the clock", Hs.Thief)
        }
    }
}

@Composable
private fun RowScope.RoleTile(emoji: String, name: String, who: String, does: String, color: Color) {
    Column(
        Modifier
            .weight(1f)
            .background(Hs.Bg.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(emoji, fontSize = 28.sp)
        Text(name.uppercase(), color = color, fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        Text(who, color = Hs.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(does, color = Hs.TextDim, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun StatTiles() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("${RULES.MIN_PLAYERS}–${RULES.MAX_PLAYERS}", "players", Hs.Primary)
            StatTile("$HUNT_MIN", "min hunt", Hs.Police)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("${T.hideSec}", "sec to hide", Hs.Thief)
            StatTile("${RULES.BOUNDARY_RADIUS_M}", "metre zone", Hs.Green)
        }
    }
}

@Composable
private fun RowScope.StatTile(value: String, unit: String, color: Color) {
    Row(
        Modifier
            .weight(1f)
            .height(72.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Hs.Surface)
            .border(1.dp, Hs.Border, RoundedCornerShape(14.dp)),
    ) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(color))
        Column(Modifier.padding(horizontal = 12.dp).align(Alignment.CenterVertically)) {
            Text(value, color = color, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text(unit, color = Hs.TextDim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ================================================================= slides

@Composable
private fun Slides(animate: Boolean) {
    val pager = rememberPagerState(pageCount = { SLIDES.size })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == SLIDES.size - 1

    HsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Step by step", modifier = Modifier.weight(1f))
            Text("${pager.currentPage + 1} / ${SLIDES.size}", color = Hs.TextDim, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalPager(state = pager, pageSpacing = 16.dp, modifier = Modifier.fillMaxWidth()) { i ->
            val slide = SLIDES[i]
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.verticalGradient(listOf(slide.accent.copy(alpha = 0.18f), Hs.Bg)))
                        .semantics { contentDescription = "Illustration: ${slide.title}" },
                    contentAlignment = Alignment.Center,
                ) { slide.art(animate) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.size(28.dp).background(slide.accent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text("${i + 1}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black) }
                    Text(slide.title, color = Hs.Text, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                }
                Body(slide.text, modifier = Modifier.heightIn(min = 110.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            repeat(SLIDES.size) { i ->
                val width by animateDpAsState(if (i == pager.currentPage) 22.dp else 8.dp, tween(250), label = "dot")
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(width = width, height = 8.dp)
                        .background(if (i == pager.currentPage) Hs.Primary else Hs.Border, CircleShape),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HsButton(
                "Back",
                onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                variant = Variant.Ghost,
                enabled = pager.currentPage > 0,
                small = true,
            )
            HsButton(
                if (last) "Start over" else "Next",
                onClick = { scope.launch { pager.animateScrollToPage(if (last) 0 else pager.currentPage + 1) } },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                small = true,
            )
        }
    }
}

// ================================================================= slide art

@Composable
private fun loop(animate: Boolean, durationMs: Int, label: String): Float {
    if (!animate) return 0.35f
    val t = rememberInfiniteTransition(label = label)
    val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(durationMs, easing = LinearEasing)), label = label)
    return v
}

@Composable
private fun ConnectArt(animate: Boolean) {
    val p = loop(animate, 1800, "waves")
    Canvas(Modifier.fillMaxSize()) {
        val phoneW = size.width * 0.16f
        val phoneH = phoneW * 1.8f
        val y = (size.height - phoneH) / 2
        val left = Offset(size.width * 0.18f, y)
        val right = Offset(size.width * 0.82f - phoneW, y)
        listOf(left to Hs.Police, right to Hs.Thief).forEach { (o, c) ->
            drawRoundRect(Hs.Surface, o, Size(phoneW, phoneH), CornerRadius(14f))
            drawRoundRect(c, o, Size(phoneW, phoneH), CornerRadius(14f), style = Stroke(4f))
            drawCircle(c, phoneW * 0.18f, Offset(o.x + phoneW / 2, o.y + phoneH / 2))
        }
        val mid = Offset(size.width / 2, size.height / 2)
        for (k in 0 until 3) {
            val f = (p + k / 3f) % 1f
            val r = size.width * (0.06f + f * 0.2f)
            drawCircle(Hs.Primary.copy(alpha = 1f - f), r, mid, style = Stroke(4f))
        }
        drawCircle(Hs.Primary, 8f, mid)
    }
}

@Composable
private fun RoomArt() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(
            Modifier.background(Hs.Surface, RoundedCornerShape(12.dp)).border(1.dp, Hs.Primary, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Label("Room code")
            Text("K7P2QX", color = Hs.Primary, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(2) { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(5) { c ->
                        val filled = r * 5 + c < 6
                        Box(
                            Modifier.size(16.dp)
                                .background(if (filled) Hs.Primary else Color.Transparent, CircleShape)
                                .border(1.5.dp, if (filled) Hs.Primary else Hs.Border, CircleShape),
                        )
                    }
                }
            }
            Text("6 of ${RULES.MAX_PLAYERS} joined", color = Hs.TextDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ReadyArt() {
    val names = listOf("Asha", "Ravi", "Meera", "Kabir")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.width(220.dp)) {
        names.forEachIndexed { i, n ->
            val ready = i < 3
            Row(
                Modifier.fillMaxWidth().background(Hs.Surface, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(n, color = Hs.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(
                    if (ready) "✓ I'm in" else "waiting…",
                    color = if (ready) Hs.Green else Hs.TextFaint, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun FlipArt(animate: Boolean) {
    val rotation = if (animate) {
        val t = rememberInfiniteTransition(label = "flip")
        val r by t.animateFloat(
            0f, 180f,
            infiniteRepeatable(
                keyframes {
                    durationMillis = 2600
                    0f at 0
                    0f at 1000
                    180f at 1600
                    180f at 2600
                },
                RepeatMode.Reverse,
            ),
            label = "flip",
        )
        r
    } else 180f
    val front = rotation > 90f
    Box(
        Modifier
            .size(width = 100.dp, height = 140.dp)
            .graphicsLayer { rotationY = rotation; cameraDistance = 12 * density }
            .background(if (front) Hs.PoliceDark else Hs.CardBack, RoundedCornerShape(12.dp))
            .border(2.dp, if (front) Hs.Police else Hs.Primary.copy(alpha = 0.6f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (front) {
            Column(Modifier.graphicsLayer { rotationY = 180f }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("👮", fontSize = 34.sp)
                Text("SIPAHI", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            }
        } else {
            Text("?", color = Hs.Primary, fontSize = 44.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun HideArt(animate: Boolean) {
    val p = loop(animate, T.hideSec * 60, "hide") // sped-up countdown
    val tm = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(size.width * 0.3f, size.height / 2)
        val r = size.height * 0.34f
        drawCircle(Hs.Border, r, c, style = Stroke(12f))
        drawArc(Hs.Thief, -90f, 360f * (1f - p), false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(12f))
        val secs = (T.hideSec * (1f - p)).toInt()
        centeredText(tm, "${secs}s", c, Hs.Text, 22)
        // Police phone blacked out, thieves scattering away from it.
        val phone = Offset(size.width * 0.72f, size.height / 2)
        drawRoundRect(Color.Black, Offset(phone.x - 26f, phone.y - 46f), Size(52f, 92f), CornerRadius(10f))
        drawRoundRect(Hs.Police, Offset(phone.x - 26f, phone.y - 46f), Size(52f, 92f), CornerRadius(10f), style = Stroke(3f))
        for (k in 0 until 4) {
            val a = (k * 90f + 45f) * (Math.PI / 180).toFloat()
            val d = 50f + p * size.height * 0.28f
            drawCircle(Hs.Thief, 9f, Offset(phone.x + cos(a) * d, phone.y + sin(a) * d * 0.7f))
        }
    }
}

@Composable
private fun CatchArt(animate: Boolean) {
    val p = loop(animate, 2400, "hold")
    val tm = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize()) {
        // Pseudo QR code.
        val cell = size.height * 0.055f
        val qr = Offset(size.width * 0.16f, (size.height - cell * 11) / 2)
        drawRect(Color.White, qr, Size(cell * 11, cell * 11))
        val bits = 0x5A3C_96E1_7B2D_4F81L
        for (y in 0 until 9) for (x in 0 until 9) {
            val finder = (x < 3 && y < 3) || (x > 5 && y < 3) || (x < 3 && y > 5)
            val on = if (finder) (x % 2 == 0 || y % 2 == 0) else (bits shr ((x * 7 + y * 3) % 63) and 1L) == 1L
            if (on) drawRect(Color.Black, Offset(qr.x + cell * (x + 1), qr.y + cell * (y + 1)), Size(cell, cell))
        }
        // Hold-to-catch button filling up.
        val c = Offset(size.width * 0.7f, size.height / 2)
        val r = size.height * 0.3f
        drawCircle(Hs.Green.copy(alpha = 0.15f), r, c)
        drawCircle(Hs.Border, r, c, style = Stroke(10f))
        drawArc(Hs.Green, -90f, 360f * p, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(10f))
        centeredText(tm, "HOLD", c, Hs.Green, 16)
    }
}

@Composable
private fun WinArt() {
    Column(Modifier.width(250.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutcomeBar("Police catches all", listOf(1f to Hs.Police))
        OutcomeBar("2 thieves escape", listOf(0.5f to Hs.Thief, 0.5f to Hs.Thief))
    }
}

@Composable
private fun OutcomeBar(title: String, parts: List<Pair<Float, Color>>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Hs.TextDim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().height(26.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            parts.forEach { (w, c) ->
                Box(Modifier.weight(w).fillMaxHeight().background(c, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                    Text("${(w * 100).toInt()}% of pot", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ================================================================= mini map

/** Top-down sketch of the play zone: police at the centre, thieves around, radar sweeping. */
@Composable
private fun MiniMap(modifier: Modifier, animate: Boolean, outside: Boolean = false, labels: Boolean = false) {
    val sweep = loop(animate, 3200, "sweep")
    val tm = rememberTextMeasurer()
    Canvas(modifier.background(Color(0xFF0E1626))) {
        // Map texture: grid + a couple of "streets".
        val step = 28.dp.toPx()
        var x = 0f
        while (x < size.width) { drawLine(Hs.Border.copy(alpha = 0.35f), Offset(x, 0f), Offset(x, size.height), 1f); x += step }
        var y = 0f
        while (y < size.height) { drawLine(Hs.Border.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), 1f); y += step }
        drawLine(Hs.SurfaceAlt, Offset(0f, size.height * 0.7f), Offset(size.width, size.height * 0.55f), 18f)
        drawLine(Hs.SurfaceAlt, Offset(size.width * 0.35f, 0f), Offset(size.width * 0.45f, size.height), 14f)

        val c = Offset(size.width / 2, size.height / 2)
        val zone = min(size.width, size.height) * 0.4f
        drawCircle(Hs.Primary.copy(alpha = 0.07f), zone, c)
        drawCircle(
            Hs.Primary.copy(alpha = 0.8f), zone, c,
            style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
        )

        // Radar sweep + pulse ring from the police.
        drawArc(
            Brush.sweepGradient(listOf(Color.Transparent, Hs.Police.copy(alpha = 0.35f)), c),
            sweep * 360f - 60f, 60f, true, Offset(c.x - zone, c.y - zone), Size(zone * 2, zone * 2),
        )
        drawCircle(Hs.Police.copy(alpha = (1f - sweep) * 0.6f), zone * sweep, c, style = Stroke(2f))
        val close = zone * 0.16f
        drawCircle(Hs.Green.copy(alpha = 0.18f), close, c)
        drawCircle(Hs.Green, close, c, style = Stroke(2f))

        val thieves = listOf(Offset(-0.55f, -0.45f), Offset(0.6f, -0.2f), Offset(-0.25f, 0.65f), Offset(0.1f, 0.12f))
        thieves.forEach { t -> thiefDot(Offset(c.x + t.x * zone, c.y + t.y * zone)) }
        if (outside) {
            val out = Offset(c.x + zone * 1.18f, c.y - zone * 0.55f)
            val pulse = 10f + 10f * sweep
            drawCircle(Hs.Warning.copy(alpha = 1f - sweep), pulse + 6f, out, style = Stroke(3f))
            thiefDot(out)
            drawLine(
                Hs.Warning, out, Offset(c.x + zone * 0.85f, c.y - zone * 0.38f), 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
            centeredText(tm, "${RULES.OUT_OF_RANGE_GRACE_SEC}s to return", Offset(out.x - 20f, out.y + 30f), Hs.Warning, 12)
            // Strikes used so far: 1 of 3.
            for (k in 0 until RULES.MAX_OUT_OF_RANGE_STRIKES) {
                val p = Offset(16.dp.toPx() + k * 20.dp.toPx(), size.height - 16.dp.toPx())
                if (k == 0) drawCircle(Hs.Warning, 5.dp.toPx(), p) else drawCircle(Hs.Border, 5.dp.toPx(), p, style = Stroke(3f))
            }
        }

        drawCircle(Color.White, 13f, c)
        drawCircle(Hs.Police, 10f, c)

        if (labels) {
            centeredText(tm, "${RULES.BOUNDARY_RADIUS_M} m", Offset(c.x, c.y - zone - 12f), Hs.Primary, 12)
            centeredText(tm, "1 m alert", Offset(c.x, c.y + close + 14f), Hs.Green, 12)
        }
    }
}

private fun DrawScope.thiefDot(p: Offset) {
    drawCircle(Color.White, 10f, p)
    drawCircle(Hs.Thief, 7.5f, p)
}

private fun DrawScope.centeredText(tm: TextMeasurer, text: String, center: Offset, color: Color, sizeSp: Int) {
    val layout = tm.measure(text, TextStyle(color = color, fontSize = sizeSp.sp, fontWeight = FontWeight.Bold))
    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(text, color = Hs.TextDim, fontSize = 12.sp)
    }
}

// ================================================================= charts

@Composable
private fun TimelineCard() {
    val hideMin = T.hideSec / 60f
    val total = hideMin + HUNT_MIN
    HsCard {
        Label("Game timeline")
        Canvas(
            Modifier.fillMaxWidth().height(46.dp).semantics {
                contentDescription = "Timeline: ${T.hideSec} seconds to hide, then a $HUNT_MIN minute hunt with a radar pulse every " +
                    "$PULSE_MIN minutes and a final $FINAL_MIN minute hunt"
            },
        ) {
            val barTop = 18.dp.toPx()
            val barH = 18.dp.toPx()
            fun xOf(min: Float) = size.width * (min / total)
            val gap = 2.dp.toPx()
            drawRoundRect(Hs.Thief, Offset(0f, barTop), Size(xOf(hideMin) - gap, barH), CornerRadius(6f))
            drawRoundRect(Hs.Police, Offset(xOf(hideMin), barTop), Size(xOf(total - FINAL_MIN) - xOf(hideMin) - gap, barH), CornerRadius(6f))
            drawRoundRect(Hs.Danger, Offset(xOf(total - FINAL_MIN), barTop), Size(size.width - xOf(total - FINAL_MIN), barH), CornerRadius(6f))
            var m = PULSE_MIN.toFloat()
            while (m < HUNT_MIN) {
                val px = xOf(hideMin + m)
                drawLine(Hs.Primary, Offset(px, barTop - 6.dp.toPx()), Offset(px, barTop + barH), 2.dp.toPx())
                drawCircle(Hs.Primary, 4.dp.toPx(), Offset(px, barTop - 8.dp.toPx()))
                m += PULSE_MIN
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("Start", color = Hs.TextFaint, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("${HUNT_MIN} min + ${T.hideSec}s", color = Hs.TextFaint, fontSize = 12.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Legend(Hs.Thief, "Hide · ${T.hideSec} s head start, police screen dark")
            Legend(Hs.Police, "Hunt · alerts within 1 m")
            Legend(Hs.Primary, "Radar pulse every $PULSE_MIN min · thief counts by distance")
            Legend(Hs.Danger, "Final $FINAL_MIN min · police radar widens to ${RULES.FINAL_HUNT_CLOSE_RADIUS_M.toInt()} m")
        }
    }
}

@Composable
private fun HBar(label: String, value: String, fraction: Float, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Hs.Text, fontSize = 13.sp, modifier = Modifier.width(96.dp))
        Box(Modifier.weight(1f).height(20.dp).background(Hs.SurfaceAlt, RoundedCornerShape(6.dp))) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color, RoundedCornerShape(6.dp)))
        }
        Spacer(Modifier.width(8.dp))
        Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(52.dp))
    }
}

@Composable
private fun ProximityCard() {
    val scale = RULES.FINAL_HUNT_CLOSE_RADIUS_M.toFloat()
    HsCard {
        Label("How close is \"close\"?")
        HBar("Bluetooth", "1–2 m", 1.5f / scale, Hs.Green)
        HBar("GPS error", "±${RULES.MAX_GPS_UNCERTAINTY_M.toInt()} m", RULES.MAX_GPS_UNCERTAINTY_M.toFloat() / scale, Hs.Warning)
        HBar("Final radar", "${scale.toInt()} m", 1f, Hs.Danger)
        Body(
            "Keep Bluetooth on! Every phone sends a tiny anonymous Bluetooth signal and listens for the others, sensing the police " +
                "within 1–2 m — far sharper than GPS. Without Bluetooth the game falls back to GPS.",
            dim = true, size = 13,
        )
    }
}

@Composable
private fun HeatCard() {
    HsCard {
        Label("Thief heat meter")
        Body("If the host turns it on, thieves see how far the police is:", dim = true, size = 13)
        HBar("Burning", "≤ 5 m", 0.1f, Hs.heat.getValue("burning"))
        HBar("Hot", "≤ 20 m", 0.25f, Hs.heat.getValue("hot"))
        HBar("Warm", "≤ 50 m", 0.55f, Hs.heat.getValue("warm"))
        HBar("Cold", "> 50 m", 1f, Hs.heat.getValue("cold"))
    }
}

@Composable
private fun PotCard() {
    HsCard {
        Label("Who wins the pot")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PotChip("5 players")
            Text("×", color = Hs.TextDim, fontSize = 16.sp)
            PotChip("₹20")
            Text("=", color = Hs.TextDim, fontSize = 16.sp)
            PotChip("₹100 pot", Hs.Primary)
        }
        PotRow("👮 Police catches all 4 thieves", listOf("₹100" to Hs.Police))
        PotRow("🦹 2 thieves never caught", listOf("₹50" to Hs.Thief, "₹50" to Hs.Thief))
        Body("Caught or disqualified thieves get nothing. Free games play for XP only.", dim = true, size = 13)
    }
}

@Composable
private fun PotChip(text: String, color: Color = Hs.Text) {
    Text(
        text, color = color, fontSize = 14.sp, fontWeight = FontWeight.Black,
        modifier = Modifier.background(Hs.SurfaceAlt, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun PotRow(title: String, parts: List<Pair<String, Color>>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Hs.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().height(30.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            parts.forEach { (amount, c) ->
                Box(Modifier.weight(1f).fillMaxHeight().background(c, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    Text(amount, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

// ================================================================= fair play + night

@Composable
private fun NumberedTile(n: Int, title: String, text: String) {
    Row(
        Modifier.fillMaxWidth().background(Hs.SurfaceAlt, RoundedCornerShape(12.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(26.dp).background(Hs.Green.copy(alpha = 0.2f), CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", color = Hs.Green, fontSize = 13.sp, fontWeight = FontWeight.Black)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Hs.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(text, color = Hs.TextDim, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun NightCard() {
    HsCard {
        Label("Night games")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.size(width = 84.dp, height = 140.dp).background(Color.Black, RoundedCornerShape(12.dp))
                    .border(1.dp, Hs.Border, RoundedCornerShape(12.dp)).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("CHOR", color = Hs.Stealth, fontSize = 13.sp, fontWeight = FontWeight.Black)
                Text("12:04", color = Hs.Stealth, fontSize = 18.sp, fontWeight = FontWeight.Black)
                Text("left", color = Hs.Stealth, fontSize = 12.sp)
            }
            Body(
                "Thieves can turn on Stealth screen — black with dim red text so the glow doesn't give away your hiding spot. " +
                    "Thief phones never vibrate; only the police phone does.",
                modifier = Modifier.weight(1f), size = 14,
            )
        }
    }
}
