package com.tabexplorer.android

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Blue = Color(0xFF2563EB)

@Composable
fun TabExplorerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF93C5FD), background = Color(0xFF0F172A), surface = Color(0xFF0F172A))
        else -> lightColorScheme(primary = Blue)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
