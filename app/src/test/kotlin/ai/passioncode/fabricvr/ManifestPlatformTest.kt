package ai.passioncode.fabricvr

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Horizon OS is told about this app, asserted against the pages that say it.
 *
 * Every line below was fetched from developers.meta.com on 2026-09-21 and is quoted in the
 * assertion that needs it, because a platform rule remembered is a platform rule guessed — the
 * audit of that date found the manifest missing four of them while `:app:lintDebug` was green,
 * and lint cannot know what Meta requires (`REQ-045`, `docs/evidence/plans/2026-09-21-v3-plan.md`).
 *
 * It reads the **source** manifest as text rather than the merged one, for `PanelSizeTest`'s
 * reason: the failure this catches is a line somebody deleted from this file, and the merged
 * manifest exists only after a build that the CI budget currently cannot run.
 */
class ManifestPlatformTest {

    private val manifest: String by lazy {
        val file = File("src/main/AndroidManifest.xml")
        assertTrue("the manifest is not where this test looks: ${file.absolutePath}", file.isFile)
        file.readText()
    }

    private fun assertDeclares(what: String, why: String) =
        assertTrue("$why\nthe manifest does not declare: $what", manifest.contains(what))

    /**
     * *Application Manifests for Release Builds* (`resources/publish-mobile-manifest`, updated
     * 2026-08-31): "`installLocation` must be set to `auto`". Meta checks the manifest at upload,
     * so a missing attribute is a rejected build rather than a broken one.
     */
    @Test fun `the app installs where Horizon OS decides`() =
        assertDeclares(
            """android:installLocation="auto"""",
            "Meta's release-manifest page requires it and the upload validator reads it.",
        )

    /**
     * Same page: a 2D app either omits head tracking or declares it `required="false"`, and either
     * way carries `android:version="1"`. The version was the missing half — this app declared the
     * feature without it since the first commit.
     */
    @Test fun `head tracking carries its version`() =
        assertDeclares(
            """android:name="android.hardware.vr.headtracking"""" +
                """ android:required="false" android:version="1"""",
            "Meta's release-manifest page pairs the feature with android:version=\"1\".",
        )

    /**
     * `ImmersiveActivity.onSceneReady` calls `scene.enablePassthrough(true)`, so the Space always
     * opens into passthrough. Meta's Spatial SDK template declares the feature `required="false"`,
     * and VRC.Quest.Functional.14 asks an app that launches into passthrough to say so in its
     * loading screen — *Passthrough Loading Screen*: `black` "is the default if the meta-data node
     * is not present" and is "not recommended for MR apps".
     */
    @Test fun `passthrough is declared and its loading screen is not black`() {
        assertDeclares(
            """android:name="com.oculus.feature.PASSTHROUGH"""",
            "The Space enables passthrough; Meta's template declares the feature.",
        )
        assertDeclares(
            """android:name="com.oculus.ossplash.background" android:value="passthrough-contextual"""",
            "VRC.Quest.Functional.14 — the default black splash is 'not recommended for MR apps'.",
        )
    }

    /**
     * The note editor needs text input inside the Space, and `B-050` is the trap that opens when
     * it arrives. Meta's `HybridSample` manifest declares both keyboard features `required="false"`;
     * whether either is *needed* for the system keyboard to attach to a Spatial SDK Compose panel
     * is not stated on any page fetched — so this declares what the template declares and the
     * device row stays open.
     */
    @Test fun `both keyboard features are declared as optional`() {
        assertDeclares(
            """android:name="com.oculus.feature.VIRTUAL_KEYBOARD"""",
            "Meta's HybridSample declares it; the Space has text fields.",
        )
        assertDeclares(
            """android:name="oculus.software.overlay_keyboard"""",
            "Meta's HybridSample declares it beside VIRTUAL_KEYBOARD.",
        )
    }

    /**
     * `DEC-0067`: the hand-tracking declaration is not idle — without it the Spatial SDK runtime
     * enumerates no hand devices at all (*Enable Hand Tracking*). Meta's template pairs the feature
     * with its version, and this app shipped the feature without the version.
     */
    @Test fun `hand tracking names the version it speaks`() =
        assertDeclares(
            """android:name="com.oculus.handtracking.version" android:value="V2.0"""",
            "Meta's Spatial SDK template pairs the feature with V2.0.",
        )

    /**
     * VRC.Quest.Packaging.4: "your application must provide a valid network security
     * configuration in your application manifest." The page defines neither *valid* nor any
     * position on cleartext, and a `domain-config` cannot express an RFC1918 range — which is why
     * `DEC-0005` left the attribute off entirely. The file now exists with a permissive
     * base-config, so the check has something to read, and `NetworkPolicy.requireReachable`
     * remains the only thing that actually decides where cleartext may go.
     */
    @Test fun `a network security configuration exists and is referenced`() {
        assertDeclares(
            """android:networkSecurityConfig="@xml/network_security_config"""",
            "VRC.Quest.Packaging.4 requires the manifest to provide one.",
        )
        val config = File("src/main/res/xml/network_security_config.xml")
        assertTrue("the referenced config is missing: ${config.absolutePath}", config.isFile)
    }

    /**
     * `DEC-0065`, closing `B-098`. The floor is the product's positioning rather than the code's:
     * 81 is the Horizon OS version Meta Virtual Display needs, and this app exists to sit beside
     * it. *Requiring Minimum OS Versions* names the element and its two attributes.
     */
    @Test fun `the Horizon OS floor is the one Virtual Display needs`() =
        assertDeclares(
            """horizonos:minSdkVersion="81" horizonos:targetSdkVersion="81"""",
            "DEC-0065 — below v81 there is no Meta Virtual Display to sit beside.",
        )

    /**
     * **No backup, said the way Android 12+ reads it** (`B-104`). `allowBackup="false"` is
     * deprecated for `dataExtractionRules` on API 31+, and lint flagged it; the policy is unchanged
     * — nothing leaves the headset except by *Export the vault*, and the Keystore-wrapped settings
     * could not be restored on another device anyway. The rules file must exclude everything from
     * both cloud backup and device transfer, or declaring it would widen what the old flag refused.
     */
    @Test fun `no backup is declared through data extraction rules that exclude everything`() {
        assertDeclares(
            "android:dataExtractionRules=\"@xml/data_extraction_rules\"",
            "Android 12+ reads extraction rules, not allowBackup, and lint flags the gap (B-104).",
        )
        val rules = File("src/main/res/xml/data_extraction_rules.xml")
        assertTrue("the rules file is missing: ${rules.absolutePath}", rules.isFile)
        val xml = rules.readText()
        listOf("cloud-backup", "device-transfer").forEach { section ->
            val block = Regex("<$section[^>]*>([\\s\\S]*?)</$section>").find(xml)?.groupValues?.get(1)
            assertTrue("$section is not declared, so it falls back to what the platform chooses", block != null)
            listOf("root", "file", "database", "sharedpref", "external").forEach { domain ->
                assertTrue("$section does not exclude $domain", block!!.contains("<exclude domain=\"$domain\""))
            }
        }
    }
}
