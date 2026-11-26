package com.docpocket.smsrdv.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

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
        primaryColor = Color(0xFF0284C7),
        secondaryColor = Color(0xFF0EA5E9)
    ),
    VERT_EMERAUDE(
        key = "VERT_EMERAUDE",
        title = "Vert Émeraude",
        primaryColor = Color(0xFF059669),
        secondaryColor = Color(0xFF10B981)
    ),
    CYAN_OCEAN(
        key = "CYAN_OCEAN",
        title = "Cyan Océan",
        primaryColor = Color(0xFF0891B2),
        secondaryColor = Color(0xFF06B6D4)
    ),
    VIOLET_IRIS(
        key = "VIOLET_IRIS",
        title = "Violet Iris",
        primaryColor = Color(0xFF7C3AED),
        secondaryColor = Color(0xFF8B5CF6)
    ),
    BLEU_NUIT(
        key = "BLEU_NUIT",
        title = "Bleu Nuit",
        primaryColor = Color(0xFF1E3A8A),
        secondaryColor = Color(0xFF3B82F6)
    ),
    ROSE_RUBIS(
        key = "ROSE_RUBIS",
        title = "Rose Rubis",
        primaryColor = Color(0xFFBE185D),
        secondaryColor = Color(0xFFEC4899)
    ),
    AMBRE_SOLAIRE(
        key = "AMBRE_SOLAIRE",
        title = "Ambre Solaire",
        primaryColor = Color(0xFFD97706),
        secondaryColor = Color(0xFFF59E0B)
    ),
    TURQUOISE_DOUX(
        key = "TURQUOISE_DOUX",
        title = "Turquoise Doux",
        primaryColor = Color(0xFF0D9488),
        secondaryColor = Color(0xFF14B8A6)
    ),
    TITANE_ARDOISE(
        key = "TITANE_ARDOISE",
        title = "Titane Ardoise",
        primaryColor = Color(0xFF334155),
        secondaryColor = Color(0xFF475569)
    ),
    DARK_AMOLED(
        key = "DARK_AMOLED",
        title = "Sombre AMOLED",
        primaryColor = Color(0xFF38BDF8),
        secondaryColor = Color(0xFF34D399),
        isDark = true
    );

    companion object {
        fun fromKey(key: String?): AppTheme {
            return values().firstOrNull { it.key == key } ?: BLEU_MEDICAL
        }
    }
}

private val DocPocketShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp)
)

@Composable
fun DocPocketTheme(
    selectedTheme: AppTheme = AppTheme.BLEU_MEDICAL,
    content: @Composable () -> Unit
) {
    val colorScheme = if (selectedTheme.isDark) {
        darkColorScheme(
            primary = selectedTheme.primaryColor,
            secondary = selectedTheme.secondaryColor,
            tertiary = Color(0xFFFBBF24),
            background = Color(0xFF090D16),
            surface = Color(0xFF111827),
            onPrimary = Color(0xFF001422),
            onSecondary = Color(0xFF001422),
            onBackground = Color(0xFFF1F5F9),
            onSurface = Color(0xFFF1F5F9),
            surfaceVariant = Color(0xFF1E293B),
            onSurfaceVariant = Color(0xFF94A3B8),
            outline = Color(0xFF334155),
            outlineVariant = Color(0xFF1E293B),
            primaryContainer = Color(0xFF0C4A6E),
            onPrimaryContainer = Color(0xFFBAE6FD),
            secondaryContainer = Color(0xFF1E293B),
            onSecondaryContainer = Color(0xFFE2E8F0)
        )
    } else {
        lightColorScheme(
            primary = selectedTheme.primaryColor,
            secondary = selectedTheme.secondaryColor,
            tertiary = Color(0xFFD97706),
            background = Color(0xFFF2F6FA),
            surface = Color(0xFFFFFFFF),
            onPrimary = Color(0xFFFFFFFF),
            onSecondary = Color(0xFFFFFFFF),
            onBackground = Color(0xFF0F172A),
            onSurface = Color(0xFF0F172A),
            surfaceVariant = Color(0xFFEBF1F7),
            onSurfaceVariant = Color(0xFF64748B),
            outline = Color(0xFFCBD5E1),
            outlineVariant = Color(0xFFE2E8F0),
            primaryContainer = selectedTheme.primaryColor.copy(alpha = 0.12f),
            onPrimaryContainer = selectedTheme.primaryColor,
            secondaryContainer = selectedTheme.secondaryColor.copy(alpha = 0.10f),
            onSecondaryContainer = Color(0xFF1E293B)
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colorScheme.surface.toArgb()
                window.navigationBarColor = colorScheme.background.toArgb()
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !selectedTheme.isDark
                insetsController.isAppearanceLightNavigationBars = !selectedTheme.isDark
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = DocPocketShapes,
        content = content
    )
}
