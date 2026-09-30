package ai.passioncode.fabricvr.common.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import ai.passioncode.fabricvr.common.UiAction
import ai.passioncode.fabricvr.common.R
import ai.passioncode.fabricvr.common.UiMessage
import androidx.compose.ui.res.stringResource
import ai.passioncode.fabricvr.common.theme.Tokens

/**
 * A failure, shown where it happened, with the one thing the person can do about it. Never a
 * toast that disappears: in a headset a message the user looked away from is a message never seen.
 *
 * [onAction] and [onDismiss] are separate on purpose. They used to be one lambda, so every *Retry*
 * in the product merely cleared the message — and a message with no action could not be dismissed
 * at all. The close control is therefore always present; the action button only when there is one.
 */
@Composable
fun ErrorBanner(
    message: UiMessage,
    modifier: Modifier = Modifier,
    onAction: (UiAction) -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Radius.m))
            .background(Tokens.Palette.surfaceRaised)
            .padding(start = Tokens.Space.m, top = Tokens.Space.s, bottom = Tokens.Space.s),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(message.text(), style = MaterialTheme.typography.bodyLarge, color = Tokens.Palette.warn)
        }
        // `B-18`: both of these were Material defaults — a 40 dp action and a 48 dp close — in a
        // banner that appears on all five screens. They carry the product's own floor now.
        message.action?.let { action ->
            FabricTextButton(onClick = { onAction(action) }) { Text(action.label()) }
        }
        // Rendered beside the repair, never instead of it (`T-031`). Two at most: in a headset a
        // row of four targets is a row a controller ray cannot separate.
        message.secondary?.let { action ->
            FabricTextButton(onClick = { onAction(action) }) { Text(action.label()) }
        }
        FabricIconButton(
            onClick = onDismiss,
            icon = Icons.Filled.Close,
            contentDescription = stringResource(R.string.action_dismiss),
            tint = Tokens.Palette.textMuted,
        )
    }
}

@Composable
fun UiAction.label(): String = stringResource(
    when (this) {
        // The three retries read the same on the button, and deliberately so: the person is
        // told what failed by the sentence beside it, and three different verbs for one gesture
        // would be three things to read where there is one thing to do. What they are three of
        // is what the press DOES — see `UiAction` and `REQ-047`.
        UiAction.RETRY_LOAD, UiAction.RETRY_DICTATION, UiAction.RETRY_SPACE -> R.string.action_retry
        UiAction.OPEN_SETTINGS -> R.string.action_settings
        UiAction.GRANT_PERMISSION -> R.string.action_allow
        UiAction.OPEN_APP_SETTINGS -> R.string.action_app_settings
        UiAction.DOWNLOAD_MODEL -> R.string.action_download
        // Two words, because the two cost different amounts: *Resume* asks for the megabytes
        // that are missing, *Download again* for all 574 of them.
        UiAction.RESUME_DOWNLOAD -> R.string.action_resume_download
        UiAction.DOWNLOAD_AGAIN -> R.string.action_download_again
        UiAction.WRITE_NOTE -> R.string.action_write_note
        UiAction.EXPORT_NOTES_ONLY -> R.string.action_export_notes_only
    },
)
