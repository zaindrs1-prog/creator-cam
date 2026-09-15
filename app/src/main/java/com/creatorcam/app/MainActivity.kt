package com.creatorcam.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.creatorcam.app.settings.AppSettings
import com.creatorcam.app.settings.SettingsRepository
import com.creatorcam.app.ui.nav.AppNav
import com.creatorcam.app.ui.theme.CreatorCamTheme

/**
 * Single-activity host. All screens are Compose destinations; camera work is
 * owned by ViewModels/services so rotation never tears down a recording —
 * the activity only declares configChanges as a second line of defence
 * (see AndroidManifest) while recording locks orientation outright.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settingsRepo = SettingsRepository(applicationContext)
        setContent {
            val settings by settingsRepo.settings.collectAsState(AppSettings())
            CreatorCamTheme(theme = settings.theme) {
                AppNav()
            }
        }
    }
}
