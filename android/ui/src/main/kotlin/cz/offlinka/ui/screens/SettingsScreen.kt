package cz.offlinka.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.offlinka.ui.R
import cz.offlinka.ui.components.GroupCard
import cz.offlinka.ui.components.GroupDivider
import cz.offlinka.ui.components.ScreenHeader
import cz.offlinka.ui.components.SectionLabel
import cz.offlinka.ui.components.Segmented
import cz.offlinka.ui.components.SheetOverlay
import cz.offlinka.ui.components.Tag
import cz.offlinka.ui.model.AppLanguage
import cz.offlinka.ui.model.DriveUi
import cz.offlinka.ui.model.ThemeMode
import cz.offlinka.ui.model.formatEditionDate
import cz.offlinka.ui.theme.Offlinka
import cz.offlinka.ui.theme.OfflinkaIcons

data class SettingsState(
    val language: AppLanguage = AppLanguage.SYSTEM,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val textZoom: Int = 100,
    val drives: List<DriveUi> = emptyList(),
    val saveHistory: Boolean = true,
    val hasRecentSearches: Boolean = false,
)

@Composable
fun SettingsScreen(
    state: SettingsState,
    onLanguage: (AppLanguage) -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onTextZoom: (Int) -> Unit,
    onChangeDrive: () -> Unit,
    onSaveHistory: (Boolean) -> Unit,
    onClearSearches: () -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier,
    languagePickerOpen: Boolean = false,
    onForgetDrive: (String) -> Unit = {},
) {
    val c = Offlinka.colors
    val locale = currentLocale()
    var picker by rememberSaveable { mutableStateOf(languagePickerOpen) }
    val languages = listOf(
        AppLanguage.SYSTEM to stringResource(R.string.settings_language_system),
        AppLanguage.CS to stringResource(R.string.settings_language_cs),
        AppLanguage.SK to stringResource(R.string.settings_language_sk),
        AppLanguage.EN to stringResource(R.string.settings_language_en),
    )
    Box(modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ScreenHeader(stringResource(R.string.settings_title))
            Column(Modifier.padding(horizontal = 16.dp).widthIn(max = 720.dp)) {
                SectionLabel(stringResource(R.string.settings_section_display))
                GroupCard {
                    NavRow(OfflinkaIcons.Globe, stringResource(R.string.settings_language), languages.first { it.first == state.language }.second) {
                        picker = true
                    }
                    GroupDivider()
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.settings_theme), style = Offlinka.type.subhead, color = c.ink)
                        Spacer(Modifier.height(12.dp))
                        Segmented(
                            listOf(
                                ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
                                ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
                                ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
                            ),
                            state.theme,
                            onTheme,
                        )
                    }
                    GroupDivider()
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.settings_text_size), style = Offlinka.type.subhead, color = c.ink, modifier = Modifier.weight(1f))
                            Text(stringResource(R.string.settings_text_size_value, state.textZoom), style = Offlinka.type.subhead, color = c.ink2)
                        }
                        ZoomSlider(state.textZoom, onTextZoom)
                        Text(
                            stringResource(R.string.settings_text_size_preview),
                            style = Offlinka.type.body.copy(fontSize = 17.sp * (state.textZoom / 100f), lineHeight = 25.sp * (state.textZoom / 100f)),
                            color = c.ink,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(c.surfaceMuted)
                                .padding(horizontal = 18.dp, vertical = 18.dp),
                        )
                    }
                }

                SectionLabel(stringResource(R.string.settings_section_drive))
                GroupCard {
                    if (state.drives.none { it.connected }) DriveRow(null, locale)
                    state.drives.sortedByDescending { it.connected }.forEachIndexed { i, d ->
                        if (i > 0 || state.drives.none { it.connected }) GroupDivider()
                        if (d.connected) DriveRow(d, locale) else ForgettableDrive(d) { onForgetDrive(d.id) }
                    }
                    GroupDivider()
                    NavRow(OfflinkaIcons.Folder, stringResource(R.string.settings_drive_change), null, onChangeDrive)
                    // "Kopie v telefonu" stays hidden until the feature ships (a "Brzy" row looks unfinished).
                }

                SectionLabel(stringResource(R.string.settings_section_privacy))
                GroupCard {
                    SwitchRow(OfflinkaIcons.History, stringResource(R.string.settings_save_history), state.saveHistory, onSaveHistory)
                    GroupDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = state.hasRecentSearches, role = Role.Button, onClick = onClearSearches)
                            .heightIn(min = 60.dp)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(OfflinkaIcons.Trash, null, tint = if (state.hasRecentSearches) c.danger else c.ink3, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(
                            stringResource(R.string.settings_clear_searches),
                            style = Offlinka.type.subhead,
                            color = if (state.hasRecentSearches) c.danger else c.ink3,
                        )
                    }
                }
                Row(Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
                    Icon(OfflinkaIcons.Lock, null, tint = c.ink2, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.settings_privacy_note), style = Offlinka.type.bodySmall, color = c.ink2)
                }

                Spacer(Modifier.height(24.dp))
                GroupCard {
                    NavRow(OfflinkaIcons.Info, stringResource(R.string.settings_about), null, onAbout)
                }
                Spacer(Modifier.height(28.dp))
            }
        }
        if (picker) {
            SheetOverlay(onDismiss = { picker = false }, dismissLabel = stringResource(R.string.action_close)) {
                Text(
                    stringResource(R.string.settings_language),
                    style = Offlinka.type.headline,
                    color = c.ink,
                    modifier = Modifier.padding(bottom = 14.dp).semantics { heading() },
                )
                Column(Modifier.selectableGroup()) {
                    languages.forEach { (lang, label) ->
                        RadioRow(label, lang == state.language) {
                            picker = false
                            onLanguage(lang)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavRow(icon: ImageVector, title: String, value: String?, onClick: () -> Unit) {
    val c = Offlinka.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.ink, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Offlinka.type.subhead, color = c.ink)
            if (value != null) Text(value, style = Offlinka.type.body, color = c.ink2)
        }
        Icon(OfflinkaIcons.ChevronRight, null, tint = c.ink2)
    }
}

@Composable
private fun DriveRow(drive: DriveUi?, locale: java.util.Locale) {
    val c = Offlinka.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (drive != null) OfflinkaIcons.Drive else OfflinkaIcons.DriveOff, null, tint = c.ink, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(if (drive != null) c.ok else c.ink3))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (drive != null) stringResource(R.string.settings_drive_connected, drive.name)
                    else stringResource(R.string.settings_drive_disconnected),
                    style = Offlinka.type.subhead,
                    color = c.ink,
                )
            }
            drive?.edition?.let {
                Text(stringResource(R.string.library_drive_edition, formatEditionDate(it, locale)), style = Offlinka.type.body, color = c.ink2)
            }
        }
    }
}

