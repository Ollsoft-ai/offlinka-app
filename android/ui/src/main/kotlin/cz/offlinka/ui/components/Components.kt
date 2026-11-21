package cz.offlinka.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cz.offlinka.ui.R
import cz.offlinka.ui.theme.HardShadow
import cz.offlinka.ui.theme.Offlinka
import cz.offlinka.ui.theme.OfflinkaIcons
import cz.offlinka.ui.theme.hardShadow

private val CardShape = RoundedCornerShape(8.dp)
private val GroupShape = RoundedCornerShape(12.dp)

/** The brand card: paper-white surface, ink outline, hard offset shadow. Pressing pushes it into its shadow. */
@Composable
fun InkCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    shadow: HardShadow? = Offlinka.elevation.card,
    background: Color = Offlinka.colors.surface,
    border: Color = Offlinka.colors.outline,
    borderWidth: Dp = Offlinka.size.outline,
    shape: Shape = CardShape,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press = if (pressed && onClick != null && shadow != null) 2.dp else 0.dp
    var m = modifier.offset(press, press)
    if (shadow != null) {
        m = m.hardShadow(shadow.copy(x = shadow.x - press, y = shadow.y - press), shape)
    }
    m = m
        .clip(shape)
        .background(background)
        .border(borderWidth, border, shape)
    if (onClick != null) {
        m = m.clickable(interactionSource = interaction, indication = null, onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
    }
    Column(m.padding(contentPadding), content = content)
}

/** A rounded list card with thin line border: rows inside are separated by [GroupDivider]. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, background: Color = Offlinka.colors.surface, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(GroupShape)
            .background(background)
            .border(Offlinka.size.outline, Offlinka.colors.line, GroupShape),
        content = content,
    )
}

@Composable
fun GroupDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Offlinka.colors.line))
}

/** Section label above a group ("Wikipedie", "Vzhled", "Dnes"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(top = 24.dp, bottom = 10.dp).heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = Offlinka.type.subhead,
            color = Offlinka.colors.ink2,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing()
    }
}

/** Paper screen header with a big title, as on Knihovna / Uložené / Nastavení. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
) {
    val c = Offlinka.colors
    Column(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 16.dp, end = 16.dp, top = if (onBack != null) 4.dp else 28.dp, bottom = 4.dp),
    ) {
        if (onBack != null) {
            BarIconButton(OfflinkaIcons.Back, stringResource(R.string.action_back), onBack, Modifier.offset(x = (-12).dp), tint = c.ink)
            Spacer(Modifier.height(6.dp))
        }
        Text(title, style = Offlinka.type.display, color = c.ink, modifier = Modifier.semantics { heading() })
        if (subtitle != null) {
            Spacer(Modifier.height(8.dp))
            Text(subtitle, style = Offlinka.type.body, color = c.ink2)
        }
    }
}

/** Paper app bar for the reader and search: content row + hairline. */
@Composable
fun PaperBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val c = Offlinka.colors
    Column(modifier.fillMaxWidth().background(c.bg).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    }
}

/** Segmented choice: ink outline, the selected part filled forest. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = Offlinka.colors
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shape)
            .border(Offlinka.size.outline, c.outline, shape)
            .selectableGroup(),
    ) {
        options.forEachIndexed { i, (value, label) ->
            if (i > 0) Box(Modifier.width(Offlinka.size.outline).fillMaxHeight().background(c.outline))
            val on = value == selected
            Box(
                Modifier
                    .weight(if (label.length > 10) 1.5f else 1f)
                    .heightIn(min = 52.dp)
                    .fillMaxHeight()
                    .background(if (on) c.accent else c.surface)
                    .selectable(on, role = Role.RadioButton, onClick = { onSelect(value) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = Offlinka.type.label,
                    color = if (on) c.onAccent else c.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

/** Orange primary button with the hard bottom ledge from the start page. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val c = Offlinka.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ledge = if (pressed) 2.dp else 4.dp
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .padding(bottom = 4.dp)
            .defaultMinSize(minHeight = Offlinka.size.button + 4.dp)
            .offset(y = 4.dp - ledge)
            .hardShadow(HardShadow(0.dp, ledge, if (enabled) c.primaryEdge else c.line), shape)
            .clip(shape)
            .background(if (enabled) c.primary else c.surfaceMuted)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (icon != null) Icon(icon, null, tint = c.onPrimary, modifier = Modifier.size(24.dp))
            Text(text, style = Offlinka.type.button, color = if (enabled) c.onPrimary else c.ink3)
        }
    }
}

/** Secondary button: card fill, ink outline, optional hard shadow. */
@Composable
fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onHero: Boolean = false,
    shadow: Boolean = false,
    big: Boolean = false,
) {
    val c = Offlinka.colors
    val shape = RoundedCornerShape(8.dp)
    val fg = if (onHero) c.onHero else c.ink
    var m = modifier.defaultMinSize(minHeight = if (big) Offlinka.size.button + 4.dp else Offlinka.size.touch)
    if (shadow) m = m.hardShadow(Offlinka.elevation.card, shape)
    Row(
        m
            .clip(shape)
            .background(if (onHero) Color.Transparent else c.surface)
            .border(Offlinka.size.outline, if (onHero) c.heroLine else c.outline, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(if (big) 24.dp else 20.dp))
        Text(text, style = if (big) Offlinka.type.button else Offlinka.type.label, color = fg)
    }
}

