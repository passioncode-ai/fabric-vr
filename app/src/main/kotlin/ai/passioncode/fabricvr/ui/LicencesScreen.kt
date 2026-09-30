package ai.passioncode.fabricvr.ui

import ai.passioncode.fabricvr.R
import ai.passioncode.fabricvr.common.Log2
import ai.passioncode.fabricvr.common.theme.Tokens
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ai.passioncode.fabricvr.common.ui.FabricTextButton

/**
 * The name the generated asset is served under. It is the root `NOTICE`, copied by
 * `app/build.gradle.kts` at build time and never edited here — see [readLicenceNotice].
 */
internal const val LICENCE_NOTICE_ASSET = "NOTICE"

/**
 * The licence notice this build actually ships, read from the asset generated from the
 * repository's `NOTICE`.
 *
 * **Why an asset and not a string resource** (`REQ-063`, audit `H13`): the MIT licence of
 * whisper.cpp requires its notice to travel with the binary, and a person holding the APK cannot
 * reach a submodule path. The obvious way to satisfy that — paste the text into `strings.xml` —
 * is the way this project keeps finding broken: the root `NOTICE` moves when a dependency moves,
 * the copy does not, and both still render. `copyLicenceNotice` in `app/build.gradle.kts` is the
 * one producer; `LicencesTest` compares the two files byte for byte, so a divergence is a red
 * test rather than a licence breach nobody notices.
 *
 * Blocking, and it says so by being a plain function: the caller moves it off the drawing thread.
 * Returns the empty string when the asset cannot be opened, which the screen renders as a
 * sentence rather than as a blank page — an empty licences screen reads as "this app uses
 * nothing", which is the one claim it must never make.
 */
internal fun readLicenceNotice(context: Context): String =
    runCatching {
        context.assets.open(LICENCE_NOTICE_ASSET).bufferedReader().use { it.readText() }
    }.getOrElse { failure ->
        Log2.e("licences.read.failed", failure)
        ""
    }

/**
 * Settings → *Licences*. Stateless, for the reason `TodayFrame` is (`DEC-0043`): the reading is
 * the part that needs a device and the rendering is the part a JVM test can measure.
 */
@Composable
internal fun LicencesFrame(notice: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Tokens.Space.l).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.m),
    ) {
        FabricTextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
        Text(stringResource(R.string.label_licences), style = MaterialTheme.typography.titleMedium)
        Text(
            notice.ifBlank { stringResource(R.string.state_licences_unavailable) },
            color = if (notice.isBlank()) Tokens.Palette.warn else Tokens.Palette.textMuted,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * The route. The read is a `produceState` on IO rather than a call in composition, because
 * opening an asset is a filesystem call and `R1` does not make an exception for a small one.
 */
@Composable
fun LicencesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val notice by produceState(initialValue = "", context) {
        value = withContext(Dispatchers.IO) { readLicenceNotice(context) }
    }
    LicencesFrame(notice = notice, onBack = onBack)
}
