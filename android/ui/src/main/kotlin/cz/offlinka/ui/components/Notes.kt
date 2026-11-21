package cz.offlinka.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cz.offlinka.ui.R
import cz.offlinka.ui.model.SearchNote
import cz.offlinka.ui.theme.Offlinka
import cz.offlinka.ui.theme.OfflinkaIcons

/**
 * The calm note about accent-free search: "Hledání se připravuje (40 %)… Nechte aplikaci otevřenou."
 * with a 4 dp fire bar along its bottom edge, or once "Hledání je připravené". The X only hides it.
 * [onHero] = inside the forest header of Home.
 */
@Composable
fun SearchNoteBox(note: SearchNote, onDismiss: () -> Unit, modifier: Modifier = Modifier, onHero: Boolean = false) {
    val c = Offlinka.colors
    val shape = RoundedCornerShape(8.dp)
    val fg = if (onHero) c.onHero else c.ink
    val fg2 = if (onHero) c.onHero2 else c.ink2
    val percent = note.percent
    var m = modifier.fillMaxWidth().clip(shape).background(if (onHero) c.heroDeep else c.surfaceMuted)
    if (onHero) m = m.border(1.dp, c.heroLine, shape)
    Column(m) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (percent == null && note.ready) OfflinkaIcons.Check else OfflinkaIcons.Info, null, tint = fg2, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (percent != null) {
                    Text(stringResource(R.string.search_index_building, percent), style = Offlinka.type.bodySmall, color = fg)
                    Text(stringResource(R.string.index_keep_open), style = Offlinka.type.bodySmall, color = fg2)
                } else {
                    Text(stringResource(R.string.search_ready), style = Offlinka.type.bodySmall, color = fg)
                }
            }
            BarIconButton(OfflinkaIcons.Close, stringResource(R.string.action_close), onDismiss, tint = fg2)
        }
        if (percent != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(if (onHero) c.heroLine else c.line)
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo(percent / 100f, 0f..1f) },
            ) {
                Box(Modifier.fillMaxWidth(percent.coerceIn(0, 100) / 100f).fillMaxHeight().background(c.primary))
            }
        }
    }
}
