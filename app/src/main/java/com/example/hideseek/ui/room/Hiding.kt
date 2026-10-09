package com.example.hideseek.ui.room

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.example.hideseek.ui.Body
import com.example.hideseek.ui.HsScreen
import com.example.hideseek.ui.theme.Hs

/** Phase HIDING: thieves run and hide, the police screen is blacked out. */
@Composable
fun Hiding(ctx: RoomCtx) {
    val left = (ctx.room.hideEndsAt ?: ctx.now) - ctx.now
    val center = Modifier.fillMaxWidth()

    if (ctx.me.isPolice) {
        HsScreen(background = Color.Black) {
            Text("🙈", fontSize = 80.sp, textAlign = TextAlign.Center, modifier = center)
            Text("CLOSE YOUR EYES & COUNT!", color = Hs.Text, fontSize = 28.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, modifier = center)
            Body("The thieves are hiding. Stay where you are.", dim = true, center = true, modifier = center)
            BigTimer(left, "Hunt starts in", Hs.Police, modifier = center)
            GpsNotice(ctx.gps)
            BleNotice(ctx.ble)
            GpsBadge(ctx.gps)
            BleBadge(ctx)
        }
        return
    }

    HsScreen {
        Text("🏃", fontSize = 80.sp, textAlign = TextAlign.Center, modifier = center)
        Text("GO HIDE!", color = Hs.Thief, fontSize = 30.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, modifier = center)
        Body(
            "${ctx.room.police?.name ?: "Someone"} is the police 👮. Hide anywhere within ${ctx.room.rules.boundaryM} m of them.",
            dim = true, center = true, modifier = center,
        )
        BigTimer(left, "Police starts hunting in", Hs.Primary, modifier = center)
        OutOfRangeBanner(ctx)
        GpsNotice(ctx.gps)
        BleNotice(ctx.ble)
        GpsBadge(ctx.gps)
        BleBadge(ctx)
        Body(
            "Tip: turn on Stealth screen once you're hidden so your screen glow doesn't give you away.",
            dim = true, size = 13, center = true, modifier = center,
        )
    }
}
