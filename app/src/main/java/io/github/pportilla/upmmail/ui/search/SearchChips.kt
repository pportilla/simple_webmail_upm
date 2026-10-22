package io.github.pportilla.upmmail.ui.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.ui.components.folderDisplayName
import io.github.pportilla.upmmail.ui.components.folderIcon
import io.github.pportilla.upmmail.ui.components.formatDay
import java.time.LocalDate

/**
 * The chips under the search field: the folder scope (when the screen was opened from a
 * folder), a removable chip per criterion of the search text, then the flag toggles, which are
 * on exactly when the text asks for their flag.
 */
@Composable
internal fun SearchChipsRow(
    state: SearchUiState,
    onScopeChange: (Boolean) -> Unit,
    onFlagChange: (SearchFlag, Boolean) -> Unit,
    onRemoveChip: (CriterionChip) -> Unit,
) {
    val filters = state.filters
    val scope = state.scopeFolder
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (scope != null) {
            ScopeChip(
                thisFolderOnly = filters.thisFolderOnly,
                folderName = folderDisplayName(scope),
                folderIcon = folderIcon(scope.role),
                onScopeChange = onScopeChange,
            )
        }
        state.chips.forEach { chip ->
            CriterionInputChip(chip = chip, names = state.names, onRemove = { onRemoveChip(chip) })
        }
        ToggleChip(
            selected = filters.unreadOnly,
            label = stringResource(R.string.search_filter_unread),
            icon = Icons.Outlined.MarkEmailUnread,
            onChange = { onFlagChange(SearchFlag.UNREAD, it) },
        )
        ToggleChip(
            selected = filters.starredOnly,
            label = stringResource(R.string.search_filter_starred),
            icon = Icons.Outlined.StarOutline,
            onChange = { onFlagChange(SearchFlag.STARRED, it) },
        )
        ToggleChip(
            selected = filters.withAttachments,
            label = stringResource(R.string.search_filter_attachments),
            icon = Icons.Outlined.AttachFile,
            onChange = { onFlagChange(SearchFlag.ATTACHMENT, it) },
        )
    }
}

@Composable
private fun ScopeChip(thisFolderOnly: Boolean, folderName: String, folderIcon: ImageVector, onScopeChange: (Boolean) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { menuOpen = true },
            label = {
                Text(
                    if (thisFolderOnly) {
                        stringResource(R.string.search_scope_this_folder)
                    } else {
                        stringResource(R.string.search_scope_all_folders)
                    },
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = if (thisFolderOnly) folderIcon else Icons.Outlined.FolderCopy,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            },
            trailingIcon = {
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
            },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.search_scope_this_folder_named, folderName)) },
                leadingIcon = { if (thisFolderOnly) Icon(Icons.Filled.Check, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onScopeChange(true)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.search_scope_all_folders)) },
                leadingIcon = { if (!thisFolderOnly) Icon(Icons.Filled.Check, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onScopeChange(false)
                },
            )
        }
    }
}

/** A criterion of the search text; tapping it (its close icon) removes the criterion. */
@Composable
private fun CriterionInputChip(chip: CriterionChip, names: Map<String, String>, onRemove: () -> Unit) {
    val label = criterionLabel(chip, names)
    InputChip(
        selected = true,
        onClick = onRemove,
        label = {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 240.dp),
            )
        },
        leadingIcon = {
            Icon(criterionIcon(chip), contentDescription = null, modifier = Modifier.size(InputChipDefaults.IconSize))
        },
        trailingIcon = {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.search_chip_remove, label),
                modifier = Modifier.size(InputChipDefaults.IconSize),
            )
        },
    )
}

@Composable
private fun ToggleChip(selected: Boolean, label: String, icon: ImageVector, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = selected,
        onClick = { onChange(!selected) },
        label = { Text(label) },
        leadingIcon = {
            Icon(
                imageVector = if (selected) Icons.Filled.Check else icon,
                contentDescription = null,
                modifier = Modifier.size(FilterChipDefaults.IconSize),
            )
        },
    )
}

private fun criterionIcon(chip: CriterionChip): ImageVector = when (chip) {
    is CriterionChip.Person -> if (chip.field == PersonField.FROM) Icons.Outlined.Person else Icons.Outlined.PersonOutline
    is CriterionChip.Subject -> Icons.AutoMirrored.Outlined.Subject
    is CriterionChip.Dates -> Icons.Outlined.DateRange
    is CriterionChip.Excluded -> Icons.Outlined.Block
}

/** What a criterion chip says, e.g. "From: Ana García" or "Last 30 days". */
@Composable
internal fun criterionLabel(chip: CriterionChip, names: Map<String, String>): String = when (chip) {
    is CriterionChip.Person -> stringResource(
        if (chip.field == PersonField.FROM) R.string.search_chip_from else R.string.search_chip_to,
        personLabel(chip.value, names),
    )
    is CriterionChip.Subject -> stringResource(R.string.search_chip_subject, chip.value)
    is CriterionChip.Excluded -> stringResource(R.string.search_chip_excluded, chip.value)
    is CriterionChip.Dates -> dateRangeLabel(chip.after, chip.before, chip.preset)
}

/** A date criterion: the preset's name, or its days ("1 jun 2026 – 30 jun 2026", "Since 1 jun 2026"). */
@Composable
internal fun dateRangeLabel(after: LocalDate?, before: LocalDate?, preset: DateChoice?): String = when {
    preset != null -> dateChoiceLabel(preset)
    after != null && before != null && after == before -> formatDay(after)
    after != null && before != null -> stringResource(R.string.search_chip_between, formatDay(after), formatDay(before))
    after != null -> stringResource(R.string.search_chip_since, formatDay(after))
    before != null -> stringResource(R.string.search_chip_until, formatDay(before))
    else -> dateChoiceLabel(DateChoice.ANY)
}

@Composable
internal fun dateChoiceLabel(choice: DateChoice): String = stringResource(
    when (choice) {
        DateChoice.ANY -> R.string.search_date_any
        DateChoice.LAST_7_DAYS -> R.string.search_date_last_7_days
        DateChoice.LAST_30_DAYS -> R.string.search_date_last_30_days
        DateChoice.LAST_6_MONTHS -> R.string.search_date_last_6_months
        DateChoice.LAST_YEAR -> R.string.search_date_last_year
        DateChoice.CUSTOM -> R.string.search_date_custom
    },
)
