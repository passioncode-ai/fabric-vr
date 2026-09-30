package ai.passioncode.fabricvr.common.ui

import ai.passioncode.fabricvr.common.theme.Tokens
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Every control a person aims at, with the one thing they all have in common applied once.
 *
 * `B-18`: Material's own defaults are sized for a fingertip on a phone held at arm's length — a
 * `TextButton` is 40 dp tall, a `FilterChip` 32. A controller ray pivots at the wrist and a pinch
 * has no surface to steady against, so `Tokens.Space.controlHeight` (72 dp) is the floor for
 * anything tappable in this product. Spelling `Modifier.heightIn(min = …)` at each call site is
 * how four of five screens ended up with at least one control that missed it, so it is spelled
 * here instead and the screens call these.
 *
 * **These are thin wrappers on purpose.** They add one constraint and forward the rest; a control
 * that cannot be styled is a control people stop using, and then the floor is lost again.
 */
@Composable
fun FabricTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = Tokens.Space.controlHeight),
    ) { content() }
}

@Composable
fun FabricOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = Tokens.Space.controlHeight),
    ) { content() }
}

@Composable
fun FabricChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SelectableChipColors = FilterChipDefaults.filterChipColors(),
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        colors = colors,
        label = { Text(label) },
        modifier = modifier.heightIn(min = Tokens.Space.controlHeight),
    )
}

/**
 * **An action that cannot be taken back, and looks like one** (`B-154`).
 *
 * `Tokens.Palette.danger` was defined when the palette was written and rendered by **nothing** —
 * it reached Material as the `error` role in `Theme.kt` and no control in the product ever wore
 * it. So *Delete* and *Delete recording* were drawn exactly like *Play* and *Transcribe again*,
 * and the only thing separating them was 8 dp of gap. In a headset the ray lands a few
 * millimetres from where it was aimed; colour is the half of that problem that spacing cannot
 * fix, because it is what tells the person which target they are about to take before they take
 * it.
 *
 * An outline rather than a filled button, deliberately: a filled red control competes with
 * *Record* — the one thing the product exists to press — for the eye of somebody scanning a
 * list, which would make the destructive action the most prominent thing on the screen. The
 * border and the label carry the colour; the surface does not.
 *
 * It keeps [FabricOutlinedButton]'s 72 dp floor, because a destructive control is the last one
 * that should be hard to aim at deliberately.
 */
@Composable
fun FabricDangerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Tokens.Palette.danger),
        border = BorderStroke(OUTLINE_WIDTH, Tokens.Palette.danger.copy(alpha = if (enabled) 1f else DISABLED_ALPHA)),
        modifier = modifier.heightIn(min = Tokens.Space.controlHeight),
    ) { content() }
}

/**
 * The filled, primary control — **the one `ControlFloorTest` was told to ignore** (`B-221`).
 *
 * That test's KDoc excluded `Button` on a stated fact: "every `Button` in this tree already
 * carries an explicit height". It was true when written and stopped being true five call sites
 * later. Settings draws *Download*, *Save* (cloud), *Save* (server) and two more at Material's
 * **40 dp** default, under Meta's 48 dp minimum and well under the 60 dp it recommends for a
 * primary action — and the scan that exists to catch exactly this could not see them, because an
 * exclusion justified by a fact outlives the fact.
 *
 * So the exclusion is gone and this is what replaces it: the same 72 dp floor every other wrapper
 * carries, in one place, so the sixth call site written next week gets it without being told.
 */
@Composable
fun FabricButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = Tokens.Space.controlHeight),
    ) { content() }
}

/**
 * **A row of controls that wraps, with a gap in BOTH directions** (`B-154`).
 *
 * Every wrapping row in this product was written as
 * `FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s))` and nothing else — and
 * a `FlowRow`'s cross-axis default is `Arrangement.Top`, which is **0 dp**. The chips are 72 dp
 * tall because `B-18` measured that a controller ray cannot hold a smaller target; ten language
 * chips wrap inside a panel, and the second line then shared an edge with the first. Two legal
 * taps with no space between them is a miss nothing reports, because both are taps.
 *
 * The same eight points in both axes: the chips are **not** irreversible — the wrong language is
 * one more tap — so what they need is a visible boundary rather than the `Tokens.Space.l` of
 * clear air [FabricDangerButton]'s neighbours get. Uniform, because a grid whose two spacings
 * differ reads as a mistake at the size a panel is drawn.
 *
 * The content is a plain `@Composable () -> Unit` rather than a `FlowRowScope` one, and that is
 * the whole reason the wrapper is worth having: `FlowRow` is `@ExperimentalLayoutApi`, so a
 * scoped lambda would drag the opt-in back out to every call site — seven screens carrying an
 * `@OptIn` for a layout they no longer name. Nothing in this product uses a `FlowRowScope`
 * member; the day something does, it takes the annotation with it deliberately.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FabricWrapRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.s),
    ) { content() }
}

/**
 * An icon-only control, sized for the toolbar.
 *
 * [Tokens.Space.iconButton] rather than [Tokens.Space.controlHeight]: a square glyph target is
 * judged by its area, and 64 × 64 is a larger target than a 72 dp-tall text button. The
 * `contentDescription` is required rather than defaulted to null — an unlabelled glyph is `B-25`.
 */
@Composable
fun FabricIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(Tokens.Space.iconButton),
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(Tokens.Space.l))
    }
}

/** Material's own outlined-button border width, spelled once so the danger variant matches it. */
private val OUTLINE_WIDTH = 1.dp

/** What Material fades a disabled outline to. Named, because a bare `0.12f` is a mystery. */
private const val DISABLED_ALPHA = 0.12f
