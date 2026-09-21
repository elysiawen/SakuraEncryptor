package com.sakura.encryptor.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Non-cancellable progress dialog for long-running encrypt / decrypt jobs.
 *
 * @param detail e.g. `video.mp4 (2/5)`
 */
@Composable
fun TaskProgressDialog(
    title: String,
    detail: String,
    fraction: Float,
    onSendToBackground: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSendToBackground) { Text("后台运行") }
        },
    )
}
