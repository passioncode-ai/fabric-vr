package ai.passioncode.fabricvr

import ai.passioncode.fabricvr.common.AppError
import ai.passioncode.fabricvr.common.UiAction
import ai.passioncode.fabricvr.common.UiStateMapper
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * `D-02`. A permanently denied microphone can only be undone on the system's own page: once the
 * refusal is hard, `requestPermissions` returns immediately with no prompt, so the app cannot ask
 * again however it is worded. Until `T-009` every branch that offered *Open app settings* called
 * the same handler as *Open settings* and landed in **this app's** Settings screen, which can
 * grant nothing — voice notes, which are the product, were permanently lost by tapping *Deny*
 * twice.
 *
 * These are the two halves a JVM test can hold. The third — whether Meta's relay actually shows a
 * page — is a human observation and is on the device-gate ledger, not asserted here.
 */
@RunWith(RobolectricTestRunner::class)
class PermissionRecoveryTest {

    @Test
    fun `a hard refusal and an ordinary settings trip are different actions`() {
        val blocked = UiStateMapper.map(AppError.Permission("microphone", permanent = true))
        val ordinary = UiStateMapper.map(AppError.Permission("microphone", permanent = false))

        assertEquals(UiAction.OPEN_APP_SETTINGS, blocked.action)
        assertNotEquals(
            "a hard refusal was routed to the same place as an ordinary one, which cannot grant it",
            blocked.action,
            ordinary.action,
        )
        assertEquals(UiAction.GRANT_PERMISSION, ordinary.action)
    }

    /**
     * The **implementation**, not a restatement of its signature.
     *
     * The first version of this test declared two anonymous objects whose bodies were literally
     * `= false` and `= true` and asserted they returned `false` and `true` — a tautology that
     * could not fail against any change to the tree, and deleting the `resolveActivity` guard,
     * which is the entire point of the change, left it green. Found by an independent
     * verification pass. Robolectric resolves no activity for the intent by default, so the
     * refusal path is the one this reaches, and it is the one that matters: a headset with no
     * such page must get a sentence rather than an `ActivityNotFoundException` taking the panel
     * down.
     */
    @Test
    fun `nothing resolving the system page is reported rather than thrown`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        Shadows.shadowOf(context.packageManager).setShouldShowActivityChooser(false)

        val opened = context.startAppSettings()

        assertFalse(
            "an unresolvable intent was reported as success, so the caller says nothing and the " +
                "button silently does nothing",
            opened,
        )
    }
}
