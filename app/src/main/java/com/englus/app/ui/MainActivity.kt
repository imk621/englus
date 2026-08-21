package com.englus.app.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.englus.app.data.EpisodeKind
import com.englus.app.ui.dictation.DictationScreen
import com.englus.app.ui.dictation.DictationViewModel
import com.englus.app.ui.library.LibraryScreen
import com.englus.app.ui.live.LiveScreen
import com.englus.app.ui.live.LiveViewModel
import com.englus.app.ui.player.PlayerScreen
import com.englus.app.ui.player.PlayerViewModel
import com.englus.app.ui.podcast.PodcastScreen
import com.englus.app.ui.podcast.PodcastViewModel
import com.englus.app.ui.podcast.SubscriptionsScreen
import com.englus.app.ui.reading.ReaderScreen
import com.englus.app.ui.reading.ReaderViewModel
import com.englus.app.ui.reading.ReadingCaptureScreen
import com.englus.app.ui.review.ReviewScreen
import com.englus.app.ui.settings.SettingsScreen
import com.englus.app.ui.shadowing.ShadowingScreen
import com.englus.app.ui.shadowing.ShadowingViewModel
import com.englus.app.ui.exam.ExamScreen
import com.englus.app.ui.quality.TranscriptCheckScreen
import com.englus.app.ui.quality.TranscriptCheckViewModel
import com.englus.app.ui.exam.ExamViewModel
import com.englus.app.ui.sound.SoundDrillScreen
import com.englus.app.ui.sound.SoundDrillViewModel
import com.englus.app.ui.sound.SoundPatternListScreen
import com.englus.app.ui.srs.ReviewSessionScreen
import com.englus.app.ui.vocab.VocabScreen
import com.englus.app.ui.vocab.VocabViewModel
import com.englus.app.ui.writing.WritingScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Root() } }
    }
}

/**
 * The four skills are the app's top-level structure.
 *
 * They had been reachable only as unlabelled icons crowded into one action bar,
 * which hid three quarters of the app behind guesswork. Tabs make what the app
 * does legible without opening anything.
 */
private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    LISTENING("tab/listening", "듣기", Icons.Default.Headphones),
    /**
     * Speaking is the second priority of this app and until now had no home of
     * its own - shadowing was reachable only from inside a player. Live
     * conversation is the part that does not belong to any one episode, so it
     * gets the tab.
     */
    SPEAKING("tab/speaking", "말하기", Icons.Default.Mic),
    READING("tab/reading", "읽기", Icons.Default.MenuBook),
    WRITING("tab/writing", "쓰기", Icons.Default.EditNote),
    REVIEW("tab/review", "복습", Icons.Default.Bookmarks),
}

