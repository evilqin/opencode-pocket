package dev.pocket.opencode.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** OpenCode Pocket 的配色：深色为主，青色是主品牌色，紫色做强调。 */
object PocketColors {
    val Cyan = Color(0xFF22D3EE)
    val CyanDeep = Color(0xFF0891B2)
    val Violet = Color(0xFFA78BFA)
    val Emerald = Color(0xFF34D399)
    val Amber = Color(0xFFFBBF24)
    val Rose = Color(0xFFFB7185)

    val DarkBackground = Color(0xFF0B1220)
    val DarkSurface = Color(0xFF101A2B)
    val DarkSurfaceHigh = Color(0xFF18243A)
}

private val DarkScheme = darkColorScheme(
    primary = PocketColors.Cyan,
    onPrimary = Color(0xFF04212B),
    primaryContainer = Color(0xFF0E3A48),
    onPrimaryContainer = Color(0xFFB8ECF7),
    secondary = PocketColors.Violet,
    onSecondary = Color(0xFF1F1235),
    secondaryContainer = Color(0xFF33235C),
    onSecondaryContainer = Color(0xFFE4DBFF),
    tertiary = PocketColors.Emerald,
    onTertiary = Color(0xFF04251B),
    tertiaryContainer = Color(0xFF12402F),
    onTertiaryContainer = Color(0xFFB8F0DA),
    background = PocketColors.DarkBackground,
    onBackground = Color(0xFFE7EDF7),
    surface = PocketColors.DarkSurface,
    onSurface = Color(0xFFE7EDF7),
    surfaceVariant = Color(0xFF1B2740),
    onSurfaceVariant = Color(0xFF9DB0CB),
    surfaceContainer = Color(0xFF131E33),
    surfaceContainerHigh = Color(0xFF182741),
    surfaceContainerHighest = Color(0xFF1E2F4C),
    outline = Color(0xFF31415F),
    outlineVariant = Color(0xFF24324C),
    error = PocketColors.Rose,
    onError = Color(0xFF35060C),
    errorContainer = Color(0xFF4C1119),
    onErrorContainer = Color(0xFFFFD9DD),
)

private val LightScheme = lightColorScheme(
    primary = PocketColors.CyanDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDF3FB),
    onPrimaryContainer = Color(0xFF00303C),
    secondary = Color(0xFF6D28D9),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9DDFF),
    onSecondaryContainer = Color(0xFF23005C),
    tertiary = Color(0xFF047857),
    onTertiary = Color.White,
    background = Color(0xFFF5F8FD),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE7EDF7),
    onSurfaceVariant = Color(0xFF44536B),
    surfaceContainer = Color(0xFFEDF2FA),
    surfaceContainerHigh = Color(0xFFE4EBF6),
    surfaceContainerHighest = Color(0xFFDBE4F2),
    outline = Color(0xFFB6C2D6),
    outlineVariant = Color(0xFFD3DCEA),
    error = Color(0xFFD64256),
)

private val Mono = FontFamily.Monospace

val PocketTypography = Typography(
    headlineMedium = TextStyle(fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.5.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

/** 代码/日志用等宽字体。 */
val CodeTextStyle = TextStyle(fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 18.sp)

@Composable
fun PocketTheme(
    themeMode: String = "dark",
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = PocketTypography,
        shapes = androidx.compose.material3.Shapes(
            extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
