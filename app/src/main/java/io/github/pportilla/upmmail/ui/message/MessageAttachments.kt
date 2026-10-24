package io.github.pportilla.upmmail.ui.message

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.data.AttachmentProvider
import io.github.pportilla.upmmail.data.safety.hasAdultExternalAccess
import io.github.pportilla.upmmail.model.Attachment
import io.github.pportilla.upmmail.ui.components.formatFileSize
import java.io.File
import java.util.Locale

/** Attachments of one message: tap to open, menu to share or save. */
@Composable
internal fun AttachmentList(
    attachments: List<AttachmentItemUi>,
    onOpen: (Attachment) -> Unit,
    onShare: (Attachment) -> Unit,
    onSave: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = pluralStringResource(R.plurals.message_attachment_count, attachments.size, attachments.size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        attachments.forEach { item ->
            AttachmentRow(item = item, onOpen = onOpen, onShare = onShare, onSave = onSave)
        }
    }
}

@Composable
private fun AttachmentRow(
    item: AttachmentItemUi,
    onOpen: (Attachment) -> Unit,
    onShare: (Attachment) -> Unit,
    onSave: (Attachment) -> Unit,
) {
    val attachment = item.attachment
    val accent = attachmentAccent(item.kind)
    OutlinedCard(
        onClick = { if (!item.downloading) onOpen(attachment) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = attachmentIcon(item.kind), contentDescription = null, tint = accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (item.downloading) {
                        stringResource(R.string.message_attachment_downloading)
                    } else {
                        formatFileSize(attachment.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (item.downloading) {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else {
                AttachmentMenu(
                    fileName = attachment.fileName,
                    onShare = { onShare(attachment) },
                    onSave = { onSave(attachment) },
                )
            }
        }
    }
}

@Composable
private fun AttachmentMenu(fileName: String, onShare: () -> Unit, onSave: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.message_attachment_options, fileName),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.message_attachment_share)) },
                leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                onClick = {
                    expanded = false
                    onShare()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.message_attachment_save)) },
                leadingIcon = { Icon(Icons.Outlined.SaveAlt, contentDescription = null) },
                onClick = {
                    expanded = false
                    onSave()
                },
            )
        }
    }
}

internal fun attachmentIcon(kind: AttachmentKind): ImageVector = when (kind) {
    AttachmentKind.IMAGE -> Icons.Outlined.Image
    AttachmentKind.PDF -> Icons.Outlined.PictureAsPdf
    AttachmentKind.DOCUMENT -> Icons.Outlined.Description
    AttachmentKind.SPREADSHEET -> Icons.Outlined.TableChart
    AttachmentKind.PRESENTATION -> Icons.Outlined.Slideshow
    AttachmentKind.ARCHIVE -> Icons.Outlined.FolderZip
    AttachmentKind.AUDIO -> Icons.Outlined.AudioFile
    AttachmentKind.VIDEO -> Icons.Outlined.VideoFile
    AttachmentKind.CALENDAR -> Icons.Outlined.Event
    AttachmentKind.GENERIC -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

/** Recognisable per-type colours (PDF red, sheets green…), lightened on dark backgrounds. */
@Composable
private fun attachmentAccent(kind: AttachmentKind): Color {
    val scheme = MaterialTheme.colorScheme
    val base = when (kind) {
        AttachmentKind.IMAGE -> Color(0xFF9334E6)
        AttachmentKind.PDF -> Color(0xFFD93025)
        AttachmentKind.DOCUMENT -> Color(0xFF1A73E8)
        AttachmentKind.SPREADSHEET -> Color(0xFF188038)
        AttachmentKind.PRESENTATION -> Color(0xFFE37400)
        AttachmentKind.ARCHIVE -> Color(0xFF5F6368)
        AttachmentKind.AUDIO -> Color(0xFF00897B)
        AttachmentKind.VIDEO -> Color(0xFFD81B60)
        AttachmentKind.CALENDAR -> Color(0xFF1E88E5)
        AttachmentKind.GENERIC -> return scheme.onSurfaceVariant
    }
    return if (scheme.surface.luminance() < 0.5f) lerp(base, Color.White, 0.35f) else base
}

/** MIME type to hand to other apps: the declared one, or a guess from the file name. */
internal fun effectiveMimeType(attachment: Attachment): String {
    val declared = attachment.mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
    if (declared.contains('/') && declared != "application/octet-stream") return declared
    val extension = attachment.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val guessed = extension.takeIf { it.isNotEmpty() }?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
    return guessed ?: "application/octet-stream"
}

/** Opens a downloaded attachment in another app. Returns false if no app can show it. */
internal fun Context.openAttachmentFile(file: File, attachment: Attachment): Boolean {
    val uri = attachmentUri(file) ?: return false
    val type = effectiveMimeType(attachment).let { if (it == "application/octet-stream") "*/*" else it }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, type)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}

/** Shows the share sheet for a downloaded attachment. Returns false if it could not. */
internal fun Context.shareAttachmentFile(file: File, attachment: Attachment, chooserTitle: String): Boolean {
    val uri = attachmentUri(file) ?: return false
    val send = Intent(Intent.ACTION_SEND).apply {
        type = effectiveMimeType(attachment)
        putExtra(Intent.EXTRA_STREAM, uri)
        // The chooser passes the read grant on to the chosen app through the clip data.
        clipData = ClipData.newRawUri(attachment.fileName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return try {
        startActivity(Intent.createChooser(send, chooserTitle))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

private fun Context.attachmentUri(file: File): Uri? =
    try {
        AttachmentProvider.uriFor(this, file)
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: SecurityException) {
        null
    }

/** Opens a web, phone, map or SMS link in another app. Returns false if no app handles it. */
internal fun Context.openExternalLink(uri: Uri): Boolean {
    if (!hasAdultExternalAccess()) return false
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
    val intent = Intent(Intent.ACTION_VIEW, uri)
    if (scheme == "http" || scheme == "https") intent.addCategory(Intent.CATEGORY_BROWSABLE)
    return try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
