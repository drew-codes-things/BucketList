package app.bucketlist.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import bucketlist.core.BucketItem
import bucketlist.core.MAX_NOTES_LENGTH
import bucketlist.core.MAX_TITLE_LENGTH

private const val NOTES_MIN_LINES = 3
private const val NOTES_MAX_LINES = 8

@Composable
fun ItemEditor(
    item: BucketItem?,
    saving: Boolean,
    onSave: (title: String, notes: String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable(item?.id) { mutableStateOf(item?.title.orEmpty()) }
    var notes by rememberSaveable(item?.id) { mutableStateOf(item?.notes.orEmpty()) }
    var confirmingDelete by remember(item?.id) { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (item == null) titleFocus.requestFocus() }

    if (confirmingDelete && item != null && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete \"${item.title}\"?") },
            text = { Text("It comes off the list on both phones.") },
            confirmButton = {
                TextButton(enabled = !saving, onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Keep") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (item == null) "Add to the list" else "Edit") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(MAX_TITLE_LENGTH) },
                    label = { Text("What do you want to do?") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(MAX_NOTES_LENGTH) },
                    label = { Text("Notes") },
                    minLines = NOTES_MIN_LINES,
                    maxLines = NOTES_MAX_LINES,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (onDelete != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(enabled = !saving, onClick = { confirmingDelete = true }) {
                        Text("Delete from the list", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && title.isNotBlank(), onClick = { onSave(title, notes) }) {
                if (saving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Save")
            }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } },
    )
}
