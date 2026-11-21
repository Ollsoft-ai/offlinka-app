package cz.offlinka.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import cz.offlinka.ui.R
import cz.offlinka.ui.components.BarIconButton
import cz.offlinka.ui.components.GroupCard
import cz.offlinka.ui.components.GroupDivider
import cz.offlinka.ui.components.HeroBox
import cz.offlinka.ui.components.OfflinkaLogo
import cz.offlinka.ui.components.Ridge
import cz.offlinka.ui.components.SectionLabel
import cz.offlinka.ui.model.formatEditionDate
import cz.offlinka.ui.theme.Offlinka
import cz.offlinka.ui.theme.OfflinkaIcons

const val CONTACT_EMAIL = "info@offlinka.cz"

/** One open-source component and its licence, e.g. "Jetpack Compose" / "Apache License 2.0". */
data class Notice(val name: String, val licence: String)

data class AboutState(
    val appVersion: String,
    val coreVersion: String?,
    /** Edition of the drive content, `2026-11`, when the drive says. */
    val edition: String? = null,
    val notices: List<Notice> = emptyList(),
    val noticesOpen: Boolean = false,
)

@Composable
fun AboutScreen(state: AboutState, onBack: () -> Unit, modifier: Modifier = Modifier, onCopyEmail: (String) -> Unit = {}) {
    val c = Offlinka.colors
    val locale = currentLocale()
    var notices by rememberSaveable { mutableStateOf(state.noticesOpen) }
    Column(modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState())) {
        HeroBox(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
                Column(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) {
                    BarIconButton(OfflinkaIcons.Back, stringResource(R.string.action_back), onBack, Modifier.offset(x = (-12).dp))
                    Spacer(Modifier.height(22.dp))
                    OfflinkaLogo(Modifier.height(48.dp), onDark = true)
                    Spacer(Modifier.height(14.dp))
                    Text(stringResource(R.string.about_tagline), style = Offlinka.type.body, color = c.onHero2)
                    Spacer(Modifier.height(16.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        HeroChip(stringResource(R.string.about_version, state.appVersion))
                        state.edition?.let { HeroChip(stringResource(R.string.about_edition, formatEditionDate(it, locale))) }
                    }
                    Spacer(Modifier.height(26.dp))
                }
                Ridge()
            }
        }
        Column(Modifier.padding(horizontal = 16.dp).widthIn(max = 720.dp)) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.surfaceMuted)
                    .padding(20.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(OfflinkaIcons.Lock, null, tint = c.ink, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(stringResource(R.string.about_privacy_title), style = Offlinka.type.subhead, color = c.ink)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.about_privacy_body), style = Offlinka.type.body, color = c.ink2)
                }
            }

            SectionLabel(stringResource(R.string.about_licences_title))
            GroupCard {
                LicenceRow(OfflinkaIcons.BookWikipedia, stringResource(R.string.about_lic_wikipedia))
                GroupDivider()
                LicenceRow(OfflinkaIcons.BookMaps, stringResource(R.string.about_lic_osm))
                GroupDivider()
                LicenceRow(OfflinkaIcons.Article, stringResource(R.string.about_lic_zim))
                GroupDivider()
                LicenceRow(OfflinkaIcons.BookGames, stringResource(R.string.about_lic_games))
                GroupDivider()
                LicenceRow(OfflinkaIcons.TextSize, stringResource(R.string.about_lic_fonts))
                GroupDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClick = { notices = !notices })
                        .heightIn(min = 64.dp)
                        .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(OfflinkaIcons.Toc, null, tint = c.ink, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.about_notices), style = Offlinka.type.subhead, color = c.ink)
                        Text(stringResource(R.string.about_notices_body), style = Offlinka.type.bodySmall, color = c.ink2)
                    }
                    Icon(if (notices) OfflinkaIcons.ChevronUp else OfflinkaIcons.ChevronDown, null, tint = c.ink2)
                }
                if (notices) {
                    state.notices.forEach { n ->
                        GroupDivider()
                        Column(Modifier.fillMaxWidth().padding(start = 58.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)) {
                            Text(n.name, style = Offlinka.type.label, color = c.ink)
                            Text(n.licence, style = Offlinka.type.caption, color = c.ink2)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            GroupCard {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(OfflinkaIcons.Mail, null, tint = c.ink, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.about_contact), style = Offlinka.type.subhead, color = c.ink)
                        Text(CONTACT_EMAIL, style = Offlinka.type.body, color = c.ink2)
                    }
                    BarIconButton(OfflinkaIcons.Copy, stringResource(R.string.action_copy), { onCopyEmail(CONTACT_EMAIL) }, tint = c.ink)
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(stringResource(R.string.about_lic_app), style = Offlinka.type.bodySmall, color = c.ink2)
            state.coreVersion?.let {
                Text("offlinka-core $it", style = Offlinka.type.caption, color = c.ink3, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.about_trademark), style = Offlinka.type.caption, color = c.ink2)
            Spacer(Modifier.height(36.dp))
        }
    }
}

@Composable
private fun HeroChip(text: String) {
    val c = Offlinka.colors
    Text(
        text,
        style = Offlinka.type.label,
        color = c.onHero2,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.5.dp, c.heroLine, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun LicenceRow(icon: ImageVector, text: String) {
    val c = Offlinka.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = c.ink, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, style = Offlinka.type.body, color = c.ink)
    }
}
