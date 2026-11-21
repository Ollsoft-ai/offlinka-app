package cz.offlinka.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import cz.offlinka.app.drive.SafDriveAccess
import cz.offlinka.app.drive.ShelfState
import cz.offlinka.app.reader.ReaderController
import cz.offlinka.app.ui.AppNavHost
import cz.offlinka.app.ui.LocalReaderController
import cz.offlinka.ui.R
import cz.offlinka.ui.screens.ConnectMode
import cz.offlinka.ui.screens.ConnectScreen
import cz.offlinka.ui.screens.ConnectState
import cz.offlinka.ui.theme.Offlinka
import cz.offlinka.ui.theme.OfflinkaTheme

class MainActivity : AppCompatActivity() {
    private lateinit var reader: ReaderController

    private val pickDrive = registerForActivityResult(PickDrive()) { uri ->
        if (uri != null) graph.shelf.onPicked(uri) else graph.shelf.onPickCancelled()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        graph.systemDark = isNight(resources.configuration)
        reader = ReaderController(this, graph.server, graph.shelf, graph.scope, graph.readerPage)
        reader.restoreState(savedInstanceState)
        setContent {
            OfflinkaTheme(darkTheme = isSystemInDarkTheme()) {
                CompositionLocalProvider(LocalReaderController provides reader) {
                    AppRoot(onPick = ::pick)
                }
            }
        }
    }

    @Composable
    private fun AppRoot(onPick: () -> Unit) {
        val shelf by graph.shelf.state.collectAsStateWithLifecycle()
        // Once the books were open, the navigation stays alive under the "unplugged" screen,
        // so plugging the drive back in returns to the same article.
        var everReady by rememberSaveable { mutableStateOf(false) }
        if (shelf is ShelfState.Ready) everReady = true
        val nav = rememberNavController()
        val connect: ConnectState? = when (val s = shelf) {
            is ShelfState.Ready -> null
            ShelfState.NoDrive -> ConnectState()
            ShelfState.Loading -> ConnectState(mode = ConnectMode.SEARCHING)
            ShelfState.Unplugged -> ConnectState(mode = ConnectMode.UNPLUGGED)
            is ShelfState.Problem -> ConnectState(problem = s.problem)
        }
        Box(Modifier.fillMaxSize().background(Offlinka.colors.bg)) {
            if (everReady) {
                // Hidden from touch and TalkBack while the connect screen covers it.
                AppNavHost(
                    graph, nav, onChangeDrive = onPick,
                    modifier = if (connect != null) Modifier.clearAndSetSemantics {} else Modifier,
                )
            }
            if (connect != null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                ) {
                    ConnectScreen(connect, onPick = onPick)
                }
            }
        }
    }

    private fun pick() {
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
        graph.shelf.onStorageChanged()
    }

    override fun onPause() {
        reader.pause()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        graph.systemDark = isNight(newConfig)
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
