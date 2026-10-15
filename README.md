# Chor Sipahi: real-world Hide & Seek for Android (Kotlin)

Friends join a room and everyone shakes their phone to flip a full-screen card. One player becomes
**👮 Sipahi (police)** and the rest are **🦹 Chor (thieves)**. Thieves hide within 100 m of the police
phone; the police hunts them for 20 minutes. Bluetooth and GPS tell the police when a thief is close.

**Fully offline.** No server, no internet, no Wi-Fi router needed: one phone hosts the game and the
others connect to it directly over **Bluetooth and Wi-Fi** (Google Nearby Connections).

```
HideSeek/
├── app/            Android app: Kotlin + Jetpack Compose (this Android Studio project)
├── server/         Old Node.js server, NOT needed any more (kept only for reference)
└── ChorSipahi.apk  Ready-to-install debug build
```

## Try it (2+ Android phones)

1. **Install `ChorSipahi.apk`** on each phone (allow "install unknown apps"), or click Run in
   Android Studio / `gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
2. On every phone switch on **Bluetooth, Wi-Fi and Location** (Wi-Fi doesn't need to be connected
   to any network). Allow the **Nearby devices** and **Location** permissions when asked.
3. **Phone A** taps **Create room**. It becomes the host and shows a 6-letter room code.
4. **Phones B, C…** open the app within ~30 m of phone A. The room appears by itself under
   **Join a game nearby** → tap **Join** (or type the code).
5. Everyone taps **I'm in**, the host taps **Deal the cards**, everyone shakes to flip.

**Quick test games:** in the lobby the host can turn on **⚡ Quick test** (3-minute hunt, 20-second
hiding time, radar pulse every 45 s) so you can play a whole round while testing.

**Engine tests (no phones needed):** `gradlew testDebugUnitTest` plays full games through the
game engine with a fake clock: police draw, hiding, the zone, Bluetooth proximity, QR catches,
forfeits, rematches and pot splitting.

### How the offline multiplayer works

- The **host phone runs the game** (`engine/RoomEngine.kt`, a Kotlin port of the old server's
  `Room.js`) and ticks it once per second. Every phone, including the host, gets its own snapshot.
- Phones find each other and connect with **Nearby Connections** (`net/NearbyLink.kt`, star
  topology). It discovers over Bluetooth / BLE and upgrades to a direct Wi-Fi link when it can.
  It needs Google Play services on the phones.
- `data/GameClient.kt` hides the difference: on the host it calls the engine directly, on other
  phones it sends the same actions to the host and waits for its answer.
- If a guest loses the host (walks too far), the app shows "reconnecting" and rejoins
  automatically when the host is in range again. Its place in the game is kept.
- **The host must keep the app open.** If the host closes the room or the app, the game ends for
  everyone and test-money entry fees are refunded.
- Up to 10 players. Bluetooth-only links usually handle about 7 phones; Nearby switches to Wi-Fi
  for more, so keep Wi-Fi on.
- **Profile, stats, test wallet and game history live on each phone** (`data/Profile.kt`).
  Each phone takes its own entry fee and adds its own winnings from the host's results.
  The wallet is play money: deposits are instant and nothing is really charged.

## How distance is measured

| Range | Sensor | Accuracy |
|---|---|---|
| 100 m zone, heat meter, radar pulse | GPS (fused location) | ±3–10 m |
| "Police within 1 m" (police vibrates, thief's green dot) | **Bluetooth LE** signal strength, GPS as fallback | ~1–2 m |
| Catch | Hold the CAUGHT button on the thief's phone, or scan the thief's QR code | exact |

**Bluetooth:** during a game every phone broadcasts a tiny advertisement holding a random tag that
the host assigned for that game only, and scans for the others. Each phone reports the signal
strength (RSSI) of the tags it hears to the host once per second. The host smooths the readings and, while they
are fresh, trusts them over GPS: about −62 dBm counts as "within ~1 m", and about −80 dBm as within
~10 m for the final-hunt radar. When Bluetooth is off or unavailable, the game falls back to GPS and
says so on screen. You can tune the thresholds `BLE_CLOSE_RSSI`, `BLE_FINAL_HUNT_RSSI` and
`BLE_BURNING_RSSI` in `engine/GameRules.kt`. Signal strength varies between phone models and when a phone is in a pocket,
so field-test the thresholds with your own phones.

**Why not Wi-Fi for distance?** Wi-Fi (via Nearby Connections) carries the game messages, but a
normal Wi-Fi link can't measure distance. Wi-Fi
ranging (Wi-Fi RTT / Wi-Fi Aware) does measure about 1 m, but only a minority of phones support
phone-to-phone Wi-Fi Aware ranging. It could be added later as an extra layer on phones that report
support (`PackageManager.FEATURE_WIFI_AWARE` + `FEATURE_WIFI_RTT`).

## Rules as implemented

- 2–10 players, 20-minute hunt with a 90-second hiding head start (police screen blacked out).
- Free games (unlimited), or a test-money entry fee of ₹10–₹100, the same for everyone, taken from
  each phone's wallet when the host deals. Max ₹500 test money added per day.
- Leaving the 100 m zone gives a 30-second warning; staying out, or leaving more than 3 times, is a
  disqualification.
- The police catches every thief and wins the whole pot. Otherwise the uncaught thieves split it equally.
- Fairness: provably fair police draw (seed committed before anyone joins, revealed afterwards), no
  police twice in a row, fake-GPS detection, refunds if the police disconnects for 3 minutes or the host closes a running game.
- Extras: radar pulse every 3 minutes, thief heat meter, a wider radar in the final 2 minutes,
  stealth screen for night play, rotating QR catch codes, XP/levels, a leaderboard of players you've met and game history.

## ⚠ Before taking real money

India's *Promotion and Regulation of Online Gaming Act, 2025* bans online money games and stops
payment processing for them. The paid mode very likely falls under it, so **talk to a lawyer first**.
This offline build only uses play money. Real money would also need a trusted server (a host phone
can't be trusted with other people's money), a payment gateway, KYC for payouts, and a signed
release build for the Play Store.

## Code map

- `app/.../engine/`: `RoomEngine` (game state machine, runs on the host phone), `GameRules`
  (rules, timings, distance and fairness helpers).
- `app/.../net/`: `NearbyLink` (Bluetooth / Wi-Fi phone-to-phone link, permissions).
- `app/.../data/`: `GameClient` (host or guest, room state), `Profile` (identity, stats, test
  wallet, history on the phone), `Models`.
- `app/.../device/`: shake detector, GPS streaming, `BleProximity`, vibration, QR generation.
- `app/.../ui/screens/`: home (host / nearby rooms), wallet, leaderboard, how to play.
- `app/.../ui/room/`: one composable per game phase (Lobby, Deal, Hiding, PoliceHunt, ThiefHunt,
  Result).
- `app/src/test/.../engine/RoomEngineTest.kt`: full games against the engine.
