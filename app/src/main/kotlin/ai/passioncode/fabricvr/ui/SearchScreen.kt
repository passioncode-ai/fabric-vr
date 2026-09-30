package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.R
import ai.passioncode.fabricvr.common.UiAction
import ai.passioncode.fabricvr.common.theme.Tokens
import ai.passioncode.fabricvr.common.ui.ErrorBanner
import ai.passioncode.fabricvr.common.ui.FabricIconButton
import ai.passioncode.fabricvr.common.ui.FabricTextButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun SearchScreen(
    onOpenNote: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: SearchViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Focused on entry: the person came here to type, and a raycast to a text field costs a second.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // **`imePadding` on every screen that has a field** (`B-222`, `DEC-0087`). Horizon OS can
    // show its keyboard as a spatial overlay, where this is a no-op — and the same build runs as a
    // 2D window in the shell, where the keyboard IS an inset and the results list sat underneath
    // it. A no-op on one surface is the cheapest correct thing to do on the other.
    Column(modifier = Modifier.fillMaxSize().padding(Tokens.Space.l).imePadding()) {
        FabricTextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::query,
            label = { Text(stringResource(R.string.field_search)) },
            // The action key says what it does and does it. Search runs on every keystroke, so
            // this dismisses the keyboard rather than re-running it — which is the whole of what
            // a person pressing it wants on a screen whose results are already there.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            singleLine = true,
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    FabricIconButton(
                        onClick = viewModel::clear,
                        icon = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_clear_query),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Spacer(Modifier.height(Tokens.Space.m))

        state.message?.let { message ->
            ErrorBanner(
                message = message,
                // Exhaustive with no `else`, so a new `UiAction` cannot land here silently. This
                // screen only ever reads: every message it can produce is a storage failure, and
                // `retry()` re-runs the query — which is the honest answer to all of them rather
                // than a branch that does nothing.
                onAction = { action ->
                    when (action) {
                        UiAction.RETRY_LOAD,
                        UiAction.RETRY_DICTATION,
                        UiAction.RETRY_SPACE,
                        UiAction.OPEN_SETTINGS,
                        UiAction.OPEN_APP_SETTINGS,
                        UiAction.GRANT_PERMISSION,
                        UiAction.DOWNLOAD_MODEL,
                        UiAction.RESUME_DOWNLOAD,
                        UiAction.DOWNLOAD_AGAIN,
                        UiAction.WRITE_NOTE,
                        // Settings' export is the only producer (`B-258`); not reachable here.
                        UiAction.EXPORT_NOTES_ONLY,
                        -> viewModel.retry()
                    }
                },
                onDismiss = viewModel::dismissMessage,
            )
            Spacer(Modifier.height(Tokens.Space.m))
        }

        if (state.query.isBlank() && state.results.isNotEmpty()) {
            Text(stringResource(R.string.label_recent), color = Tokens.Palette.textMuted, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(Tokens.Space.s))
        }

        when {
            state.loading && state.results.isEmpty() -> CircularProgressIndicator()
            state.results.isNotEmpty() -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(Tokens.Space.s),
            ) {
                items(state.results, key = { it.id }) { note ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenNote(note.id) }
                            .padding(Tokens.Space.s),
                    ) {
                        Text(note.title.ifBlank { stringResource(R.string.label_untitled) }, style = MaterialTheme.typography.titleMedium)
                        val line = note.transcript?.text?.takeIf {
                            state.query.isNotBlank() && it.contains(state.query, ignoreCase = true)
                        } ?: note.preview
                        Text(line, color = Tokens.Palette.textMuted, maxLines = 2)
                    }
                }
            }
            state.searched -> Text(
                stringResource(R.string.search_no_match, state.query),
                color = Tokens.Palette.textMuted,
            )
            else -> Text(stringResource(R.string.state_nothing_written), color = Tokens.Palette.textMuted)
        }
    }
}
