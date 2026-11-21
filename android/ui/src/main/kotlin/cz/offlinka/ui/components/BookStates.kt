package cz.offlinka.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import cz.offlinka.ui.R
import cz.offlinka.ui.model.BookState
import cz.offlinka.ui.model.BookUi
import cz.offlinka.ui.model.icon
import cz.offlinka.ui.theme.Offlinka
import cz.offlinka.ui.theme.OfflinkaIcons
import cz.offlinka.ui.theme.hardShadow

/** The book's icon box; a book that failed to open shows the warning icon on warnBg instead. */
@Composable
fun StatefulBadge(book: BookUi, background: Color, foreground: Color, modifier: Modifier = Modifier, size: Dp = Offlinka.size.bookIconBox) {
    val c = Offlinka.colors
    if (book.state == BookState.FAILED) {
        BookBadge(OfflinkaIcons.Warning, c.warnBg, c.warnInk, modifier, size = size)
    } else {
        BookBadge(book.icon, background, foreground, modifier, size = size)
    }
}

/**
 * The status line of a book that is not open: "Otevírám…" with a 14 dp spinner, or "Nejde otevřít.
 * Zkusit znovu?" in warnInk. Full opacity: both stay readable.
 */
@Composable
fun BookStateLine(state: BookState, style: TextStyle, modifier: Modifier = Modifier) {
    val c = Offlinka.colors
    when (state) {
        BookState.OPENING -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), color = c.ink2, strokeWidth = 2.dp, trackColor = c.line)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.library_opening), style = style, color = c.ink2)
        }
        BookState.FAILED -> Text(stringResource(R.string.library_open_failed), style = style, color = c.warnInk, modifier = modifier)
        BookState.OPEN -> Unit
    }
}

/**
 * The brand dialog (ink card, outline, hard shadow) used for the external link and errors:
 * icon, title, body, an outline action and "Zavřít".
 */
@Composable
fun BrandDialog(
    icon: ImageVector,
    title: String,
    body: String,
    primary: String,
    onPrimary: () -> Unit,
    onDismiss: () -> Unit,
    iconTint: Color = Offlinka.colors.warnInk,
) {
    val c = Offlinka.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .hardShadow(Offlinka.elevation.raised, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(c.surface)
                .border(1.5.dp, c.outline, RoundedCornerShape(16.dp))
                .padding(22.dp),
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(16.dp))
            Text(title, style = Offlinka.type.headline, color = c.ink, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Text(body, style = Offlinka.type.body, color = c.ink2)
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlineButton(primary, onPrimary, Modifier.weight(1f), icon = OfflinkaIcons.Retry, shadow = true)
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onDismiss)
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.action_close), style = Offlinka.type.subhead, color = c.ink) }
            }
        }
    }
}
