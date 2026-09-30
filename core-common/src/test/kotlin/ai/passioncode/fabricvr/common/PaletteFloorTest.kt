package ai.passioncode.fabricvr.common

import ai.passioncode.fabricvr.common.theme.Tokens
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The palette is read on a Quest display, not on a monitor, and the display has a floor (`B-220`).
 *
 * Meta, *Color* (`horizon-os/design/color`): "LCD limitations prevent them from meaningfully
 * differentiating brightness levels **below 13 out of 255**." Every channel under that floor is
 * the same tone to a person wearing the headset, whatever the hex says.
 *
 * `Palette.ink` was `#0B0E12` — red **11** — so the background, pure black and `surface #141922`
 * compressed toward one tone on the device and the dark theme's depth was thinner than it looked
 * in a design tool. Nobody had measured it, because a colour looks right on the machine it is
 * chosen on.
 *
 * **This is a floor, not a target.** A token may be as dark as 13; darker is a value the hardware
 * throws away, and a palette that pretends otherwise is a palette whose separations are fiction.
 */
class PaletteFloorTest {

    private val floor = 13

    /**
     * WCAG relative luminance, which is what Meta's contrast numbers are computed from.
     * `((c/255 + 0.055) / 1.055) ^ 2.4` per channel below the linear knee, weighted 0.2126 /
     * 0.7152 / 0.0722.
     */
    private fun luminance(c: Color): Double {
        val (r, g, b) = channels(c)
        fun lin(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun channels(c: Color): Triple<Int, Int, Int> {
        val argb = c.value.toLong() ushr 32
        return Triple(
            ((argb shr 16) and 0xFF).toInt(),
            ((argb shr 8) and 0xFF).toInt(),
            (argb and 0xFF).toInt(),
        )
    }

    private val opaqueTokens: Map<String, Color> = mapOf(
        "ink" to Tokens.Palette.ink,
        "surface" to Tokens.Palette.surface,
        "surfaceRaised" to Tokens.Palette.surfaceRaised,
        "line" to Tokens.Palette.line,
        "text" to Tokens.Palette.text,
        "textMuted" to Tokens.Palette.textMuted,
        "accent" to Tokens.Palette.accent,
        "accentInk" to Tokens.Palette.accentInk,
        "warn" to Tokens.Palette.warn,
        "danger" to Tokens.Palette.danger,
        "recording" to Tokens.Palette.recording,
    )

    @Test fun `no palette channel sits below the display's usable floor`() {
        val offenders = opaqueTokens.mapNotNull { (name, colour) ->
            val (r, g, b) = channels(colour)
            val low = listOfNotNull(
                "R=$r".takeIf { r < floor },
                "G=$g".takeIf { g < floor },
                "B=$b".takeIf { b < floor },
            )
            if (low.isEmpty()) null else "$name ${low.joinToString(", ")}"
        }

        assertTrue(
            "these tokens have a channel below Meta's floor of $floor/255, where the display " +
                "cannot differentiate brightness at all: ${offenders.joinToString("; ")}",
            offenders.isEmpty(),
        )
    }

    /**
     * And the floor is worth nothing if the two darkest tokens land on the same tone anyway. The
     * background and the card sitting on it are the pair the whole dark theme rests on.
     */
    @Test fun `the background and the surface above it are separable on the device`() {
        val (ir, ig, ib) = channels(Tokens.Palette.ink)
        val (sr, sg, sb) = channels(Tokens.Palette.surface)
        val separation = maxOf(sr - ir, sg - ig, sb - ib)

        assertTrue(
            "ink and surface differ by only $separation brightness levels in their widest " +
                "channel; below 5 they read as one tone through the headset",
            separation >= 5,
        )
    }

    /**
     * **A control's boundary is a UI component, and Meta asks for 3:1** (`B-224`, `DEC-0086`).
     *
     * `Theme.kt` maps `outline` to `Palette.line`, and that single token is the only edge on every
     * `FabricOutlinedButton` and all six `OutlinedTextField`s in the product. It was `#2A3340`,
     * which is **1.38:1** against `surface` — a boundary a person in a headset cannot see, on the
     * controls whose whole affordance is the boundary. `FabricDangerButton` overrides it at 5.73:1
     * and is the proof the rest could.
     *
     * Measured against `surface` rather than `ink`, because an outlined control sits on a card far
     * more often than on the background, and the card is the harder of the two.
     */
    @Test fun `a control's boundary is visible against the surface it sits on`() {
        val ratio = contrast(Tokens.Palette.line, Tokens.Palette.surface)

        assertTrue(
            "Palette.line is %.2f:1 against Palette.surface; Meta asks for 3:1 on a UI component's "
                .format(ratio) + "boundary, and this token is the only edge every outlined control has",
            ratio >= 3.0,
        )
    }

    /**
     * **The Space host paints a translucent background and the panel host does not** (`B-219`,
     * `DEC-0085`). The immersive panel is configured transparent — `Theme_FabricVR_Transparent`,
     * `enableTransparent = true`, `includeGlass = false` — and the manifest asks the shell for a
     * passthrough splash; painting a fully opaque surface over that throws all of it away and
     * makes the Space a black rectangle floating in the room.
     */
    @Test fun `the space background lets the room through and the panel background does not`() {
        assertTrue(
            "Palette.ink is the 2D panel's background and must be fully opaque; it is " +
                "${Tokens.Palette.ink.alpha}",
            Tokens.Palette.ink.alpha == 1f,
        )
        assertTrue(
            "Palette.inkSpace must let passthrough through; alpha is ${Tokens.Palette.inkSpace.alpha}",
            Tokens.Palette.inkSpace.alpha < 1f,
        )
        assertTrue(
            "Palette.inkSpace must stay legible over a bright room; alpha is " +
                "${Tokens.Palette.inkSpace.alpha}, and below 0.85 a white wall lifts the composite " +
                "background far enough to cost the text its contrast",
            Tokens.Palette.inkSpace.alpha >= 0.85f,
        )
    }
}
