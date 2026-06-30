package com.druk.lmplayground.theme

import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.druk.lmplayground.R

private val PlaygroundDarkColorScheme = darkColorScheme(
    primary = Blue80,
    onPrimary = Blue20,
    primaryContainer = Blue30,
    onPrimaryContainer = Blue90,
    inversePrimary = Blue40,
    secondary = DarkBlue80,
    onSecondary = DarkBlue20,
    secondaryContainer = DarkBlue30,
    onSecondaryContainer = DarkBlue90,
    tertiary = Yellow80,
    onTertiary = Yellow20,
    tertiaryContainer = Yellow30,
    onTertiaryContainer = Yellow90,
    error = Red80,
    onError = Red20,
    errorContainer = Red30,
    onErrorContainer = Red90,
    background = Grey10,
    onBackground = Grey90,
    surface = Grey10,
    onSurface = Grey80,
    inverseSurface = Grey90,
    inverseOnSurface = Grey20,
    surfaceVariant = BlueGrey30,
    onSurfaceVariant = BlueGrey80,
    outline = BlueGrey60
)

private val PlaygroundLightColorScheme = lightColorScheme(
    primary = Blue40,
    onPrimary = Color.White,
    primaryContainer = Blue90,
    onPrimaryContainer = Blue10,
    inversePrimary = Blue80,
    secondary = DarkBlue40,
    onSecondary = Color.White,
    secondaryContainer = DarkBlue90,
    onSecondaryContainer = DarkBlue10,
    tertiary = Yellow40,
    onTertiary = Color.White,
    tertiaryContainer = Yellow90,
    onTertiaryContainer = Yellow10,
    error = Red40,
    onError = Color.White,
    errorContainer = Red90,
    onErrorContainer = Red10,
    background = Grey99,
    onBackground = Grey10,
    surface = Grey99,
    onSurface = Grey10,
    inverseSurface = Grey20,
    inverseOnSurface = Grey95,
    surfaceVariant = BlueGrey90,
    onSurfaceVariant = BlueGrey30,
    outline = BlueGrey50
)

/**
 * User-selectable UI accent. SYSTEM (default) keeps the current behaviour —
 * Material You from the wallpaper on Android 12+. Every other entry is a seed
 * colour from which a full, contrast-safe Material 3 scheme is derived at
 * runtime (see [lightSchemeFromSeed] / [darkSchemeFromSeed]).
 */
enum class ThemeColor(val key: String, val seed: Color?, val labelRes: Int) {
    SYSTEM("system", null, R.string.theme_color_system),
    GREEN("green", Color(0xFF1F9E3E), R.string.theme_color_green),
    BLUE("blue", Color(0xFF1565C0), R.string.theme_color_blue),
    PURPLE("purple", Color(0xFF6750A4), R.string.theme_color_purple),
    TEAL("teal", Color(0xFF00897B), R.string.theme_color_teal),
    ORANGE("orange", Color(0xFFE8740C), R.string.theme_color_orange),
    ROSE("rose", Color(0xFFD81B60), R.string.theme_color_rose),
    RED("red", Color(0xFFD32F2F), R.string.theme_color_red),
    GREY("grey", Color(0xFF757575), R.string.theme_color_grey);

    companion object {
        fun fromKey(key: String?): ThemeColor = values().firstOrNull { it.key == key } ?: SYSTEM
    }
}

/**
 * Global, process-wide theme selection. A Compose snapshot state so that every
 * [PlaygroundTheme] call site (there are many — one per Compose screen) reacts
 * instantly when it changes. Initialised from [com.druk.lmplayground.storage.StoragePreferences]
 * in App.onCreate; updated by the Settings colour picker.
 */
object AppThemeState {
    var current by mutableStateOf(ThemeColor.SYSTEM)
}

private val DarkOnLight = Color(0xFF1C1B1F)

