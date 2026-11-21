package cz.offlinka.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cz.offlinka.ui.R
import cz.offlinka.ui.components.HeroBox
import cz.offlinka.ui.components.InkCard
import cz.offlinka.ui.components.OfflinkaLogo
import cz.offlinka.ui.components.OutlineButton
import cz.offlinka.ui.components.PrimaryButton
import cz.offlinka.ui.components.Ridge
import cz.offlinka.ui.components.TextLink
import cz.offlinka.ui.components.SheetOverlay
import cz.offlinka.ui.theme.Offlinka
import cz.offlinka.ui.theme.OfflinkaIcons

enum class ConnectMode { PICK, SEARCHING, UNPLUGGED }

enum class ConnectProblem { NONE, NOT_FOUND, WRONG_FOLDER, NO_ACCESS }

data class ConnectState(
    val mode: ConnectMode = ConnectMode.PICK,
    val problem: ConnectProblem = ConnectProblem.NONE,
    val showHelp: Boolean = false,
    /** Android reports a mounted USB drive: invite the tap. */
    val driveSeen: Boolean = false,
    /** Scanning takes longer than 3 s (a big drive on a slow port). */
    val slow: Boolean = false,
    /** The guides bundled with the app can be opened without the drive. */
    val canOpenGuides: Boolean = false,
)

@Composable
fun ConnectScreen(
    state: ConnectState,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenGuides: () -> Unit = {},
) {
    val c = Offlinka.colors
    var help by rememberSaveable { mutableStateOf(state.showHelp) }
    Box(modifier.fillMaxSize().background(c.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            HeroBox(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp)) {
                        OfflinkaLogo(Modifier.height(30.dp), onDark = true)
                    }
                    DriveIllustration(
                        unplugged = state.mode == ConnectMode.UNPLUGGED,
                        modifier = Modifier.padding(top = 8.dp).size(width = 200.dp, height = 240.dp),
                    )
                    Ridge(Modifier.padding(top = 10.dp))
                }
            }
            Column(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = 560.dp)
                    .padding(horizontal = 20.dp),
            ) {
                when (state.mode) {
                    ConnectMode.SEARCHING -> Searching(state.slow)
                    ConnectMode.UNPLUGGED -> Unplugged(onPick, if (state.canOpenGuides) onOpenGuides else null)
                    ConnectMode.PICK -> Pick(state, onPick)
                }
                Spacer(Modifier.height(16.dp))
                if (state.mode != ConnectMode.SEARCHING) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextLink(stringResource(R.string.connect_help_link), { help = true })
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
        if (help) {
            // No dispatcher in screenshot tests; in the app back closes the sheet.
            if (LocalOnBackPressedDispatcherOwner.current != null) BackHandler { help = false }
            SheetOverlay(onDismiss = { help = false }, dismissLabel = stringResource(R.string.action_close)) {
                HelpSheet()
            }
        }
    }
}

@Composable
private fun Pick(state: ConnectState, onPick: () -> Unit) {
    val c = Offlinka.colors
    Text(
        stringResource(R.string.connect_title),
        style = Offlinka.type.headline,
        color = c.ink,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.connect_body), style = Offlinka.type.body, color = c.ink2)
    Spacer(Modifier.height(20.dp))
    if (state.problem != ConnectProblem.NONE) {
        val (title, body) = when (state.problem) {
            ConnectProblem.NOT_FOUND -> R.string.connect_err_notfound_title to R.string.connect_err_notfound_body
            ConnectProblem.WRONG_FOLDER -> R.string.connect_err_wrongfolder_title to R.string.connect_err_wrongfolder_body
            else -> R.string.connect_err_noaccess_title to R.string.connect_err_noaccess_body
        }
        Note(OfflinkaIcons.Warning, c.warnInk, c.warnBg, c.warnLine) {
            Text(stringResource(title), style = Offlinka.type.subhead, color = c.ink)
            Text(stringResource(body), style = Offlinka.type.bodySmall, color = c.ink)
        }
    } else {
        // With a problem the note replaces the steps, so the help link stays on screen.
        Steps()
    }
    if (state.driveSeen) {
        Spacer(Modifier.height(16.dp))
        Note(OfflinkaIcons.Drive, c.accent, c.accentSoft, c.ok) {
            Text(stringResource(R.string.connect_drive_seen), style = Offlinka.type.bodyStrong, color = c.ink)
        }
    }
    Spacer(Modifier.height(24.dp))
    Box(
        if (state.driveSeen) {
            Modifier
                .border(3.dp, c.ok, RoundedCornerShape(12.dp))
                .padding(5.dp)
        } else {
            Modifier
        },
    ) {
        PrimaryButton(
            stringResource(if (state.problem == ConnectProblem.NONE) R.string.connect_button else R.string.connect_pick_again),
            onPick,
            Modifier.fillMaxWidth(),
            icon = OfflinkaIcons.Drive,
        )
    }
    Spacer(Modifier.height(10.dp))
    Text(
        stringResource(R.string.connect_remember),
        style = Offlinka.type.bodySmall,
        color = c.ink2,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A calm note: icon, tinted fill, outline. */
@Composable
private fun Note(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    fill: androidx.compose.ui.graphics.Color,
    outline: androidx.compose.ui.graphics.Color,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(fill)
            .border(1.5.dp, outline, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(content = content)
    }
}

@Composable
private fun Steps() {
    val c = Offlinka.colors
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf(R.string.connect_step_plug, R.string.connect_step_pick_android).forEachIndexed { i, res ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(c.accent),
                    contentAlignment = Alignment.Center,
                ) { Text("${i + 1}", style = Offlinka.type.subhead, color = c.onAccent) }
                Spacer(Modifier.width(16.dp))
                Text(stringResource(res), style = Offlinka.type.body, color = c.ink)
            }
        }
    }
}

