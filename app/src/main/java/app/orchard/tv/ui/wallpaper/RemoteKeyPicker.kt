package app.orchard.tv.ui.wallpaper

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.orchard.tv.R
import app.orchard.tv.shortcuts.RemoteShortcut
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.home.MenuRow
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.Type

@Composable
fun RemoteKeyPicker(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    val apps = RemoteShortcut.candidates()
    val current = RemoteShortcut.selected(context)
    val expected = remember { RemoteShortcut.revision(context) }
    fun choose(key: String) {
        runCatching { RemoteShortcut.select(context, key, expected) }.onSuccess { onDismiss() }
            .onFailure { Toast.makeText(context, R.string.shortcut_failed, Toast.LENGTH_LONG).show() }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(420.dp).heightIn(max = 420.dp).liquidGlass(28.dp).padding(22.dp)) {
            BasicText(stringResource(R.string.shortcut_shopping), style = Type.Title, modifier = Modifier.padding(8.dp))
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                MenuRow(Glyph.ChevronLeft, stringResource(R.string.menu_back), onDismiss, focusRequester = first)
                MenuRow(Glyph.Power, stringResource(R.string.shortcut_none), { choose("") }, trailing = if (current.isEmpty()) "✓" else null)
                apps.forEach { app -> MenuRow(Glyph.Play, app.label, { choose(app.key) }, trailing = if (current == app.key) "✓" else null) }
            }
        }
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    }
}
