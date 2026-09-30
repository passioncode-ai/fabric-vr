package ai.passioncode.fabricvr.common.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The sheleg-design *workbench* pack, as tokens rather than values sprinkled through the screens.
 * Dark-first: the panel floats over passthrough, and a bright surface in a headset is a lamp.
 * Figma is off for v1, so this file is the source of truth for every colour and step.
 */
object Tokens {
    object Palette {
        /**
         * The 2D panel's background, and **the darkest tone this display can actually render**
         * (`B-220`). It was `#0B0E12` — red 11 — and Meta is explicit: "LCD limitations prevent
         * them from meaningfully differentiating brightness levels below 13 out of 255", so the
         * background, pure black and [surface] compressed toward one tone in the headset while
         * looking like three on a monitor. Every channel is at or above 13 now, and `PaletteFloorTest`
         * keeps it that way for the whole palette rather than for this one token.
         */
        val ink = Color(0xFF0D1116)

        /**
         * The **immersive** background: the same tone, letting the room through (`B-219`,
         * `DEC-0085`).
         *
         * The Space's panel is configured transparent — `Theme_FabricVR_Transparent`,
         * `enableTransparent = true`, `includeGlass = false` — and the manifest asks the shell for a
         * passthrough splash, and then the composition painted [ink] at full opacity over all of it.
         * A Space that declares transparency and throws it away is a black rectangle floating in
         * somebody's room, which is the one thing Meta's mixed-reality guidance asks an app not to be.
         *
         * **Why 0.92 and not less.** The floor is legibility over a room nobody controls. Worst case
         * is a white wall: the composite background becomes about `0.92 x 13 + 0.08 x 255 = 32` in
         * red, and [text] at 232 still reads against it at better than 12:1 — far above the 4.5:1
         * a person needs. At 0.80 the same wall lifts it past 60 and the margin starts to matter.
         * **The exact value is a device question and is on the board for the headset session**
         * (`DEC-0066` defers it): what this number is not allowed to be is 1.0, silently.
         */
        val inkSpace = ink.copy(alpha = 0.92f)
        val surface = Color(0xFF141922)
        val surfaceRaised = Color(0xFF1C232E)
        /**
         * The edge of a control, and **the only edge most of them have** (`B-224`, `DEC-0086`).
         *
         * `Theme.kt` maps Material's `outline` to this one token, so it draws the border of every
         * `FabricOutlinedButton` and all six `OutlinedTextField`s. It was `#2A3340` — **1.38:1**
         * against [surface], where Meta asks for **3:1** on a UI component's boundary. A control
         * whose whole affordance is its outline, with an outline nobody can see, is a control that
         * reads as text. `FabricDangerButton` overrides it at 5.73:1 and was the proof the rest
         * could.
         *
         * `#5C6A7E` measures **3.15:1** on [surface], keeping the palette's blue cast rather than
         * going neutral. `PaletteFloorTest` computes it from the sRGB formula rather than trusting
         * this comment.
         */
        val line = Color(0xFF5C6A7E)
        val text = Color(0xFFE8EDF4)
        val textMuted = Color(0xFF93A1B3)
        val accent = Color(0xFF6FD3C7)
        /**
         * The text that sits ON [accent]. Red was **5** and the floor applies to a foreground as
         * much as to a background: below 13 the display renders 5 and 13 as the same tone, so the
         * value was describing a distinction the hardware throws away (`B-220`).
         */
        val accentInk = Color(0xFF0D201D)
        val warn = Color(0xFFE2B457)
        val danger = Color(0xFFE0736B)
        val recording = Color(0xFFE0736B)
    }

    object Space {
        val xs = 4.dp
        val s = 8.dp
        val m = 16.dp
        val l = 24.dp
        val xl = 32.dp

        /**
         * Control sizes for a panel aimed at with a controller ray or a pinch from a metre away.
         *
         * The 48dp minimum touch target is a floor for a phone held at arm's length. A ray pivots
         * at the wrist and a pinch has no surface to steady against, so everything here is far
         * above it: [captureButtonHeight] for the one thing the product exists to do, [controlHeight]
         * for anything else a person taps, [iconButton] for the toolbar.
         */
        /**
         * Renamed from `holdButtonHeight` by `T-045`: `DEC-0010` deleted hold-to-talk and the
         * button became tap-to-start / tap-to-stop, but the name kept describing the gesture for
         * eleven commits. **A name is documentation the compiler checks**, and it was the only
         * piece of this product's documentation still asserting a gesture that does not exist.
         */
        val captureButtonHeight = 128.dp
        val controlHeight = 72.dp
        val iconButton = 64.dp

        /**
         * The height reserved above the record button for whatever the voice flow has to say.
         *
         * **It is a reservation, not a measurement of the current state.** The button is the one
         * thing a person aims a controller ray at for the length of a thought, and `B-13` is that
         * it moved twice per dictation because the message above it was taller in one state than
         * another. A fixed box sized for the TALLEST state means the target never moves.
         *
         * Derived from the download state, which is the tallest: a line of text (~30) + 8 + a
         * `LinearProgressIndicator` (4, with its own padding ~16) + 8 + a [controlHeight] Cancel
         * button (72) = 130.
         *
         * **Adding a taller state means raising this number**, and the test
         * `the record button does not move between states` fails if you do not.
         */
        val statusSlot = 130.dp

        /**
         * Copy, standing alone on the right of a note. It is wide and tall because it is the
         * action taken most often and the one whose neighbours must never be hit instead.
         */
        val copyButtonWidth = 148.dp
        val copyButtonHeight = 96.dp
    }

    object Radius {
        val s = 8.dp
        val m = 14.dp
        val l = 22.dp
    }

    /**
     * A panel sits about a metre away, so the type is a step larger than a phone's. The smallest
     * size here is the smallest one that stayed readable at 288 dpi on the panel.
     */
    object Type {
        val titleSize = 28.sp
        val headingSize = 20.sp
        val bodySize = 17.sp
        val labelSize = 14.sp
    }

    /** Motion degrades to rest: nothing in a headset should keep moving after it has arrived. */
    object Motion {
        const val quickMs = 120
        const val calmMs = 240
    }
}
