package com.maslarski.iptv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.maslarski.iptv.R
import com.maslarski.iptv.ui.theme.Palette

/** D-pad friendly yes/no dialog; the cancel button receives initial focus so a stray OK never destroys data. */
@Composable
fun ConfirmDialog(
    title: String,
    body: String?,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(max = 480.dp).clip(RoundedCornerShape(24.dp)).background(Palette.SurfaceElevated).padding(32.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (body != null) {
                Spacer(Modifier.height(8.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlowButton(stringResource(R.string.action_cancel), onDismiss, primary = false, requestInitialFocus = true)
                GlowButton(confirmText, onConfirm)
            }
        }
    }
}