@Composable
private fun Root() {
    val nav = rememberNavController()

    // Transcription progress and review reminders both post notifications.
    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.hierarchy?.mapNotNull { it.route }?.firstOrNull()
    val onTab = Tab.entries.any { it.route == route }

    Scaffold(
        bottomBar = {
            // Only on the tabs themselves. A detail screen that keeps the bar
            // invites tapping away mid-task and losing the place.
            if (onTab) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(
            navController = nav,
            startDestination = Tab.LISTENING.route,
            modifier = Modifier.padding(bottom = pad.calculateBottomPadding()),
        ) {
            composable(Tab.LISTENING.route) {
                LibraryScreen(
                    kind = EpisodeKind.LISTENING,
                    onOpen = { nav.navigate("player/${it.id}") },
                    onPodcasts = { nav.navigate("podcasts") },
                    onSettings = { nav.navigate("settings") },
                    onReadingCapture = { nav.navigate("reading/capture") },
                )
            }
            composable(Tab.SPEAKING.route) {
                val application = app()
                val vm: LiveViewModel = viewModel(
                    key = "live",
                    factory = viewModelFactory { initializer { LiveViewModel(application) } },
                )
                LiveScreen(
                    vm = vm,
                    showBack = false,
                    onBack = {},
                    onOpenSettings = { nav.navigate("settings") },
                )
            }
            composable(Tab.READING.route) {
                LibraryScreen(
                    kind = EpisodeKind.READING,
                    onOpen = { nav.navigate("reader/${it.id}") },
                    onPodcasts = {},
                    onSettings = { nav.navigate("settings") },
                    onReadingCapture = { nav.navigate("reading/capture") },
                )
            }
            composable(Tab.WRITING.route) {
                WritingScreen(
                    showBack = false,
                    onBack = {},
                    onOpenSettings = { nav.navigate("settings") },
                )
            }
            composable(Tab.REVIEW.route) {
                ReviewScreen(
                    showBack = false,
                    onBack = {},
                    onOpenSettings = { nav.navigate("settings") },
                    onStartSession = { nav.navigate("srs") },
                    onSoundDrills = { nav.navigate("sound") },
                    onVocab = { nav.navigate("vocab") },
                )
            }

            composable("srs") { ReviewSessionScreen(onBack = { nav.popBackStack() }) }
            composable("vocab") {
                val application = app()
                val vm: VocabViewModel = viewModel(
                    key = "vocab",
                    factory = viewModelFactory { initializer { VocabViewModel(application) } },
                )
                VocabScreen(vm = vm, onBack = { nav.popBackStack() })
            }
            composable("sound") {
                val application = app()
                val vm: SoundDrillViewModel = viewModel(
                    key = "sound-list",
                    factory = viewModelFactory { initializer { SoundDrillViewModel(application, null) } },
                )
                SoundPatternListScreen(
                    vm = vm,
                    onBack = { nav.popBackStack() },
                    onDrill = { nav.navigate("sound/$it") },
                )
            }
            composable(
                "sound/{pattern}",
                arguments = listOf(navArgument("pattern") { type = NavType.StringType }),
            ) { e ->
                val name = e.arguments?.getString("pattern") ?: return@composable
                val application = app()
                val vm: SoundDrillViewModel = viewModel(
                    key = "sound-$name",
                    factory = viewModelFactory { initializer { SoundDrillViewModel(application, name) } },
                )
                SoundDrillScreen(vm = vm, onBack = { nav.popBackStack() })
            }
            composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
            composable("podcasts") {
                SubscriptionsScreen(
                    onOpen = { nav.navigate("podcast/$it") },
                    onBack = { nav.popBackStack() },
                )
            }
            composable("reading/capture") {
                ReadingCaptureScreen(
                    onBack = { nav.popBackStack() },
                    onOpen = { id ->
                        nav.popBackStack()
                        nav.navigate("reader/$id")
                    },
                )
            }

            composable(
                "podcast/{podcastId}",
                arguments = listOf(navArgument("podcastId") { type = NavType.LongType }),
            ) { e ->
                val id = e.arguments?.getLong("podcastId") ?: return@composable
                val application = app()
                val vm: PodcastViewModel = viewModel(
                    key = "podcast-$id",
                    factory = viewModelFactory { initializer { PodcastViewModel(application, id) } },
                )
                PodcastScreen(vm = vm, onBack = { nav.popBackStack() })
            }
            composable(
                "player/{episodeId}",
                arguments = listOf(navArgument("episodeId") { type = NavType.LongType }),
            ) { e ->
                val id = e.arguments?.getLong("episodeId") ?: return@composable
                val application = app()
                val vm: PlayerViewModel = viewModel(
                    key = "player-$id",
                    factory = viewModelFactory { initializer { PlayerViewModel(application, id) } },
                )
                PlayerScreen(
                    vm = vm,
                    onBack = { nav.popBackStack() },
                    onDictation = { position -> nav.navigate("dictation/$id/$position") },
                    onShadowing = { position -> nav.navigate("shadowing/$id/$position") },
                    onExam = { nav.navigate("exam/$id") },
                    onCheckTranscript = { nav.navigate("check/$id") },
                    onOpenSettings = { nav.navigate("settings") },
                )
            }
            composable(
                "reader/{episodeId}",
                arguments = listOf(navArgument("episodeId") { type = NavType.LongType }),
            ) { e ->
                val id = e.arguments?.getLong("episodeId") ?: return@composable
                val application = app()
                val vm: ReaderViewModel = viewModel(
                    key = "reader-$id",
                    factory = viewModelFactory { initializer { ReaderViewModel(application, id) } },
                )
                ReaderScreen(
                    vm = vm,
                    onBack = { nav.popBackStack() },
                    onOpenSettings = { nav.navigate("settings") },
                )
            }
            composable(
                "check/{episodeId}",
                arguments = listOf(navArgument("episodeId") { type = NavType.LongType }),
            ) { e ->
                val id = e.arguments?.getLong("episodeId") ?: return@composable
                val application = app()
                val vm: TranscriptCheckViewModel = viewModel(
                    key = "check-$id",
                    factory = viewModelFactory {
                        initializer { TranscriptCheckViewModel(application, id) }
                    },
                )
                TranscriptCheckScreen(vm = vm, onBack = { nav.popBackStack() })
            }
            composable(
                "exam/{episodeId}",
                arguments = listOf(navArgument("episodeId") { type = NavType.LongType }),
            ) { e ->
                val id = e.arguments?.getLong("episodeId") ?: return@composable
                val application = app()
                val vm: ExamViewModel = viewModel(
                    key = "exam-$id",
                    factory = viewModelFactory { initializer { ExamViewModel(application, id) } },
                )
                ExamScreen(vm = vm, onBack = { nav.popBackStack() })
            }
            composable(
                "shadowing/{episodeId}/{position}",
                arguments = listOf(
                    navArgument("episodeId") { type = NavType.LongType },
                    navArgument("position") { type = NavType.IntType },
                ),
            ) { e ->
                val episodeId = e.arguments?.getLong("episodeId") ?: return@composable
                val position = e.arguments?.getInt("position") ?: 0
                val application = app()
                val vm: ShadowingViewModel = viewModel(
                    key = "shadowing-$episodeId-$position",
                    factory = viewModelFactory {
                        initializer { ShadowingViewModel(application, episodeId, position) }
                    },
                )
                ShadowingScreen(vm = vm, onBack = { nav.popBackStack() })
            }
            composable(
                "dictation/{episodeId}/{position}",
                arguments = listOf(
                    navArgument("episodeId") { type = NavType.LongType },
                    navArgument("position") { type = NavType.IntType },
                ),
            ) { e ->
                val episodeId = e.arguments?.getLong("episodeId") ?: return@composable
                val position = e.arguments?.getInt("position") ?: 0
                val application = app()
                val vm: DictationViewModel = viewModel(
                    key = "dictation-$episodeId-$position",
                    factory = viewModelFactory {
                        initializer { DictationViewModel(application, episodeId, position) }
                    },
                )
                DictationScreen(
                    vm = vm,
                    onBack = { nav.popBackStack() },
                    onOpenSettings = { nav.navigate("settings") },
                )
            }
        }
    }
}

@Composable
private fun app(): android.app.Application =
    LocalContext.current.applicationContext as android.app.Application
