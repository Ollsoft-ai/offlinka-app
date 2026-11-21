package cz.offlinka.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.offlinka.ui.R
import cz.offlinka.ui.theme.Offlinka
import cz.offlinka.ui.theme.OfflinkaIcons

enum class MainTab { HOME, LIBRARY, SAVED, SETTINGS }

/** Bottom bar of the main screens: card surface, line on top, the orange peak above the current tab. */
@Composable
fun OfflinkaBottomBar(selected: MainTab, onSelect: (MainTab) -> Unit, modifier: Modifier = Modifier) {
    val c = Offlinka.colors
    Column(modifier.fillMaxWidth().background(c.surface).windowInsetsPadding(WindowInsets.navigationBars)) {
        Hairline(color = c.line)
        Row(
            Modifier.fillMaxWidth().height(Offlinka.size.bottomBar + 8.dp).selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            MainTab.entries.forEach { tab ->
                val on = tab == selected
                val (icon, label) = when (tab) {
                    MainTab.HOME -> OfflinkaIcons.Home to R.string.nav_home
                    MainTab.LIBRARY -> OfflinkaIcons.Library to R.string.nav_library
                    MainTab.SAVED -> OfflinkaIcons.Bookmark to R.string.nav_saved
                    MainTab.SETTINGS -> OfflinkaIcons.Settings to R.string.nav_settings
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(on, role = Role.Tab, onClick = { onSelect(tab) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.height(12.dp), contentAlignment = Alignment.BottomCenter) {
                        if (on) Peak(Modifier.size(width = 14.dp, height = 10.dp))
                    }
                    Icon(icon, null, tint = if (on) c.ink else c.ink2, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(label),
                        style = Offlinka.type.labelSmall,
                        color = if (on) c.ink else c.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }
        }
    }
}