/** Underlined text link in the brand's link colour. */
@Composable
fun TextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Offlinka.colors
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = Offlinka.size.touch)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text,
            style = Offlinka.type.subhead,
            color = c.link,
            modifier = Modifier.drawBehind {
                val y = size.height + 1.dp.toPx()
                drawLine(c.linkUnderline, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 2.dp.toPx())
            },
        )
    }
}

/** A 48 dp icon button tinted for the surface it sits on. */
@Composable
fun BarIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Offlinka.colors.onHero,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(Offlinka.size.touch)
            .clip(RoundedCornerShape(24.dp))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(24.dp))
    }
}

@Composable
fun OfflinkaLogo(modifier: Modifier = Modifier, onDark: Boolean) {
    Image(
        painterResource(if (onDark) R.drawable.logo_offlinka_paper else R.drawable.logo_offlinka_ink),
        contentDescription = stringResource(R.string.cd_logo),
        modifier = modifier,
    )
}

/** Small outlined uppercase tag ("CZ", "Brzy"). */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, color: Color = Offlinka.colors.ink2, background: Color = Color.Transparent, border: Color = Offlinka.colors.line) {
    Text(
        text.uppercase(),
        style = Offlinka.type.labelSmall,
        color = color,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .border(1.5.dp, border, RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** Calm empty / error state: icon in a soft circle, title, body, optional action. */
@Composable
fun MessageState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val c = Offlinka.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(36.dp)).background(c.surfaceMuted),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = c.ink2, modifier = Modifier.size(32.dp)) }
        Spacer(Modifier.height(18.dp))
        Text(title, style = Offlinka.type.title, color = c.ink, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp))
        Text(body, style = Offlinka.type.body, color = c.ink2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** The ridge silhouette that ends every forest hero, as on the start page. */
@Composable
fun Ridge(modifier: Modifier = Modifier, height: Dp = Offlinka.size.ridge, below: Color = Offlinka.colors.bg) {
    val c = Offlinka.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                val sx = size.width / 1440f
                val sy = size.height / 120f
                fun path(points: List<Pair<Float, Float>>) = Path().apply {
                    moveTo(0f, size.height)
                    points.forEach { (x, y) -> lineTo(x * sx, y * sy) }
                    lineTo(size.width, size.height); close()
                }
                drawPath(
                    path(listOf(0f to 70f, 120f to 30f, 210f to 60f, 340f to 8f, 450f to 52f, 600f to 22f, 740f to 68f, 900f to 10f, 1030f to 50f, 1180f to 14f, 1300f to 58f, 1440f to 28f)),
                    c.heroRidge,
                )
                drawPath(
                    path(listOf(0f to 96f, 150f to 62f, 260f to 88f, 430f to 44f, 580f to 84f, 710f to 62f, 880f to 98f, 1060f to 48f, 1200f to 82f, 1330f to 58f, 1440f to 88f)),
                    below,
                )
            },
    )
}

/** Forest hero background with the contour texture. */
@Composable
fun HeroBox(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = Offlinka.colors
    val topo = painterResource(R.drawable.topo_dark)
    Box(
        modifier
            .clipToBounds()
            .background(c.hero)
            .drawBehind {
                val step = 640.dp.toPx()
                var y = -step * 0.18f
                while (y < size.height) {
                    var x = -step * 0.3f
                    while (x < size.width) {
                        translate(x, y) {
                            with(topo) { draw(androidx.compose.ui.geometry.Size(step, step), colorFilter = ColorFilter.tint(c.heroTopo)) }
                        }
                        x += step
                    }
                    y += step
                }
            },
        content = content,
    )
}

/** Book illustration from the ZIM (PNG) when present, else our icon in the book's colour box. */
@Composable
fun BookBadge(
    icon: ImageVector,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
    size: Dp = Offlinka.size.bookIconBox,
    illustration: ImageBitmap? = null,
) {
    val shape = RoundedCornerShape(if (size < 32.dp) 6.dp else 8.dp)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(background)
            .border(Offlinka.size.outline, Offlinka.colors.outline, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (illustration != null) {
            Image(BitmapPainter(illustration), null, Modifier.size(size * 0.7f))
        } else {
            Icon(icon, null, tint = foreground, modifier = Modifier.size(size * 0.58f))
        }
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = Offlinka.colors.line) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** The brand's small orange peak (logo accent), used to mark the current tab. */
@Composable
fun Peak(modifier: Modifier = Modifier, color: Color = Offlinka.colors.primary) {
    Box(
        modifier
            .size(width = 16.dp, height = 12.dp)
            .drawBehind {
                val p = Path().apply {
                    moveTo(size.width / 2, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close()
                }
                drawPath(p, color)
            },
    )
}
