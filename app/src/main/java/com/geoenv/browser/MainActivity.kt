package com.geoenv.browser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.geoenv.browser.db.ThemeConfig
import com.geoenv.browser.di.ServiceLocator
import com.geoenv.browser.ui.theme.DensityMode
import com.geoenv.browser.ui.theme.GeoEnvTheme
import com.geoenv.browser.ui.theme.isDarkSystem
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val locator = ServiceLocator.get()

        setContent {
            // 主题配置来自 Room theme_config 表（需求 3.8 / 验收 4：重启不丢失）
            val themeState by produceState(initialValue = ThemeUiState(), key1 = true) {
                locator.database.themeDao().observe().collect { cfg ->
                    val c = cfg ?: ThemeConfig.DEFAULT
                    value = ThemeUiState(
                        mode = c.mode,
                        primaryHex = c.primaryHex,
                        density = c.density,
                    )
                }
            }

            val systemDark = isDarkSystem()
            val dark = when (themeState.mode) {
                ThemeConfig.MODE_DARK -> true
                ThemeConfig.MODE_LIGHT -> false
                else -> systemDark
            }
            val densityMode =
                if (themeState.density == ThemeConfig.DENSITY_COMPACT) DensityMode.COMPACT
                else DensityMode.DEFAULT

            GeoEnvTheme(
                primaryHex = themeState.primaryHex,
                darkTheme = dark,
                densityMode = densityMode,
            ) {
                AppNavHost()
            }
        }
    }

    data class ThemeUiState(
        val mode: String = ThemeConfig.MODE_SYSTEM,
        val primaryHex: String = "#1E6FFF",
        val density: String = ThemeConfig.DENSITY_DEFAULT,
    )
}

/** 占位导航已迁移至 [com.geoenv.browser.AppNavHost]（Phase 4 完整路由）。 */