/** A drive the app still has a permission for but that is not plugged in: offer to forget it. */
@Composable
private fun ForgettableDrive(drive: DriveUi, onForget: () -> Unit) {
    val c = Offlinka.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(OfflinkaIcons.DriveOff, null, tint = c.ink3, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(drive.name, style = Offlinka.type.subhead, color = c.ink)
            Text(stringResource(R.string.settings_drive_disconnected), style = Offlinka.type.bodySmall, color = c.ink2)
        }
        Text(
            stringResource(R.string.settings_drive_forget),
            style = Offlinka.type.label,
            color = c.danger,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(role = Role.Button, onClick = onForget)
                .heightIn(min = 48.dp)
                .padding(horizontal = 8.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Offlinka.colors
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(icon, null, tint = c.ink, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, style = Offlinka.type.subhead, color = c.ink, modifier = Modifier.weight(1f))
        Switch(checked)
    }
}

@Composable
private fun RowScope.Switch(checked: Boolean) {
    val c = Offlinka.colors
    Box(
        Modifier
            .size(width = 52.dp, height = 30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (checked) c.accent else c.surfaceMuted)
            .border(1.5.dp, if (checked) c.accent else c.outline, RoundedCornerShape(15.dp))
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(if (checked) c.onAccent else c.surface).border(1.5.dp, c.outline, RoundedCornerShape(11.dp)))
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Offlinka.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)).border(2.dp, if (selected) c.accent else c.ink3, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(c.accent))
        }
        Spacer(Modifier.width(16.dp))
        Text(label, style = Offlinka.type.subhead, color = c.ink)
    }
}