/** Blend [seed] toward black until its WCAG luminance is at most [maxLum]. */
private fun darkenTo(seed: Color, maxLum: Float): Color {
    var t = 0f
    var c = seed
    while (c.luminance() > maxLum && t < 1f) {
        t += 0.04f
        c = lerp(seed, Color.Black, t)
    }
    return c
}

/** Blend [seed] toward white until its WCAG luminance is at least [minLum]. */
private fun lightenTo(seed: Color, minLum: Float): Color {
    var t = 0f
    var c = seed
    while (c.luminance() < minLum && t < 1f) {
        t += 0.04f
        c = lerp(seed, Color.White, t)
    }
    return c
}

/**
 * Derive a light Material 3 scheme from a seed. Accents are forced DARK
 * (luminance <= ~0.16) so white on-text clears WCAG AA (~5:1); containers stay
 * very light with dark text. Neutrals/error are inherited from the base scheme.
 */
private fun lightSchemeFromSeed(seed: Color): ColorScheme {
    val muted = lerp(seed, Color(0xFF7A7A7A), 0.35f)
    val primary = darkenTo(seed, 0.16f)
    val secondary = darkenTo(muted, 0.18f)
    val primaryContainer = lerp(seed, Color.White, 0.82f)
    val secondaryContainer = lerp(muted, Color.White, 0.85f)
    return PlaygroundLightColorScheme.copy(
        primary = primary, onPrimary = Color.White,
        primaryContainer = primaryContainer, onPrimaryContainer = DarkOnLight,
        inversePrimary = lightenTo(seed, 0.62f),
        secondary = secondary, onSecondary = Color.White,
        secondaryContainer = secondaryContainer, onSecondaryContainer = DarkOnLight,
        tertiary = primary, onTertiary = Color.White,
        tertiaryContainer = primaryContainer, onTertiaryContainer = DarkOnLight,
    )
}

/**
 * Derive a dark Material 3 scheme from a seed. Mirrors the Material 3 dark
 * palette: accents are LIGHT (luminance >= ~0.60) with dark on-text (~10:1);
 * containers are very dark with white text. Neutrals/error inherited.
 */
private fun darkSchemeFromSeed(seed: Color): ColorScheme {
    val muted = lerp(seed, Color(0xFFAAAAAA), 0.30f)
    val primary = lightenTo(seed, 0.60f)
    val secondary = lightenTo(muted, 0.60f)
    val primaryContainer = darkenTo(seed, 0.12f)
    val secondaryContainer = darkenTo(muted, 0.12f)
    return PlaygroundDarkColorScheme.copy(
        primary = primary, onPrimary = DarkOnLight,
        primaryContainer = primaryContainer, onPrimaryContainer = Color.White,
        inversePrimary = darkenTo(seed, 0.20f),
        secondary = secondary, onSecondary = DarkOnLight,
        secondaryContainer = secondaryContainer, onSecondaryContainer = Color.White,
        tertiary = primary, onTertiary = DarkOnLight,
        tertiaryContainer = primaryContainer, onTertiaryContainer = Color.White,
    )
}

@SuppressLint("NewApi")
@Composable
fun PlaygroundTheme(
    isDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val choice = AppThemeState.current
    // SYSTEM on Android 12+ keeps Material You (wallpaper) — the original default.
    val useDynamic = choice == ThemeColor.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val myColorScheme = when {
        useDynamic && isDarkTheme -> dynamicDarkColorScheme(LocalContext.current)
        useDynamic && !isDarkTheme -> dynamicLightColorScheme(LocalContext.current)
        choice.seed != null && isDarkTheme -> darkSchemeFromSeed(choice.seed)
        choice.seed != null -> lightSchemeFromSeed(choice.seed)
        isDarkTheme -> PlaygroundDarkColorScheme
        else -> PlaygroundLightColorScheme
    }

    MaterialTheme(
        colorScheme = myColorScheme,
        typography = PlaygroundTypography,
        content = content
    )
}
