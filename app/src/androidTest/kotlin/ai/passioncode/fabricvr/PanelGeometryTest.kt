package ai.passioncode.fabricvr

import com.meta.spatial.runtime.PanelConfigOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel's texture and its size in the room must describe the same rectangle.
 *
 * Instrumented, not JVM, for a reason that is the finding itself: `PanelConfigOptions`'s defaults
 * are computed from `Build.BOARD` in a static initialiser — `eureka` is a Quest 3 — so the numbers
 * this asserts against only exist on the device. Measured there on 2026-09-21:
 * `EYEBUFFER 2064×2208`, `DEFAULT_DPI 288`, and a fresh config defaulting to **1.0 m × 0.75 m**.
 *
 * That default is the defect. The app asked for a 1024×640 dp texture — an aspect of 1.6 — and set
 * no `width` or `height` at all, so it was drawn onto the 1.333 default quad and stretched
 * vertically by twenty per cent (`B-08`). Nobody had noticed because nothing could read the
 * configuration back and no screenshot was ever taken.
 */
class PanelGeometryTest {

    private fun configured(): PanelConfigOptions =
        PanelConfigOptions().also { ImmersiveActivity.applyPanelConfig(it) }

    @Test
    fun theTextureAndTheWorldDescribeTheSameRectangle() {
        val o = configured()

        val texture = o.layoutWidthInDp / o.layoutHeightInDp
        val world = o.width / o.height

        assertEquals(
            "the texture is $texture wide-to-tall and the quad it is drawn on is $world — " +
                "the difference is the stretch a person sees",
            texture.toDouble(), world.toDouble(), 0.001,
        )
    }

    /**
     * The assertion above is the one that matters, and it is **not** made an identity by the
     * production code computing `height` from the same two numbers: the test reads the values back
     * off the object, so a config that forgets to set `width`/`height` at all — which is exactly
     * what shipped — leaves the SDK's 1.0/0.75 default and fails here.
     */
    @Test
    fun aConfigThatSetsNoWorldSizeIsNotSilentlyAccepted() {
        val untouched = PanelConfigOptions()

        assertTrue(
            "the SDK's default is no longer 1.0 x 0.75, so this test's premise needs re-measuring",
            untouched.width == 1.0f && untouched.height == 0.75f,
        )
        assertTrue(
            "the shipped configuration left the SDK default in place",
            configured().height != untouched.height || configured().width != untouched.width,
        )
    }

    @Test
    fun theTextureFitsTheEyebufferThisDeviceReports() {
        val o = configured()
        val widthPx = o.layoutWidthInDp * o.layoutDpi / 160f
        val heightPx = o.layoutHeightInDp * o.layoutDpi / 160f

        assertTrue(
            "the texture is ${widthPx}x${heightPx} px against an eyebuffer of " +
                "${PanelConfigOptions.EYEBUFFER_WIDTH}x${PanelConfigOptions.EYEBUFFER_HEIGHT} — " +
                "asking for more pixels than the device renders buys nothing and costs GPU",
            widthPx <= PanelConfigOptions.EYEBUFFER_WIDTH &&
                heightPx <= PanelConfigOptions.EYEBUFFER_HEIGHT,
        )
    }

    /**
     * Pixels per degree is deliberately **not** asserted equal. Angular size is `2·atan(s/2d)`,
     * which is not linear in `s`, so a rectangle with matching aspect still has a few per cent more
     * horizontal resolution than vertical — about four at this size. That is geometry, not a
     * defect, and a tolerance chosen to accommodate it would be a number with no meaning. What is
     * asserted is that both are in a range where text is legible at all.
     */
    @Test
    fun bothAxesCarryEnoughPixelsPerDegreeForText() {
        val o = configured()
        val ppdH = (o.layoutWidthInDp * o.layoutDpi / 160f) / degrees(o.width)
        val ppdV = (o.layoutHeightInDp * o.layoutDpi / 160f) / degrees(o.height)

        listOf("horizontal" to ppdH, "vertical" to ppdV).forEach { (axis, ppd) ->
            assertTrue("$axis resolution is $ppd pixels per degree, which is too coarse for text", ppd >= 30f)
        }
    }

    private fun degrees(metres: Float): Float =
        (2.0 * Math.toDegrees(Math.atan((metres / 2.0) / DISTANCE_M))).toFloat()

    private companion object {
        /** `ImmersiveActivity.DISTANCE_M` is private; this is the same 1.3 m, named here. */
        const val DISTANCE_M = 1.3
    }
}
