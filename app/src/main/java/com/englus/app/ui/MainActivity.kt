package com.englus.app.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.englus.app.ui.library.LibraryScreen
import com.englus.app.ui.player.PlayerScreen
import com.englus.app.ui.player.PlayerViewModel
import com.englus.app.ui.podcast.PodcastScreen
import com.englus.app.ui.podcast.PodcastViewModel
import com.englus.app.ui.podcast.SubscriptionsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Root() } }
    }
}

@Composable
private fun Root() {
    val nav = rememberNavController()

    // Transcription runs as a foreground service; without notification permission
    // its progress is invisible on Android 13+.
    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    NavHost(navController = nav, startDestination = "library") {
        composable("library") {
            LibraryScreen(
                onOpen = { nav.navigate("player/${it.id}") },
                onPodcasts = { nav.navigate("podcasts") },
            )
        }
        composable("podcasts") {
            SubscriptionsScreen(
                onOpen = { nav.navigate("podcast/$it") },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            "podcast/{podcastId}",
            arguments = listOf(navArgument("podcastId") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("podcastId") ?: return@composable
            val app = androidx.compose.ui.platform.LocalContext.current.applicationContext
                    as android.app.Application
            val vm: PodcastViewModel = viewModel(
                key = "podcast-$id",
                factory = viewModelFactory { initializer { PodcastViewModel(app, id) } },
            )
            PodcastScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        composable(
            "player/{episodeId}",
            arguments = listOf(navArgument("episodeId") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("episodeId") ?: return@composable
            val app = androidx.compose.ui.platform.LocalContext.current.applicationContext
                    as android.app.Application
            val vm: PlayerViewModel = viewModel(
                key = "player-$id",
                factory = viewModelFactory { initializer { PlayerViewModel(app, id) } },
            )
            PlayerScreen(vm = vm, onBack = { nav.popBackStack() })
        }
    }
}
