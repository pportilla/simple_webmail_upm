package io.github.pportilla.upmmail.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.pportilla.upmmail.R

/*
 * Small building blocks for settings screens, all based on Material 3 ListItem with 16 dp
 * gutters. Whole rows are the touch targets, with the right accessibility roles.
 */

/** Title of a group of settings, announced as a heading. */
@Composable
fun PreferenceSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/** A row that does something when tapped (or just shows information when [onClick] is null). */
@Composable
fun ClickablePreference(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    PreferenceRow(
        title = title,
        summary = summary,
        icon = icon,
        iconTint = iconTint,
        enabled = enabled,
        leading = leading,
        trailing = trailing,
        modifier = if (onClick != null) {
            modifier.clickable(enabled = enabled, onClick = onClick)
        } else {
            // Read title and summary together.
            modifier.semantics(mergeDescendants = true) {}
        },
    )
}

/** A row with a switch; the whole row toggles it. */
@Composable
fun SwitchPreference(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    PreferenceRow(
        title = title,
        summary = summary,
        icon = icon,
        enabled = enabled,
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
    )
}

/**
 * A row showing the current choice among [options]; tapping it opens a dialog with radio
 * buttons. Picking an option applies it and closes the dialog.
 */
@Composable
fun <T> ChoicePreference(
    title: String,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    optionIcon: ((T) -> ImageVector?)? = null,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    ClickablePreference(
        title = title,
        summary = optionLabel(selected),
        icon = icon,
        enabled = enabled,
        modifier = modifier,
        onClick = { showDialog = true },
    )
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                Column(
                    modifier = Modifier
                        .selectableGroup()
                        .verticalScroll(rememberScrollState()),
                ) {
                    options.forEach { option ->
                        val isSelected = option == selected
                        ListItem(
                            headlineContent = { Text(optionLabel(option)) },
                            leadingContent = { RadioButton(selected = isSelected, onClick = null) },
                            trailingContent = optionIcon?.invoke(option)?.let { vector ->
                                { Icon(vector, contentDescription = null) }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = isSelected,
                                    role = Role.RadioButton,
                                    onClick = {
                                        showDialog = false
                                        if (!isSelected) onSelect(option)
                                    },
                                ),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun PreferenceRow(
    title: String,
    summary: String?,
    icon: ImageVector?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    iconTint: Color? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    val colors = ListItemDefaults.colors(
        headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
        supportingColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        leadingIconColor = (iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = alpha),
    )
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        leadingContent = leading ?: icon?.let { vector -> { Icon(vector, contentDescription = null) } },
        trailingContent = trailing,
        colors = colors,
        modifier = modifier.fillMaxWidth(),
    )
}

private const val DISABLED_ALPHA = 0.38f