@Composable
private fun Searching(slow: Boolean) {
    val c = Offlinka.colors
    Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = c.primary, trackColor = c.surfaceMuted, strokeCap = StrokeCap.Round)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.connect_searching), style = Offlinka.type.subhead, color = c.ink)
        if (slow) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.connect_slow), style = Offlinka.type.bodySmall, color = c.ink2, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Unplugged(onPick: () -> Unit, onOpenGuides: (() -> Unit)?) {
    val c = Offlinka.colors
    Text(
        stringResource(R.string.unplugged_title),
        style = Offlinka.type.headline,
        color = c.ink,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.unplugged_body), style = Offlinka.type.body, color = c.ink2)
    Spacer(Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(c.ok))
        }
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.unplugged_waiting), style = Offlinka.type.subhead, color = c.ink2)
    }
    Spacer(Modifier.height(28.dp))
    OutlineButton(stringResource(R.string.unplugged_pick_other), onPick, Modifier.fillMaxWidth(), icon = OfflinkaIcons.Drive, shadow = true, big = true)
    if (onOpenGuides != null) {
        Spacer(Modifier.height(14.dp))
        OutlineButton(stringResource(R.string.unplugged_guides), onOpenGuides, Modifier.fillMaxWidth(), icon = OfflinkaIcons.FirstAid, shadow = true, big = true)
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.HelpSheet() {
    val c = Offlinka.colors
    Text(
        stringResource(R.string.help_title),
        style = Offlinka.type.headline,
        color = c.ink,
        modifier = Modifier.padding(bottom = 12.dp).semantics { heading() },
    )
    Column(Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
        listOf(
            R.string.help_1, R.string.help_2, R.string.help_picker_menu, R.string.help_pick_folder,
            R.string.help_3_android, R.string.help_exfat, R.string.help_5,
        ).forEach { res ->
            Row(Modifier.padding(vertical = 6.dp)) {
                Box(Modifier.padding(top = 9.dp).size(7.dp).clip(RoundedCornerShape(4.dp)).background(c.primary))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(res), style = Offlinka.type.body, color = c.ink)
            }
        }
    }
}

