package ai.passioncode.fabricvr.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The immersive host lets the room through and the panel host does not (`B-219`, `DEC-0085`).
 *
 * `PaletteFloorTest` asserts the two tokens differ. This asserts the **wiring**, which is where
 * the defect actually lived: every piece of the transparency was already in place — the
 * `Theme_FabricVR_Transparent` style, `enableTransparent = true`, `includeGlass = false`, and
 * `com.oculus.ossplash.background=passthrough-contextual` in the manifest — and one `Surface`
 * colour in the shared composition painted over all of it. A token nobody passes is a token that
 * does nothing.
 *
 * It is a source scan rather than a composition test for the reason `ControlFloorTest` is: the
 * next host is written by somebody reading `ImmersiveActivity.kt`, and a scan names the omission
 * instead of waiting for a screenshot nobody takes.
 */
class SpaceBackgroundTest {

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")!!)
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("no repository root above ${System.getProperty("user.dir")}")
        }
        return dir
    }

    private fun source(name: String): String =
        File(repoRoot(), "app/src/main/kotlin/ai/passioncode/fabricvr/$name").readText()

    @Test fun `the immersive host asks for the translucent background`() {
        val immersive = source("ImmersiveActivity.kt")

        assertTrue(
            "ImmersiveActivity composes FabricApp without background = Tokens.Palette.inkSpace, " +
                "so the Space paints an opaque rectangle over its own passthrough configuration",
            immersive.contains("background = Tokens.Palette.inkSpace"),
        )
    }

    @Test fun `the panel host does not`() {
        val panel = source("PanelActivity.kt")

        assertTrue(
            "PanelActivity passes a translucent background; a see-through window in the Horizon " +
                "OS shell makes whatever is behind it unreadable, and that host is not the one " +
                "over passthrough",
            !panel.contains("inkSpace"),
        )
    }

    /**
     * And the composition must keep taking the colour from its caller. Hardcoding it back is the
     * exact regression this pair exists for, and it is one word.
     */
    @Test fun `the shared composition paints what it is given`() {
        val app = File(repoRoot(), "app/src/main/kotlin/ai/passioncode/fabricvr/ui/FabricApp.kt")
            .readText()

        assertTrue(
            "FabricApp does not declare a background parameter, so both hosts get one colour",
            Regex("""background:\s*Color""").containsMatchIn(app),
        )
        assertTrue(
            "FabricApp's Surface does not paint the parameter it was given",
            // `[^)]*` does not reach past `Modifier.fillMaxSize()`'s own bracket — the first
            // version of this line failed on correct code for that reason.
            Regex("""Surface\([\s\S]{0,200}?color\s*=\s*background""").containsMatchIn(app),
        )
    }
}
