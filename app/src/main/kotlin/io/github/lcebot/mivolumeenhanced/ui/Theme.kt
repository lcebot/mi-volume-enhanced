@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.mivolumeenhanced.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.annotation.ColorRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import io.github.lcebot.mivolumeenhanced.R

/**
 * The one theme every screen and sheet is drawn under. Typography and shapes are the expressive
 * defaults; the colour scheme is passed explicitly because MaterialExpressiveTheme's own default
 * is light only.
 */
@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = remember(context, dark) { appColorScheme(context, dark) }
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
}

/**
 * The wallpaper palette when the system offers one, otherwise the app's own (res/values/colors.xml).
 *
 * The wallpaper decides the accent but not what an error looks like: red marks a scope that is not
 * loaded, and a wallpaper that turned the error family green would say the opposite. So the error
 * family is always the app's own, on top of whichever palette applies.
 */
fun appColorScheme(context: Context, dark: Boolean): ColorScheme {
    val brand = BrandPalette(context)
    val base = runCatching { if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) }
        .getOrElse { brand.scheme(dark) }
    val own = if (dark) brand.dark else brand.light
    return base.copy(
        error = own.color(R.color.brand_error),
        onError = own.color(R.color.brand_on_error),
        errorContainer = own.color(R.color.brand_error_container),
        onErrorContainer = own.color(R.color.brand_on_error_container),
    )
}

/**
 * The app's palette, read from resources so the launcher background and the window background
 * share one source. Both appearances are resolved through their own configuration, because the
 * fixed roles of either scheme come from tones of the other.
 */
internal class BrandPalette(context: Context) {
    val light = Tones(context.withNight(false))
    val dark = Tones(context.withNight(true))

    class Tones(private val context: Context) {
        fun color(@ColorRes id: Int): Color = Color(context.getColor(id))
    }

    /**
     * Every role is bound: components choose their own roles, and a partial scheme shows Material's
     * baseline wherever it stops. The fixed roles keep one tone in both appearances, which the M3
     * tone assignments give from roles the palette already has: fixed is the light container (90),
     * fixed dim the dark main colour (80), on-fixed the light on-container (10), on-fixed-variant
     * the dark container (30).
     */
    fun scheme(isDark: Boolean): ColorScheme {
        val t = if (isDark) dark else light
        val primary = t.color(R.color.brand_primary)
        return (if (isDark) darkColorScheme() else lightColorScheme()).copy(
            primary = primary,
            onPrimary = t.color(R.color.brand_on_primary),
            primaryContainer = t.color(R.color.brand_primary_container),
            onPrimaryContainer = t.color(R.color.brand_on_primary_container),
            inversePrimary = t.color(R.color.brand_inverse_primary),
            secondary = t.color(R.color.brand_secondary),
            onSecondary = t.color(R.color.brand_on_secondary),
            secondaryContainer = t.color(R.color.brand_secondary_container),
            onSecondaryContainer = t.color(R.color.brand_on_secondary_container),
            tertiary = t.color(R.color.brand_tertiary),
            onTertiary = t.color(R.color.brand_on_tertiary),
            tertiaryContainer = t.color(R.color.brand_tertiary_container),
            onTertiaryContainer = t.color(R.color.brand_on_tertiary_container),
            background = t.color(R.color.brand_surface),
            onBackground = t.color(R.color.brand_on_surface),
            surface = t.color(R.color.brand_surface),
            onSurface = t.color(R.color.brand_on_surface),
            surfaceVariant = t.color(R.color.brand_surface_variant),
            onSurfaceVariant = t.color(R.color.brand_on_surface_variant),
            surfaceTint = primary,
            inverseSurface = t.color(R.color.brand_inverse_surface),
            inverseOnSurface = t.color(R.color.brand_inverse_on_surface),
            error = t.color(R.color.brand_error),
            onError = t.color(R.color.brand_on_error),
            errorContainer = t.color(R.color.brand_error_container),
            onErrorContainer = t.color(R.color.brand_on_error_container),
            outline = t.color(R.color.brand_outline),
            outlineVariant = t.color(R.color.brand_outline_variant),
            surfaceBright = t.color(R.color.brand_surface_bright),
            surfaceDim = t.color(R.color.brand_surface_dim),
            surfaceContainer = t.color(R.color.brand_surface_container),
            surfaceContainerHigh = t.color(R.color.brand_surface_container_high),
            surfaceContainerHighest = t.color(R.color.brand_surface_container_highest),
            surfaceContainerLow = t.color(R.color.brand_surface_container_low),
            surfaceContainerLowest = t.color(R.color.brand_surface_container_lowest),
            primaryFixed = light.color(R.color.brand_primary_container),
            primaryFixedDim = dark.color(R.color.brand_primary),
            onPrimaryFixed = light.color(R.color.brand_on_primary_container),
            onPrimaryFixedVariant = dark.color(R.color.brand_primary_container),
            secondaryFixed = light.color(R.color.brand_secondary_container),
            secondaryFixedDim = dark.color(R.color.brand_secondary),
            onSecondaryFixed = light.color(R.color.brand_on_secondary_container),
            onSecondaryFixedVariant = dark.color(R.color.brand_secondary_container),
            tertiaryFixed = light.color(R.color.brand_tertiary_container),
            tertiaryFixedDim = dark.color(R.color.brand_tertiary),
            onTertiaryFixed = light.color(R.color.brand_on_tertiary_container),
            onTertiaryFixedVariant = dark.color(R.color.brand_tertiary_container),
        )
    }
}

private fun Context.withNight(night: Boolean): Context {
    val config = Configuration(resources.configuration)
    val mode = if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
    return createConfigurationContext(config)
}

/**
 * Draws behind the system bars, with bar icons that contrast with the app's surface: enabling
 * edge-to-edge alone leaves them at the platform default, which follows the system theme rather
 * than what is drawn under them.
 */
fun Activity.drawEdgeToEdge() {
    WindowCompat.enableEdgeToEdge(window)
    val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !night
        isAppearanceLightNavigationBars = !night
    }
}
