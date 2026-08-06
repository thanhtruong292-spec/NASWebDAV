package com.nas.naswebdav.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/**
 * Request focus when a dialog or sheet opens so the first interactive element
 * receives keyboard / TalkBack focus immediately.
 */
@Composable
fun FocusOnLaunch(focusRequester: FocusRequester) {
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.announceOnLaunch(text: String): Modifier = this.semantics {
    liveRegion = LiveRegionMode.Polite
    // contentDescription is read by TalkBack on focus; liveRegion announces on change
}.also { /* text carried via parent Text composable */ }

/** Single-line text input keyboard config without autocorrect — appropriate for filenames, URLs, IDs. */
@OptIn(ExperimentalComposeUiApi::class)
fun rememberInputKeyboard(): KeyboardOptions = KeyboardOptions(
    autoCorrect = false,
    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None
)
