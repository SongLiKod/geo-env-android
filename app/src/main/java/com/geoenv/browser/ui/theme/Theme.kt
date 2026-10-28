package com.geoenv.browser.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* =====================================================================================
 * 主题主色 & 语义色 Token（docs/geo-env-tech.md §4.2）
 * ===================================================================================*/

/** 从 HEX 字符串（#RRGGBB / RRGGBB）解析，失败回退默认主色。 */
fun parseHexColor(hex: String?, fallback: Color = DefaultPrimary): Color {
    if (hex.isNullOrBlank()) return fallback
    val s = hex.trim().removePrefix("#")
    return try {
        when (s.length) {
            6 -> Color(android.graphics.Color.parseColor("#$s"))
            8 -> Color(android.graphics.Color.parseColor("#$s"))
            else -> fallback
        }
    } catch (_: IllegalArgumentException) {
        fallback
    }
}

fun Color.toHex(): String = "#" + String.format(
    "%02X%02X%02X",
    (red * 255).toInt().coerceIn(0, 255),
    (green * 255).toInt().coerceIn(0, 255),
    (blue * 255).toInt().coerceIn(0, 255),
)

val DefaultPrimary = Color(0xFF1E6FFF)

/** 语义色（success / warning 不随主题变化，仅 error 跟随） */
object GeoEnvSemantic {
    val Success = Color(0xFF188038)
    val Warning = Color(0xFFE37400)
    val Error = Color(0xFFD93025)
    val Running = Color(0xFF188038)
    val Stopped = Color(0xFF9AA0A6)
    val Starting = Color(0xFFE37400)
    val Failed = Color(0xFFD93025)
}

/* =====================================================================================
 * 布局密度
 * ===================================================================================*/
enum class DensityMode { COMPACT, DEFAULT }

data class GeoEnvDensity(
    val mode: DensityMode = DensityMode.DEFAULT,
    val horizontalPadding: Dp = 16.dp,
    val verticalPadding: Dp = 12.dp,
    val itemSpacing: Dp = 8.dp,
    val cardElevation: Dp = 1.dp,
    val topBarHeight: Dp = 56.dp,
    val bottomBarHeight: Dp = 64.dp,
    val minHeight: Dp = 40.dp,
    val iconSize: Dp = 24.dp,
    val textStyle: androidx.compose.ui.unit.TextUnit = 14.sp,
) {
    companion object {
        fun of(mode: DensityMode): GeoEnvDensity = when (mode) {
            DensityMode.COMPACT -> GeoEnvDensity(
                mode = DensityMode.COMPACT,
                horizontalPadding = 10.dp,
                verticalPadding = 6.dp,
                itemSpacing = 4.dp,
                cardElevation = 0.dp,
                topBarHeight = 48.dp,
                bottomBarHeight = 56.dp,
                minHeight = 32.dp,
                iconSize = 20.dp,
                textStyle = 13.sp,
            )
            DensityMode.DEFAULT -> GeoEnvDensity()
        }
    }
}

private val LocalDensityMode = staticCompositionLocalOf { GeoEnvDensity() }

/** 获取当前布局密度 */
val CurrentDensity: GeoEnvDensity
    @Composable @ReadOnlyComposable get() = LocalDensityMode.current

/* =====================================================================================
 * 主题主入口
 * ===================================================================================*/
@Composable
fun GeoEnvTheme(
    primaryHex: String,
    darkTheme: Boolean,
    densityMode: DensityMode,
    content: @Composable () -> Unit,
) {
    val primary = parseHexColor(primaryHex)
    val scheme = if (darkTheme) darkScheme(primary) else lightScheme(primary)
    val density = GeoEnvDensity.of(densityMode)

    CompositionLocalProvider(LocalDensityMode provides density) {
        MaterialTheme(
            colorScheme = scheme,
            typography = GeoEnvTypography,
            shapes = GeoEnvShapes,
            content = content,
        )
    }
}

/** 依据主色自动衍生浅色配色（需求 4.2：自动衍生配套变体）。 */
private fun lightScheme(primary: Color) = lightColorScheme(
    primary = primary,
    onPrimary = Color.White,
    primaryContainer = primary.copy(alpha = 0.12f),
    onPrimaryContainer = primary,
    secondary = primary.copy(alpha = 0.72f),
    onSecondary = Color.White,
    secondaryContainer = primary.copy(alpha = 0.10f),
    onSecondaryContainer = primary,
    tertiary = primary.copy(alpha = 0.55f),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF1B1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1C1E),
    surfaceVariant = Color(0xFFEDEFF3),
    onSurfaceVariant = Color(0xFF44474F),
    surfaceContainer = Color(0xFFF1F3F7),
    surfaceContainerHigh = Color(0xFFE9EBF0),
    outline = Color(0xFFC4C6D0),
    outlineVariant = Color(0xFFE1E3E9),
    error = GeoEnvSemantic.Error,
    onError = Color.White,
    errorContainer = GeoEnvSemantic.Error.copy(alpha = 0.12f),
    onErrorContainer = GeoEnvSemantic.Error,
    scrim = Color.Black,
)

private fun darkScheme(primary: Color) = darkColorScheme(
    primary = primary,
    onPrimary = Color.White,
    primaryContainer = primary.copy(alpha = 0.30f),
    onPrimaryContainer = Color.White,
    secondary = primary.copy(alpha = 0.80f),
    onSecondary = Color.White,
    secondaryContainer = primary.copy(alpha = 0.24f),
    onSecondaryContainer = Color.White,
    tertiary = primary.copy(alpha = 0.60f),
    background = Color(0xFF101318),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF171B22),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF22262E),
    onSurfaceVariant = Color(0xFFC3C6CF),
    surfaceContainer = Color(0xFF1D212A),
    surfaceContainerHigh = Color(0xFF272B34),
    outline = Color(0xFF8B8F99),
    outlineVariant = Color(0xFF3A3E47),
    error = Color(0xFFF28B82),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDB),
    scrim = Color.Black,
)

/** 便捷：跟随系统暗色判断 */
@Composable
fun isDarkSystem(): Boolean = isSystemInDarkTheme()
