package com.docpocket.smsrdv.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class AppTheme(
    val key: String,
    val title: String,
    val primaryColor: Color,
    val secondaryColor: Color,
    val isDark: Boolean = false
) {
    BLEU_MEDICAL(
        key = "BLEU_MEDICAL",
        title = "Bleu Médical",
        primaryColor = Color(0xFF0288D1),
        secondaryColor = Color(0xFF00ACC1)
    ),
    VERT_EMERAUDE(
        key = "VERT_EMERAUDE",
        title = "Vert Émeraude",
        primaryColor = Color(0xFF2E7D32),
        secondaryColor = Color(0xFF388E3C)
    ),
    CYAN_OCEAN(
        key = "CYAN_OCEAN",
        title = "Cyan Océan",
        primaryColor = Color(0xFF00838F),
        secondaryColor = Color(0xFF0097A7)
    ),
    VIOLET_IRIS(
        key = "VIOLET_IRIS",
        title = "Violet Iris",
        primaryColor = Color(0xFF6A1B9A),
        secondaryColor = Color(0xFF8E24AA)
    ),
    BLEU_NUIT(
        key = "BLEU_NUIT",
        title = "Bleu Nuit",
        primaryColor = Color(0xFF1A237E),
        secondaryColor = Color(0xFF283593)
    ),
    ROSE_RUBIS(
        key = "ROSE_RUBIS",
        title = "Rose Rubis",
        primaryColor = Color(0xFFAD1457),
        secondaryColor = Color(0xFFC2185B)
    ),
    AMBRE_SOLAIRE(
        key = "AMBRE_SOLAIRE",
        title = "Ambre Solaire",
        primaryColor = Color(0xFFE65100),
        secondaryColor = Color(0xFFEF6C00)
    ),
    TURQUOISE_DOUX(
        key = "TURQUOISE_DOUX",
        title = "Turquoise Doux",
        primaryColor = Color(0xFF00695C),
        secondaryColor = Color(0xFF00796B)
    ),
    TITANE_ARDOISE(
        key = "TITANE_ARDOISE",
        title = "Titane Ardoise",
        primaryColor = Color(0xFF37474F),
        secondaryColor = Color(0xFF455A64)
    ),
    DARK_AMOLED(
        key = "DARK_AMOLED",
        title = "Sombre AMOLED",
        primaryColor = Color(0xFF00E5FF),
        secondaryColor = Color(0xFF69F0AE),
        isDark = true
    );

    companion object {
        fun fromKey(key: String?): AppTheme {
            return values().firstOrNull { it.key == key } ?: BLEU_MEDICAL
        }
    }
}

@Composable
fun DocPocketTheme(
    selectedTheme: AppTheme = AppTheme.BLEU_MEDICAL,
    content: @Composable () -> Unit
) {
    val colorScheme = if (selectedTheme.isDark) {
        darkColorScheme(
            primary = selectedTheme.primaryColor,
            secondary = selectedTheme.secondaryColor,
            tertiary = Color(0xFFFFD54F),
            background = Color(0xFF000000),
            surface = Color(0xFF121212),
            onPrimary = Color(0xFF000000),
            onSecondary = Color(0xFF000000),
            onBackground = Color(0xFFEDEDED),
            onSurface = Color(0xFFEDEDED),
            surfaceVariant = Color(0xFF1E1E1E),
            onSurfaceVariant = Color(0xFFB0B0B0),
            primaryContainer = Color(0xFF00363A),
            onPrimaryContainer = Color(0xFF80DEEA)
        )
    } else {
        lightColorScheme(
            primary = selectedTheme.primaryColor,
            secondary = selectedTheme.secondaryColor,
            background = Color(0xFFF8FAFC),
            surface = Color(0xFFFFFFFF),
            onPrimary = Color(0xFFFFFFFF),
            onSecondary = Color(0xFFFFFFFF),
            onBackground = Color(0xFF1E293B),
            onSurface = Color(0xFF1E293B),
            surfaceVariant = Color(0xFFF1F5F9),
            onSurfaceVariant = Color(0xFF64748B),
            primaryContainer = selectedTheme.primaryColor.copy(alpha = 0.12f),
            onPrimaryContainer = selectedTheme.primaryColor
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
