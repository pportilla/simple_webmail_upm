package io.github.pportilla.upmmail.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.model.Folder

/**
 * "Move to…" dialog listing the selectable [folders], except [excludeFolderId] (the current one).
 */
@Composable
fun FolderPickerDialog(
    folders: List<Folder>,
    excludeFolderId: Long?,
    onDismiss: () -> Unit,
    onFolderSelected: (Folder) -> Unit,
) {
    val choices = folders.filter { it.selectable && it.id != excludeFolderId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_move)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(choices, key = { it.id }) { folder ->
                    ListItem(
                        modifier = Modifier
                            .clickable { onFolderSelected(folder) }
                            .padding(start = (folder.depth * 16).dp),
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { Icon(folderIcon(folder.role), contentDescription = null) },
                        headlineContent = { Text(folderDisplayName(folder)) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
