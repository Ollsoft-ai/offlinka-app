package cz.offlinka.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cz.offlinka.app.drive.SafDriveAccess
import cz.offlinka.app.drive.ShelfState
import cz.offlinka.app.reader.PageInfo
import cz.offlinka.app.reader.ReaderController
import cz.offlinka.app.reader.ReaderUrls
import cz.offlinka.app.reader.RetainedReaderState
import cz.offlinka.app.ui.AppNavHost
import cz.offlinka.app.ui.GuidesReader
import cz.offlinka.app.ui.LocalReaderController
import cz.offlinka.app.ui.Routes
import cz.offlinka.ui.R
import cz.offlinka.ui.screens.ConnectMode
import cz.offlinka.ui.screens.ConnectProblem
import cz.offlinka.ui.screens.ConnectScreen
import cz.offlinka.ui.screens.ConnectState
import cz.offlinka.ui.theme.Offlinka
import cz.offlinka.ui.theme.OfflinkaTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : AppCompatActivity() {
    private lateinit var reader: ReaderController

    private val pickDrive = registerForActivityResult(PickDrive()) { uri ->
        if (uri != null) graph.shelf.onPicked(uri) else graph.shelf.onPickCancelled()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        graph.systemDark = isNight(resources.configuration)
        graph.fontScale = resources.configuration.fontScale
        reader = ReaderController(this, graph.server, graph.shelf, graph.scope, graph.readerPage, graph.retainedReader)
        reader.restoreState(savedInstanceState)
        setContent {
            OfflinkaTheme(darkTheme = isSystemInDarkTheme()) {
                CompositionLocalProvider(LocalReaderController provides reader) {
                    AppRoot()
                }
            }
        }
    }

    @Composable
    private fun AppRoot() {
        val shelf by graph.shelf.state.collectAsStateWithLifecycle()
        val driveVisible by graph.driveVisible.collectAsStateWithLifecycle()
        // Once the books were open, the navigation stays alive under the "unplugged" screen,
        // so plugging the drive back in returns to the same article.
        var everReady by rememberSaveable { mutableStateOf(false) }
        if (shelf is ShelfState.Ready) everReady = true
        // "Flashku nevidím" shown after a tap with no USB volume mounted; the next tap opens the picker anyway.
        var noVolumeShown by rememberSaveable { mutableStateOf(false) }
        var guides by rememberSaveable { mutableStateOf(false) }
        val nav = rememberNavController()
        val back by nav.currentBackStackEntryAsState()

        // A drive picked from Settings that cannot be used: the open books stay, a short message says why.
        LaunchedEffect(Unit) {
            graph.shelf.pickProblem.collect { problem ->
                val text = when (problem) {
                    ConnectProblem.WRONG_FOLDER -> R.string.connect_err_wrongfolder_title
                    ConnectProblem.NOT_FOUND -> R.string.connect_err_notfound_title
                    else -> R.string.connect_err_noaccess_title
                }
                Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()
            }
        }

        var slow by remember { mutableStateOf(false) }
        LaunchedEffect(shelf is ShelfState.Loading) {
            slow = false
            if (shelf is ShelfState.Loading) {
                delay(3_000)
                slow = true
            }
        }
        if (driveVisible == true && noVolumeShown) noVolumeShown = false

        val connect: ConnectState? = when (val s = shelf) {
            is ShelfState.Ready -> null
            ShelfState.NoDrive -> ConnectState(
                problem = if (noVolumeShown) ConnectProblem.NOT_FOUND else ConnectProblem.NONE,
                driveSeen = driveVisible == true,
            )
            ShelfState.Loading -> ConnectState(mode = ConnectMode.SEARCHING, slow = slow)
            ShelfState.Unplugged -> ConnectState(mode = ConnectMode.UNPLUGGED)
            is ShelfState.Problem -> ConnectState(problem = s.problem, driveSeen = driveVisible == true && s.problem == ConnectProblem.NOT_FOUND)
        }?.copy(canOpenGuides = graph.shelf.builtin != null)

        // Status bar icons: light on the forest heroes, dark on paper screens (in the light theme).
        val dark = Offlinka.colors.isDark
        val route = back?.destination?.route
        val onHero = guides.not() && (connect != null || route == null || route == Routes.HOME || route == Routes.ABOUT)
        val view = androidx.compose.ui.platform.LocalView.current
        SideEffect {
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark && !onHero
        }

        Box(Modifier.fillMaxSize().background(Offlinka.colors.bg)) {
            if (everReady) {
                // Hidden from touch and TalkBack while the connect screen covers it.
                AppNavHost(
                    graph, nav, onChangeDrive = ::pickNow,
                    modifier = if (connect != null || guides) Modifier.clearAndSetSemantics {} else Modifier,
                )
            }
            if (connect != null && !guides) {
                // Back on the cover screen leaves the app instead of moving the hidden pages.
                BackHandler { moveTaskToBack(true) }
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                ) {
                    ConnectScreen(
                        connect,
                        onPick = {
                            if (driveVisible == false && !noVolumeShown && connect.mode == ConnectMode.PICK) {
                                noVolumeShown = true
                            } else {
                                pickNow()
                            }
                        },
                        onOpenGuides = { guides = true },
                    )
                }
            }
            if (guides) {
                BundledGuides(onClose = { guides = false })
            }
        }
    }

    /** A second, separate WebView for the bundled guides, so the hidden reader keeps its own page. */
    @Composable
    private fun BundledGuides(onClose: () -> Unit) {
        val controller = remember {
            ReaderController(this, graph.server, graph.shelf, graph.scope, MutableStateFlow(PageInfo()), RetainedReaderState())
        }
        DisposableEffect(Unit) { onDispose { controller.destroy() } }
        val guides = graph.shelf.builtin
        GuidesReader(graph, controller, guides?.let { ReaderUrls.article(it.id, it.mainPath ?: "") }, onClose)
    }

    private fun pickNow() {
        try {
            pickDrive.launch(Unit)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.connect_err_noaccess_body, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        reader.resume()
        // The drive may have been pulled out or plugged in while we were in the background.
        (application as OfflinkaApp).updateDriveVisible()
        graph.shelf.onStorageChanged()
    }

    override fun onPause() {
        reader.pause()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        graph.systemDark = isNight(newConfig)
        graph.fontScale = newConfig.fontScale
        reader.setFontScale(newConfig.fontScale)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        reader.saveState(outState)
    }

    override fun onDestroy() {
        reader.destroy()
        super.onDestroy()
    }

    private fun isNight(c: Configuration) = (c.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}

/** Opens the system folder picker on the USB drive. */
class PickDrive : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent = SafDriveAccess.pickerIntent(context)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}
