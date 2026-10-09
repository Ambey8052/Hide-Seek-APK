# Chor Sipahi: real-world Hide & Seek for Android (Kotlin)

Friends join a room and everyone shakes their phone to flip a full-screen card. One player becomes
**👮 Sipahi (police)** and the rest are **🦹 Chor (thieves)**. Thieves hide within 100 m of the police
phone; the police hunts them for 20 minutes. Bluetooth and GPS tell the police when a thief is close.

```
HideSeek/
├── app/            Android app: Kotlin + Jetpack Compose (this Android Studio project)
├── server/         Game server: Node.js, Express REST + Socket.IO real-time, SQLite
└── ChorSipahi.apk  Ready-to-install debug build
```

## Try it

1. **Start the server** on a PC (Node 22.13+):
   ```bash
   cd server
   npm install
   npm start          # prints the PC's address, e.g. http://192.168.68.64:4000
   npm test           # 23 tests: rules, payouts, zone, GPS and Bluetooth proximity
   ```
2. **Install `ChorSipahi.apk`** on each Android phone (allow "install unknown apps").
3. Open the app: there is **no login**. Each phone automatically gets a guest player (tied to the
   device) with a random name; tap your name on the home screen to change it.
   Phones and the PC must be on the **same Wi-Fi**. If the PC's address differs from the one in the
   app, tap **⚙ Server** on the home screen (or on the "can't connect" screen) and enter it.
   If phones can't connect, allow Node.js through Windows Firewall (private networks).
4. Wallet top-ups are credited instantly in dev mode. Friends can add each other with the 8-character
   **player code** shown on the home and Friends screens. Guest play is on in dev mode, or with
   `ALLOW_GUESTS=1`; the phone/OTP login API still exists for a later production build.

**Quick test games:** `GAME_DURATION_SEC=180 HIDE_DURATION_SEC=20 npm start`
(PowerShell: `$env:GAME_DURATION_SEC=180; npm start`).

**Only one phone?** `node scripts/bot.js <ROOM_CODE> [lat] [lng]` adds a scripted second player
that joins, readies up, flips its card and reports a GPS position.

**Build the APK yourself:** open the project in Android Studio and click Run, or
`gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

## How distance is measured

| Range | Sensor | Accuracy |
|---|---|---|
| 100 m zone, heat meter, radar pulse | GPS (fused location) | ±3–10 m |
| "Police within 1 m" (police vibrates, thief's green dot) | **Bluetooth LE** signal strength, GPS as fallback | ~1–2 m |
| Catch | Hold the CAUGHT button on the thief's phone, or scan the thief's QR code | exact |

**Bluetooth:** during a game every phone broadcasts a tiny advertisement holding a random tag that
the server assigned for that game only, and scans for the others. Each phone reports the signal
strength (RSSI) of the tags it hears once per second. The server smooths the readings and, while they
are fresh, trusts them over GPS: about −62 dBm counts as "within ~1 m", and about −80 dBm as within
~10 m for the final-hunt radar. When Bluetooth is off or unavailable, the game falls back to GPS and
says so on screen. You can tune the thresholds with `BLE_CLOSE_RSSI`, `BLE_FINAL_HUNT_RSSI` and
`BLE_BURNING_RSSI`. Signal strength varies between phone models and when a phone is in a pocket,
so field-test the thresholds with your own phones.

**Why not Wi-Fi or a hotspot?** A hotspot only connects phones; it can't measure distance. Wi-Fi
ranging (Wi-Fi RTT / Wi-Fi Aware) does measure about 1 m, but only a minority of phones support
phone-to-phone Wi-Fi Aware ranging. It could be added later as an extra layer on phones that report
support (`PackageManager.FEATURE_WIFI_AWARE` + `FEATURE_WIFI_RTT`).

## Rules as implemented

- 2–10 players, 20-minute hunt with a 90-second hiding head start (police screen blacked out).
- Entry fee of ₹10–₹100, the same for everyone, taken from all wallets at once when the host deals
  (or from nobody if anyone is short). Max ₹500 deposit per day. One free game per player per day.
- Leaving the 100 m zone gives a 30-second warning; staying out, or leaving more than 3 times, is a
  disqualification.
- The police catches every thief and wins the whole pot. Otherwise the uncaught thieves split it equally.
- Fairness: provably fair police draw (seed committed before anyone joins, revealed afterwards), no
  police twice in a row, fake-GPS detection, refunds if the police disconnects or the server restarts.
- Extras: radar pulse every 3 minutes, thief heat meter, a wider radar in the final 2 minutes,
  stealth screen for night play, rotating QR catch codes, XP/levels, friends leaderboard and history.

## ⚠ Before taking real money

India's *Promotion and Regulation of Online Gaming Act, 2025* bans online money games and stops
payment processing for them. The paid mode very likely falls under it, so **talk to a lawyer first**.
The free mode works without payments. You also still need: a payment gateway (deposits are instant
only in dev mode), an SMS/OTP provider, HTTPS for the server (then remove `usesCleartextTraffic` from
the manifest), KYC for payouts, and a signed release build for the Play Store.

## Code map

- `app/.../data/`: `Session` (login, REST), `GameClient` (Socket.IO, room state), `Models`.
- `app/.../device/`: shake detector, GPS streaming, `BleProximity`, vibration, QR generation.
- `app/.../ui/screens/`: login, home, wallet, friends, leaderboard, how to play.
- `app/.../ui/room/`: one composable per game phase (Lobby, Deal, Hiding, PoliceHunt, ThiefHunt,
  Result).
- `server/src/game/Room.js`: game state machine. `server/src/rules.js`: pure rule functions.
