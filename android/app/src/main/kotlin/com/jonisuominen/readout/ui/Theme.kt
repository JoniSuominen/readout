package com.jonisuominen.readout.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.jonisuominen.readout.core.ThemedColor
import com.jonisuominen.readout.core.WidgetRGB

private val Indigo = Color(0xFF4F4DCB)
private val IndigoLight = Color(0xFFB9B8FF)

@Composable
fun ReadoutTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = IndigoLight)
        else -> lightColorScheme(primary = Indigo)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

fun WidgetRGB.toColor(): Color = Color(argb)

@Composable
fun ThemedColor.current(): Color = resolve(isSystemInDarkTheme()).toColor()

val WarningColor: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFFFFAA33) else Color(0xFFB25E00)
