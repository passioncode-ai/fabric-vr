package ai.passioncode.fabricvr.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ai.passioncode.fabricvr.ArchivePicker
import ai.passioncode.fabricvr.PermissionRequester
import ai.passioncode.fabricvr.common.theme.FabricTheme
import ai.passioncode.fabricvr.common.theme.Tokens

object Routes {
    const val TODAY = "today"
    const val EDITOR = "editor/{id}"
    const val SEARCH = "search"
    const val SETTINGS = "settings"

    /** `REQ-063` / `H13`. Its own route rather than a section of Settings: it is four thousand
     *  characters of licence text and it must be scrollable without the speech block above it. */
    const val LICENCES = "licences"
    fun editor(id: String) = "editor/$id"
}

/**
 * One composable for both surfaces: the 2D panel activity and the immersive panel host the same
 * tree, which is what makes "the same notes, in the room" true rather than a second implementation.
 *
 * @param onEnterSpace present only in the panel; it reports its own failure so the panel can say
 * the space did not start instead of dying on an uncaught ActivityNotFoundException.
 */
@Composable
fun FabricApp(
    permissionRequester: PermissionRequester,
    /**
     * Choosing an exported archive to restore from (`REQ-054`). Null means this host cannot open
     * a picker, and Settings then shows no *Restore* row at all — a button that cannot open
     * anything is `D-02`'s shape. Both shipped hosts provide one; the default exists so a test
     * harness can compose the tree without inventing a picker it will not use.
     */
    archivePicker: ArchivePicker? = null,
    onEnterSpace: ((onFailure: (Throwable) -> Unit) -> Unit)? = null,
    onLeaveSpace: (() -> Unit)? = null,
    /**
     * **What the tree paints behind itself, and the two hosts differ** (`B-219`, `DEC-0085`).
     *
     * This was `Tokens.Palette.ink` at full opacity for both, which threw away everything the
     * immersive host had asked for: `Theme_FabricVR_Transparent`, `enableTransparent = true`,
     * `includeGlass = false` and a passthrough splash in the manifest, all painted over by an
     * opaque rectangle. The default is the panel's, because the 2D host is the one that should
     * NOT be transparent — it sits in the Horizon OS shell beside other windows, and a see-through
     * window there is a window whose neighbour is unreadable through it.
     */
    background: Color = Tokens.Palette.ink,
) {
    FabricTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = background) {
            val nav = rememberNavController()
            NavHost(navController = nav, startDestination = Routes.TODAY) {
                composable(Routes.TODAY) {
                    TodayScreen(
                        permissionRequester = permissionRequester,
                        onOpenNote = { id -> nav.navigate(Routes.editor(id)) },
                        onSearch = { nav.navigate(Routes.SEARCH) },
                        onSettings = { nav.navigate(Routes.SETTINGS) },
                        onEnterSpace = onEnterSpace,
                        onLeaveSpace = onLeaveSpace,
                    )
                }
                composable(
                    Routes.EDITOR,
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                ) { entry ->
                    NoteEditorScreen(
                        noteId = entry.arguments?.getString("id").orEmpty(),
                        permissionRequester = permissionRequester,
                        onBack = { nav.popBackStack() },
                        onSettings = { nav.navigate(Routes.SETTINGS) },
                    )
                }
                composable(Routes.SEARCH) {
                    SearchScreen(
                        onOpenNote = { id -> nav.navigate(Routes.editor(id)) },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onBack = { nav.popBackStack() },
                        archivePicker = archivePicker,
                        onLicences = { nav.navigate(Routes.LICENCES) },
                    )
                }
                composable(Routes.LICENCES) {
                    LicencesScreen(onBack = { nav.popBackStack() })
                }
            }
        }
    }
}
