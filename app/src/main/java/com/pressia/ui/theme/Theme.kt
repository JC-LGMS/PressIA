package com.pressia.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** Couleurs dominantes proposées (rouge Press par défaut). Accepte aussi un hexadécimal "#RRGGBB". */
fun accentSeed(accent: String): Color {
    parseHexColor(accent)?.let { return it }
    return when (accent) {
        "blue" -> Color(0xFF1565C0)
        "green" -> Color(0xFF2E7D32)
        "violet" -> Color(0xFF6A1B9A)
        "orange" -> Color(0xFFE65100)
        "pink" -> Color(0xFFC2185B)
        "teal" -> Color(0xFF00796B)
        else -> Color(0xFFB71C1C) // rouge Press
    }
}

/** Parse "#RRGGBB" ou "#AARRGGBB" (pur Kotlin, testable). */
fun parseHexColor(hex: String): Color? {
    return try {
        val h = hex.trim().removePrefix("#")
        if (h.length != 6 && h.length != 8) return null
        h.toLong(16) // valide les caractères
        val v = if (h.length == 6) "FF$h" else h
        // Constructeur Long (0xAARRGGBB sRGB), pas ULong : ce dernier attend une valeur déjà
        // packée et fabriquait une couleur corrompue qui plantait au premier luminance().
        Color(v.toLong(16))
    } catch (_: Exception) { null }
}

/** `#RRGGBB` depuis une couleur (pour le sélecteur). */
fun Color.toHex(): String {
    fun b(f: Float) = (f * 255).toInt().coerceIn(0, 255).toString(16).padStart(2, '0').uppercase()
    return "#${b(red)}${b(green)}${b(blue)}"
}

/** Conversion RGB → HSV (pur Kotlin, pour le sélecteur de couleur). */
fun colorToHsv(c: Color): FloatArray {
    val r = c.red; val g = c.green; val b = c.blue
    val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
    var h = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    if (h < 0) h += 360f
    val s = if (max == 0f) 0f else d / max
    return floatArrayOf(h, s, max)
}

val ACCENTS = listOf(
    "red" to Color(0xFFB71C1C),
    "blue" to Color(0xFF1565C0),
    "green" to Color(0xFF2E7D32),
    "violet" to Color(0xFF6A1B9A),
    "orange" to Color(0xFFE65100),
    "pink" to Color(0xFFC2185B),
    "teal" to Color(0xFF00796B)
)

/** Fonds d'écran : (id, libellé, couleur claire, couleur sombre). */
data class BgChoice(val id: String, val label: String, val light: Color?, val dark: Color?)

/**
 * Fonds proposés. Chaque choix a une variante claire et une variante sombre : sans cela, un fond
 * clair (gris, sépia, blanc) écrasait le thème Sombre / AMOLED qui restait alors clair.
 *
 * « Défaut » vaut #FAFAFA le jour (les Scaffold affichent `surface`, blanc sans cela) et
 * #121212 la nuit, comme le schéma de base.
 */
val BACKGROUNDS = listOf(
    BgChoice("default", "Défaut", Color(0xFFFAFAFA), Color(0xFF121212)),
    BgChoice("white", "Blanc", Color(0xFFFFFFFF), Color(0xFF1C1C1E)),
    BgChoice("black", "Noir", Color(0xFF000000), Color(0xFF000000)),
    BgChoice("sepia", "Sépia", Color(0xFFF3E9D2), Color(0xFF2B2317)),
    BgChoice("grey", "Gris", Color(0xFFE8EBEE), Color(0xFF22262A)),
    BgChoice("night", "Bleu nuit", Color(0xFF0D1B2A), Color(0xFF0D1B2A))
)

private fun seedLight(seed: Color) = lightColorScheme(
    primary = seed,
    onPrimary = Color.White,
    primaryContainer = seed.mix(Color.White, 0.88f),
    onPrimaryContainer = seed.mix(Color.Black, 0.30f),
    secondary = seed.mix(Color.Black, 0.25f),
    background = Color(0xFFFAFAFA),
    surface = Color.White
)

private fun seedDark(seed: Color) = darkColorScheme(
    primary = seed.mix(Color.White, 0.35f),
    onPrimary = Color.Black,
    primaryContainer = seed.mix(Color.Black, 0.45f),
    onPrimaryContainer = seed.mix(Color.White, 0.85f),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E)
)

private fun seedAmoled(seed: Color) = seedDark(seed).copy(
    background = Color.Black, surface = Color.Black, surfaceVariant = Color(0xFF111111),
    onBackground = DARK_TEXT, onSurface = DARK_TEXT, onSurfaceVariant = DARK_TEXT_MUTED
)

/**
 * Texte principal des fonds sombres. Un blanc pur (255) dazzle sur un fond noir ou très foncé :
 * on le descend d'un cran (~236), ce qui reste nettement lisible.
 */
private val DARK_TEXT = Color(0xFFEDEDF0)
private val DARK_TEXT_MUTED = Color(0xFFB9B4C0)

private fun Color.mix(other: Color, f: Float): Color = lerp(this, other, f.coerceIn(0f, 1f))

/** Assombrit une couleur claire pour qu'elle reste lisible en thème sombre (teinte conservée). */
fun Color.toDarkVariant(): Color {
    val (h, s, v) = colorToHsv(this)
    return Color.hsv(h, s * 0.55f, v * 0.22f)
}

/** Applique une couleur de fond à tout le schéma (fonds, surfaces, textes adaptés au contraste). */
fun applyBackground(scheme: ColorScheme, c: Color): ColorScheme {
    val darkBg = c.luminance() < 0.5f
    // Texte ni tout blanc ni tout noir : un cran en retrait du fond, plus confortable la nuit.
    val on = if (darkBg) DARK_TEXT else Color(0xFF191C1E)
    val onVar = if (darkBg) Color(0xFFB9B4C0) else Color(0xFF44474E)
    return scheme.copy(
        background = c, surface = c, surfaceVariant = c,
        surfaceContainer = c, surfaceContainerLow = c, surfaceContainerHigh = c,
        surfaceContainerHighest = c, surfaceContainerLowest = c,
        onBackground = on, onSurface = on, onSurfaceVariant = onVar
    )
}

/** Résout la couleur de fond choisie pour le thème actif (clair, sombre ou AMOLED). */
fun resolveBackground(bg: String, dark: Boolean): Color? {
    BACKGROUNDS.firstOrNull { it.id == bg }?.let { choice ->
        val c = if (dark) choice.dark else choice.light
        if (c != null) return c
    }
    // Couleur personnalisée "#RRGGBB" : en thème sombre on l'assombrit au lieu de la laisser claquer.
    if (bg.trim().startsWith("#")) parseHexColor(bg)?.let { return if (dark) it.toDarkVariant() else it }
    return null
}

@Composable
fun PressIATheme(theme: String = "system", accent: String = "red", bg: String = "default", content: @Composable () -> Unit) {
    val dark = when (theme) {
        "light" -> false
        "dark", "amoled" -> true
        else -> isSystemInDarkTheme()
    }
    val seed = accentSeed(accent)
    var scheme = when (theme) {
        "amoled" -> seedAmoled(seed)
        "dark" -> seedDark(seed)
        "light" -> seedLight(seed)
        else -> if (dark) seedDark(seed) else seedLight(seed)
    }
    // AMOLED impose le noir : sans cette exception, un fond clair choisi dans les Réglages
    // écrasait le noir et l'AMOLED se retrouvait plus clair que le thème Sombre.
    if (theme != "amoled") resolveBackground(bg, dark)?.let { c -> scheme = applyBackground(scheme, c) }
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
