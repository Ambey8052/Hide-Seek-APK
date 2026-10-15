package com.example.hideseek.ui.screens

import androidx.compose.runtime.Composable
import com.example.hideseek.ui.Body
import com.example.hideseek.ui.HsCard
import com.example.hideseek.ui.HsPage
import com.example.hideseek.ui.Label

private val SECTIONS = listOf(
    "0 · No internet needed" to "Phones connect to each other directly over Bluetooth and Wi-Fi. One phone hosts the game; everyone else stays within about 30 m of it while joining. Keep Bluetooth, Wi-Fi and Location switched on.",
    "1 · Make a room" to "One player taps Create room — their phone becomes the host. Pick a free game or a test-money entry fee (₹10–₹100, same for everyone). Everyone else opens the app nearby and taps the room under \"Join a game nearby\" (or types the 6-letter code). 2–10 players.",
    "2 · Get ready" to "Everyone taps \"I'm in\". Test-money entry fees are only taken from wallets when the host starts — all at once, or nobody is charged.",
    "3 · Shake to flip" to "Each phone shows a face-down card. Shake your phone to flip it. One player becomes 👮 SIPAHI (police); everyone else is 🦹 CHOR (thief). Only you see your card until everyone has flipped.",
    "4 · Hide" to "Thieves get a 90-second head start while the police screen is blacked out. Stay within 100 m of the police phone at all times.",
    "5 · The hunt (20 min)" to "The police phone vibrates when a thief is within 1 m. A thief sees a green dot when the police is that close. In the final 2 minutes the police radar widens to 10 m. Every 3 minutes the police gets a radar pulse showing how many thieves are at each distance.",
    "★ Bluetooth radar" to "Keep Bluetooth on! During a game every phone sends out a tiny anonymous Bluetooth signal and listens for the others. That senses the police within about 1–2 m, which GPS (±5 m) can't do. Without Bluetooth the game falls back to GPS.",
    "6 · Catching" to "The police catches a thief by holding the CAUGHT button on the thief's phone, or by scanning the catch code (QR) on the thief's screen. Codes change every 30 seconds so screenshots don't work.",
    "7 · Out of the zone" to "Step outside 100 m and you get a 30-second warning to come back. Stay out longer, or leave the zone more than 3 times, and you're out.",
    "8 · Winning" to "The police catches every thief before time runs out → police takes the whole pot. Otherwise the thieves who were never caught split the pot equally. Caught or disqualified thieves get nothing.",
)

private val FAIR = listOf(
    "Nobody — not even the host phone — can choose who becomes police. The host locks in a secret seed (its fingerprint is the \"fairness seal\" in the lobby) before anyone joins. Every player adds their own random seed. The police is calculated from all of them, and the secret is revealed after the game so anyone can check.",
    "The same person can't be police twice in a row in a rematch.",
    "Fake-GPS apps are detected and the player is disqualified.",
    "If the police loses GPS / connection for 3 minutes, the game is cancelled and everyone is refunded in full. If the host leaves, the room closes for everyone, so the host should keep the app open.",
    "Close-range alerts use Bluetooth signal strength when available, and GPS otherwise. Zone checks give thieves the benefit of GPS error, so nobody is punished for GPS drift at the zone edge.",
    "This test build uses play money kept on each phone (max ₹500 added per day). Free games are unlimited.",
)

@Composable
fun HowToPlayScreen(onBack: () -> Unit) {
    HsPage("How to play", onBack) {
        SECTIONS.forEach { (title, text) ->
            HsCard {
                Label(title)
                Body(text)
            }
        }
        HsCard {
            Label("Fair play")
            FAIR.forEach { Body("• $it") }
        }
        HsCard {
            Label("Night games")
            Body("Thieves can turn on Stealth screen — a black screen with dim red text so the glow doesn't give away your hiding spot. Thief phones never vibrate when the police is near; only the police phone does.")
        }
    }
}
