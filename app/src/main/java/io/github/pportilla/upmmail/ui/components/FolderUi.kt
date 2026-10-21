package io.github.pportilla.upmmail.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Report
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.model.Folder
import io.github.pportilla.upmmail.model.FolderRole

fun folderIcon(role: FolderRole): ImageVector = when (role) {
    FolderRole.INBOX -> Icons.Outlined.Inbox
    FolderRole.DRAFTS -> Icons.Outlined.Drafts
    FolderRole.SENT -> Icons.AutoMirrored.Outlined.Send
    FolderRole.ARCHIVE -> Icons.Outlined.Archive
    FolderRole.TRASH -> Icons.Outlined.Delete
    FolderRole.JUNK -> Icons.Outlined.Report
    FolderRole.OTHER -> Icons.Outlined.Folder
}

/** Localised name for well-known folders ("Enviados" vs "Sent"), the server name otherwise. */
@Composable
fun folderDisplayName(folder: Folder): String = when (folder.role) {
    FolderRole.INBOX -> stringResource(R.string.folder_inbox)
    FolderRole.DRAFTS -> stringResource(R.string.folder_drafts)
    FolderRole.SENT -> stringResource(R.string.folder_sent)
    FolderRole.ARCHIVE -> stringResource(R.string.folder_archive)
    FolderRole.TRASH -> stringResource(R.string.folder_trash)
    FolderRole.JUNK -> stringResource(R.string.folder_junk)
    FolderRole.OTHER -> folder.displayName
}
