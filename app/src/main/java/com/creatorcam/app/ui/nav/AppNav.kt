package com.creatorcam.app.ui.nav

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.creatorcam.app.ui.camera.CameraScreen
import com.creatorcam.app.ui.devicecheck.DeviceCheckScreen
import com.creatorcam.app.ui.diagnostics.DiagnosticsScreen
import com.creatorcam.app.ui.editor.EditorScreen
import com.creatorcam.app.ui.home.HomeScreen
import com.creatorcam.app.ui.permissions.PermissionsScreen
import com.creatorcam.app.ui.player.PlayerScreen
import com.creatorcam.app.ui.recordings.RecordingsScreen
import com.creatorcam.app.ui.settings.SettingsScreen
import com.creatorcam.app.ui.teleprompter.TeleprompterScreen

object Routes {
    const val HOME = "home"
    const val PERMISSIONS = "permissions"
    const val DEVICE_CHECK = "device_check"
    const val CAMERA = "camera"
    const val RECORDINGS = "recordings"
    const val PLAYER = "player"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"
    const val TELEPROMPTER = "teleprompter"
    const val EDITOR = "editor"
}

/**
 * Primary user path: home → device check → camera → record → preview →
 * save → share. No onboarding carousel, no login, no paywalls.
 */
@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onDualCamera = { nav.navigate("${Routes.CAMERA}?mode=dual") },
                onSingleCamera = { nav.navigate("${Routes.CAMERA}?mode=single") },
                onRecordings = { nav.navigate(Routes.RECORDINGS) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onDeviceCheck = { nav.navigate(Routes.DEVICE_CHECK) },
                onTeleprompter = { nav.navigate(Routes.TELEPROMPTER) },
                onPermissionsNeeded = { nav.navigate(Routes.PERMISSIONS) },
            )
        }
        composable(Routes.PERMISSIONS) {
            PermissionsScreen(onDone = { nav.popBackStack() })
        }
        composable(Routes.DEVICE_CHECK) {
            DeviceCheckScreen(
                onBack = { nav.popBackStack() },
                onDualCamera = { nav.navigate("${Routes.CAMERA}?mode=dual") },
                onSingleCamera = { nav.navigate("${Routes.CAMERA}?mode=single") },
                onDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) },
            )
        }
        composable(
            route = "${Routes.CAMERA}?mode={mode}",
            arguments = listOf(navArgument("mode") {
                type = NavType.StringType
                defaultValue = "dual"
            }),
        ) { entry ->
            CameraScreen(
                startMode = entry.arguments?.getString("mode") ?: "dual",
                onBack = { nav.popBackStack() },
                onRecordingDone = { uri ->
                    nav.navigate("${Routes.PLAYER}?uri=${Uri.encode(uri.toString())}") {
                        popUpTo(Routes.CAMERA) { inclusive = true }
                    }
                },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.RECORDINGS) {
            RecordingsScreen(
                onBack = { nav.popBackStack() },
                onPlay = { uri ->
                    nav.navigate("${Routes.PLAYER}?uri=${Uri.encode(uri.toString())}")
                },
                onEdit = { uri ->
                    nav.navigate("${Routes.EDITOR}?uri=${Uri.encode(uri.toString())}")
                },
            )
        }
        composable(
            route = "${Routes.PLAYER}?uri={uri}",
            arguments = listOf(navArgument("uri") { type = NavType.StringType }),
        ) { entry ->
            val uri = Uri.parse(entry.arguments?.getString("uri").orEmpty())
            PlayerScreen(
                uri = uri,
                onBack = { nav.popBackStack() },
                onEdit = { nav.navigate("${Routes.EDITOR}?uri=${Uri.encode(uri.toString())}") },
            )
        }
        composable(
            route = "${Routes.EDITOR}?uri={uri}",
            arguments = listOf(navArgument("uri") { type = NavType.StringType }),
        ) { entry ->
            val uri = Uri.parse(entry.arguments?.getString("uri").orEmpty())
            EditorScreen(
                uri = uri,
                onBack = { nav.popBackStack() },
                onExported = { outUri ->
                    nav.navigate("${Routes.PLAYER}?uri=${Uri.encode(outUri.toString())}") {
                        popUpTo(Routes.EDITOR) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) },
            )
        }
        composable(Routes.DIAGNOSTICS) {
            DiagnosticsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.TELEPROMPTER) {
            TeleprompterScreen(onBack = { nav.popBackStack() })
        }
    }
}