/** A phone showing the app, with the drive in its USB-C port (or pulled out, dashed). */
@Composable
fun DriveIllustration(unplugged: Boolean, modifier: Modifier = Modifier) {
    val c = Offlinka.colors
    val desc = stringResource(R.string.cd_drive_illustration)
    // In the dark theme the phone is drawn in muted tones, not bright paper.
    val paper = if (c.isDark) c.onHero3 else c.onHero
    val screen = if (c.isDark) c.surfaceMuted else c.onHero
    Canvas(modifier.semantics { contentDescription = desc }) {
        val u = size.height / 240f
        fun r(x: Float, y: Float, w: Float, h: Float, rad: Float, color: androidx.compose.ui.graphics.Color, stroke: Float = 0f) {
            if (stroke > 0f) {
                drawRoundRect(color, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(rad * u), style = Stroke(stroke * u))
            } else {
                drawRoundRect(color, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(rad * u))
            }
        }
        val ox = (size.width / u - 200f) / 2f
        val px = ox + 48f
        // phone: hard shadow, body, screen
        r(px + 6f, 8f, 104f, 168f, 18f, c.shadowStrong)
        r(px, 2f, 104f, 168f, 18f, c.heroDeep)
        r(px, 2f, 104f, 168f, 18f, paper, 5f)
        r(px + 10f, 14f, 84f, 140f, 8f, screen)
        // screen: forest header with ridge, logo bar and peak, search box, tiles
        r(px + 10f, 14f, 84f, 44f, 8f, c.hero)
        drawRect(c.hero, Offset((px + 10f) * u, 40f * u), Size(84f * u, 16f * u))
        val ridge = Path().apply {
            moveTo((px + 10f) * u, 58f * u)
            listOf(0f to 50f, 10f to 54f, 22f to 47f, 34f to 53f, 46f to 46f, 58f to 52f, 70f to 47f, 84f to 52f).forEach { (x, y) ->
                lineTo((px + 10f + x) * u, y * u)
            }
            lineTo((px + 94f) * u, 58f * u); close()
        }
        drawPath(ridge, screen)
        r(px + 18f, 20f, 26f, 4f, 2f, paper)
        val peak = Path().apply {
            moveTo((px + 31f) * u, 15f * u); lineTo((px + 34f) * u, 19.5f * u); lineTo((px + 28f) * u, 19.5f * u); close()
        }
        drawPath(peak, c.primary)
        r(px + 18f, 30f, 68f, 9f, 2.5f, paper)
        r(px + 18f, 64f, 68f, 18f, 3f, c.surface)
        r(px + 18f, 64f, 68f, 18f, 3f, c.heroDeep, 1.6f)
        r(px + 22f, 68f, 9f, 9f, 2f, androidx.compose.ui.graphics.Color(0xFFE9DFC8))
        r(px + 22f, 68f, 9f, 9f, 2f, c.heroDeep, 1.2f)
        val tiles = listOf(
            Triple(px + 18f, 88f, androidx.compose.ui.graphics.Color(0xFFDCE6D3)),
            Triple(px + 54f, 88f, androidx.compose.ui.graphics.Color(0xFFF6D8CE)),
            Triple(px + 18f, 122f, androidx.compose.ui.graphics.Color(0xFFE9DFC8)),
            Triple(px + 54f, 122f, androidx.compose.ui.graphics.Color(0xFFFBDCC8)),
        )
        tiles.forEach { (x, y, tone) ->
            r(x, y, 32f, 28f, 3f, c.surface)
            r(x, y, 32f, 28f, 3f, c.heroDeep, 1.6f)
            r(x + 4f, y + 4f, 8f, 8f, 2f, tone)
            r(x + 4f, y + 4f, 8f, 8f, 2f, c.heroDeep, 1.2f)
        }
        // port
        r(px + 42f, 170f, 20f, 6f, 2f, c.onHero3)
        // the drive
        val dy = if (unplugged) 196f else 176f
        if (unplugged) {
            val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f * u, 4f * u))
            drawLine(paper, Offset((px + 52f) * u, 178f * u), Offset((px + 52f) * u, 192f * u), 2.5f * u, pathEffect = dash)
        }
        r(px + 46f, dy, 12f, 7f, 1.5f, c.onHero3)
        r(px + 40f, dy + 9f, 36f, 52f, 8f, c.shadowStrong)
        r(px + 34f, dy + 6f, 36f, 52f, 8f, c.heroDeep)
        r(px + 34f, dy + 6f, 36f, 52f, 8f, paper, 3.5f)
        r(px + 41f, dy + 13f, 22f, 38f, 4f, c.hero)
        val dpeak = Path().apply {
            moveTo((px + 52f) * u, (dy + 21f) * u); lineTo((px + 58f) * u, (dy + 30f) * u); lineTo((px + 46f) * u, (dy + 30f) * u); close()
        }
        drawPath(dpeak, c.primary)
        listOf(36f, 42f).forEach { yy ->
            drawLine(c.heroLine, Offset((px + 44f) * u, (dy + yy) * u), Offset((px + 60f) * u, (dy + yy + 1f) * u), 1.5f * u, StrokeCap.Round)
        }
    }
}
