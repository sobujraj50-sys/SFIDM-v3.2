package com.sfidm.downloader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class AppTheme(
    val id: String,
    val labelEn: String,
    val labelBn: String,
    val bg: Color,
    val surface: Color,
    val accent: Color,
    val onAccent: Color
) {
    MIDNIGHT_CYBER(
        id = "midnight_cyber",
        labelEn = "Midnight Cyber",
        labelBn = "মিডনাইট সায়ান",
        bg = Color(0xFF0B0F19),
        surface = Color(0xFF151C2C),
        accent = Color(0xFF00D2FF),
        onAccent = Color(0xFF06131A)
    ),
    OBSIDIAN_GOLD(
        id = "obsidian_gold",
        labelEn = "Obsidian Gold",
        labelBn = "অবসিডিয়ান গোল্ড",
        bg = Color(0xFF09090B),
        surface = Color(0xFF18181B),
        accent = Color(0xFFF5B041),
        onAccent = Color(0xFF1A1204)
    ),
    CRIMSON_STEALTH(
        id = "crimson_stealth",
        labelEn = "Crimson Stealth",
        labelBn = "ক্রিমসন রেড",
        bg = Color(0xFF0A0A0C),
        surface = Color(0xFF16161A),
        accent = Color(0xFFFF2E51),
        onAccent = Color(0xFFFFFFFF)
    ),
    EMERALD_TERMINAL(
        id = "emerald_terminal",
        labelEn = "Emerald Terminal",
        labelBn = "এমেরাল্ড গ্রিন",
        bg = Color(0xFF060D0A),
        surface = Color(0xFF0F1D17),
        accent = Color(0xFF00E676),
        onAccent = Color(0xFF041A0E)
    ),
    VAPOR_VIOLET(
        id = "vapor_violet",
        labelEn = "Vapor Violet",
        labelBn = "ভ্যাপর ভায়োলেট",
        bg = Color(0xFF0D0A1A),
        surface = Color(0xFF1A1430),
        accent = Color(0xFFA855F7),
        onAccent = Color(0xFFFFFFFF)
    ),
    AMOLED_MONO(
        id = "amoled_mono",
        labelEn = "AMOLED Mono",
        labelBn = "অ্যামোলেড ব্ল্যাক",
        bg = Color(0xFF000000),
        surface = Color(0xFF141414),
        accent = Color(0xFFFFFFFF),
        onAccent = Color(0xFF000000)
    );

    companion object {
        fun fromId(id: String?): AppTheme = entries.find { it.id == id } ?: MIDNIGHT_CYBER
    }
}

@Composable
fun SfidmTheme(
    appTheme: AppTheme,
    content: @Composable () -> Unit
) {
    val colorScheme = darkColorScheme(
        primary = appTheme.accent,
        onPrimary = appTheme.onAccent,
        secondary = appTheme.accent.copy(alpha = 0.85f),
        background = appTheme.bg,
        onBackground = Color(0xFFF1F5F9),
        surface = appTheme.surface,
        onSurface = Color(0xFFF1F5F9),
        surfaceVariant = appTheme.surface.copy(alpha = 0.85f),
        onSurfaceVariant = Color(0xFF94A3B8),
        error = Color(0xFFEF4444),
        onError = Color.White
    )

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
