package cz.offlinka.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cz.offlinka.ui.theme.Offlinka

/**
 * A bottom sheet drawn in the screen's own layout (no window), so it is deterministic in screenshots:
 * scrim over [content], card-coloured panel with rounded top and a grab handle. Tapping the scrim calls
 * [onDismiss]; the host handles the back gesture.
 */
@Composable
fun BoxScope.SheetOverlay(onDismiss: () -> Unit, dismissLabel: String, content: @Composable ColumnScope.() -> Unit) {
    val c = Offlinka.colors
    Box(
        Modifier
            .matchParentSize()
            .background(c.scrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = dismissLabel,
                onClick = onDismiss,
            ),
    )
    val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(1.5.dp, c.outline, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.line))
        }
        content()
    }
}

/** Convenience: a full-size box with [content] and, when [visible], a sheet over it. */
@Composable
fun WithSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    dismissLabel: String,
    modifier: Modifier = Modifier,
    sheet: @Composable ColumnScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        content()
        if (visible) SheetOverlay(onDismiss, dismissLabel, sheet)
    }
}
