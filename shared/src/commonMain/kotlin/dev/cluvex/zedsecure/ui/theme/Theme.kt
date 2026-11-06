package dev.cluvex.zedsecure.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.ktx.animateColorScheme
import dev.cluvex.zedsecure.domain.model.AppDesign
import dev.cluvex.zedsecure.domain.model.BentoPrefs
import dev.cluvex.zedsecure.ui.bento.BentoPalettes
import dev.cluvex.zedsecure.ui.bento.BentoStyle
import dev.cluvex.zedsecure.ui.bento.LocalBentoStyle
import dev.cluvex.zedsecure.ui.bento.bentoColorScheme
import dev.cluvex.zedsecure.domain.model.ColorContrast
import dev.cluvex.zedsecure.domain.model.ColorStyle

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ZedSecureTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,

    accentColor: Int = 0,
    amoledBlack: Boolean = false,

    colorStyle: ColorStyle = ColorStyle.ZedSecure,
    colorContrast: ColorContrast = ColorContrast.Standard,
    animateColors: Boolean = true,

    design: AppDesign = AppDesign.Portal,

    bento: BentoPrefs = BentoPrefs(),

    languageTag: String = "en",

    fontScale: Float = 1f,

    font: dev.cluvex.zedsecure.domain.model.UiFont = dev.cluvex.zedsecure.domain.model.UiFont.Auto,

    uiScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val system = if (dynamicColor) dynamicColorSchemeOrNull(darkTheme) else null
    val bentoSeed = system?.primary ?: accentSeed(accentColor)
    val bentoColors = remember(bento.palette, darkTheme, bentoSeed, amoledBlack) {
        BentoPalettes.of(bento.palette, darkTheme, bentoSeed, amoledBlack)
    }
    val baseScheme = if (design == AppDesign.Bento) {
        remember(bentoColors) { bentoColorScheme(bentoColors) }
    } else {
        system ?: rememberZedColorScheme(darkTheme, accentColor, colorStyle, colorContrast)
    }
    // AMOLED is a full ColorScheme.copy(); cache it so a theme switch does not
    // re-copy the scheme on every animation frame.
    val scheme = remember(baseScheme, amoledBlack, darkTheme) {
        if (amoledBlack) baseScheme.amoled(darkTheme) else baseScheme
    }
    val bentoStyle = remember(bentoColors, bento, languageTag) { BentoStyle(bentoColors, bento, latin = languageTag != "fa") }
    val colorScheme = if (animateColors) {
        animateColorScheme(scheme, animationSpec = { spring(stiffness = Spring.StiffnessMediumLow) })
    } else {
        scheme
    }
    // Typography and MotionScheme are constant for a given language/design/font.
    // Rebuilding them on every recomposition (i.e. every animation frame of a
    // theme switch) hands MaterialExpressiveTheme new instances and forces the
    // whole tree's text to remeasure each frame.
    val typography = remember(languageTag, design, font) { typographyFor(languageTag, design, font) }
    val motionScheme = remember { MotionScheme.expressive() }

    val density = LocalDensity.current
    val scale = dev.cluvex.zedsecure.domain.model.UiScale.clamp(uiScale)
    val scaled = remember(density, fontScale, scale) {
        if (fontScale == 1f && scale == 1f) density else Density(density.density * scale, density.fontScale * fontScale)
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = motionScheme,
        shapes = ZedShapes,
        typography = typography,
    ) {
        CompositionLocalProvider(
            LocalDensity provides scaled,
            LocalAppDesign provides design,
            LocalBentoStyle provides bentoStyle,
            content = content,
        )
    }
}

@Composable
fun rememberZedColorScheme(
    dark: Boolean,
    accent: Int,
    style: ColorStyle,
    contrast: ColorContrast,
): ColorScheme = remember(dark, accent, style, contrast) { zedColorScheme(dark, accent, style, contrast) }

fun zedColorScheme(dark: Boolean, accent: Int, style: ColorStyle, contrast: ColorContrast): ColorScheme {
    val seed = accentSeed(accent)
    return if (style == ColorStyle.ZedSecure) {
        dynamicColorScheme(
            seedColor = seed,
            isDark = dark,
            primary = seed,
            secondary = BrandTeal,
            tertiary = BrandRose,
            style = PaletteStyle.TonalSpot,
            contrastLevel = contrast.level,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
        )
    } else {
        dynamicColorScheme(
            seedColor = seed,
            isDark = dark,
            style = style.palette,
            contrastLevel = contrast.level,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
        )
    }
}

fun accentSeed(index: Int): Color = AccentPresets.getOrNull(index)?.light ?: BrandIris

val ColorStyle.palette: PaletteStyle
    get() = when (this) {
        ColorStyle.ZedSecure, ColorStyle.TonalSpot -> PaletteStyle.TonalSpot
        ColorStyle.Vibrant -> PaletteStyle.Vibrant
        ColorStyle.Expressive -> PaletteStyle.Expressive
        ColorStyle.Rainbow -> PaletteStyle.Rainbow
        ColorStyle.FruitSalad -> PaletteStyle.FruitSalad
        ColorStyle.Fidelity -> PaletteStyle.Fidelity
        ColorStyle.Neutral -> PaletteStyle.Neutral
        ColorStyle.Monochrome -> PaletteStyle.Monochrome
    }

val LocalAppDesign = staticCompositionLocalOf { AppDesign.Bento }

@Composable
expect fun dynamicColorSchemeOrNull(darkTheme: Boolean): ColorScheme?
