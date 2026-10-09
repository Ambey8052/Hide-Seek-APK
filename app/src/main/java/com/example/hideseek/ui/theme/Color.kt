package com.example.hideseek.ui.theme

import androidx.compose.ui.graphics.Color

// Dark "night-friendly" palette: low glare so a lit screen doesn't give away a hiding spot.
object Hs {
    val Bg = Color(0xFF0B0F1A)
    val Surface = Color(0xFF151B2B)
    val SurfaceAlt = Color(0xFF1E2638)
    val Border = Color(0xFF2A3348)
    val Text = Color(0xFFF1F5F9)
    val TextDim = Color(0xFF94A3B8)
    val TextFaint = Color(0xFF64748B)
    val Primary = Color(0xFFFFB020)
    val PrimaryText = Color(0xFF1A1203)
    val Police = Color(0xFF3B82F6)
    val PoliceDark = Color(0xFF1E3A8A)
    val Thief = Color(0xFFEF4444)
    val ThiefDark = Color(0xFF7F1D1D)
    val Green = Color(0xFF22C55E)
    val Danger = Color(0xFFF43F5E)
    val Warning = Color(0xFFF59E0B)
    val Stealth = Color(0xFF7F1D1D)
    val CardBack = Color(0xFF2B1A4A)

    val heat = mapOf(
        "cold" to Color(0xFF38BDF8),
        "warm" to Color(0xFFFACC15),
        "hot" to Color(0xFFFB923C),
        "burning" to Color(0xFFEF4444),
    )
}
