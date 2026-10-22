package io.github.pportilla.upmmail.ui.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.model.ContactSuggestion
import io.github.pportilla.upmmail.ui.components.SenderAvatar
import java.time.LocalDate

/** What the advanced search panel asks of the view model. */
internal class AdvancedSearchActions(
    /** Applies a change to the current fields. */
    val onChange: ((AdvancedSearchForm) -> AdvancedSearchForm) -> Unit,
    val onPersonInput: (PersonField, String) -> Unit,
    val onPickSuggestion: (PersonField, ContactSuggestion) -> Unit,
    val onCommitPerson: (PersonField) -> Unit,
    val onRemovePerson: (PersonField, Int) -> Unit,
    val onSearch: () -> Unit,
    val onClear: () -> Unit,
    val onClose: () -> Unit,
)

/**
 * The advanced search panel: a full-screen form over the search screen, edge to edge, with its
 * Clear and Search buttons kept above the keyboard and the fields scrolling on small screens.
 * [form] starts as the criteria of the search text; Search writes it back to the text.
 *
 * @param names contact names of the chosen addresses, by [nameKey].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdvancedSearchPanel(
    form: AdvancedSearchForm,
    suggestions: PersonSuggestions?,
    names: Map<String, String>,
    actions: AdvancedSearchActions,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    var pickingDates by rememberSaveable { mutableStateOf(false) }
    val search = {
        focusManager.clearFocus()
        actions.onSearch()
    }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            TopAppBar(
                title = { Text(stringResource(R.string.search_advanced)) },
                navigationIcon = {
                    IconButton(onClick = actions.onClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PersonSection(PersonField.FROM, form, suggestions, names, actions)
                PersonSection(PersonField.TO, form, suggestions, names, actions)
                PanelTextField(
                    value = form.subject,
                    onValueChange = { actions.onChange { current -> current.copy(subject = it) } },
                    label = stringResource(R.string.search_advanced_subject),
                    icon = Icons.AutoMirrored.Outlined.Subject,
                )
                PanelTextField(
                    value = form.words,
                    onValueChange = { actions.onChange { current -> current.copy(words = it) } },
                    label = stringResource(R.string.search_advanced_words),
                    icon = Icons.Outlined.TextFields,
                )
                PanelTextField(
                    value = form.excluded,
                    onValueChange = { actions.onChange { current -> current.copy(excluded = it) } },
                    label = stringResource(R.string.search_advanced_excluded),
                    icon = Icons.Outlined.Block,
                    imeAction = ImeAction.Search,
                    onSearch = search,
                )
                DateSection(
                    form = form,
                    onChoose = { choice -> actions.onChange { it.copy(date = choice, customAfter = null, customBefore = null) } },
                    onPickCustom = {
                        focusManager.clearFocus()
                        pickingDates = true
                    },
                )
                Column {
                    SwitchRow(
                        label = stringResource(R.string.search_filter_attachments),
                        icon = Icons.Outlined.AttachFile,
                        checked = form.hasAttachment,
                        onChange = { actions.onChange { current -> current.copy(hasAttachment = it) } },
                    )
                    SwitchRow(
                        label = stringResource(R.string.search_filter_unread),
                        icon = Icons.Outlined.MarkEmailUnread,
                        checked = form.unread,
                        onChange = { actions.onChange { current -> current.copy(unread = it) } },
                    )
                    SwitchRow(
                        label = stringResource(R.string.search_filter_starred),
                        icon = Icons.Outlined.StarOutline,
                        checked = form.starred,
                        onChange = { actions.onChange { current -> current.copy(starred = it) } },
                    )
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = actions.onClear, enabled = !form.isEmpty) {
                    Text(stringResource(R.string.search_advanced_clear))
                }
                Button(onClick = search) {
                    Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.search_advanced_search))
                }
            }
        }
    }
    if (pickingDates) {
        DateRangeDialog(
            after = form.customAfter,
            before = form.customBefore,
            onDismiss = { pickingDates = false },
            onConfirm = { after, before ->
                pickingDates = false
                actions.onChange { it.copy(date = DateChoice.CUSTOM, customAfter = after, customBefore = before) }
            },
        )
    }
}

/**
 * Senders or recipients: the chosen ones as chips, then a field whose text suggests contacts.
 * Picking one adds its address; typed text counts as it is (a name or part of an address).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun PersonSection(
    field: PersonField,
    form: AdvancedSearchForm,
    suggestions: PersonSuggestions?,
    names: Map<String, String>,
    actions: AdvancedSearchActions,
) {
    val values = form.people(field)
    val input = form.input(field)
    var focused by remember { mutableStateOf(false) }
    val shown = suggestions?.takeIf { it.field == field && focused && input.isNotBlank() }?.items.orEmpty()
    // Keeps the suggestions above the keyboard when they appear.
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val hasSuggestions = shown.isNotEmpty()
    LaunchedEffect(hasSuggestions) {
        if (hasSuggestions) {
            withFrameNanos { }
            bringIntoViewRequester.bringIntoView()
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .bringIntoViewRequester(bringIntoViewRequester),
    ) {
        if (values.isNotEmpty()) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                values.forEachIndexed { index, value ->
                    val label = personLabel(value, names)
                    InputChip(
                        selected = false,
                        onClick = { actions.onRemovePerson(field, index) },
                        label = {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp))
                        },
                        avatar = { SenderAvatar(name = names[nameKey(value)], email = value, size = InputChipDefaults.AvatarSize) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.search_chip_remove, label),
                                modifier = Modifier.size(InputChipDefaults.IconSize),
                            )
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = { actions.onPersonInput(field, it) },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    // A hardware Enter adds the typed value and stays in the field for another one.
                    val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                    if (event.type == KeyEventType.KeyDown && enter && input.isNotBlank()) {
                        actions.onCommitPerson(field)
                        true
                    } else {
                        false
                    }
                },
            label = {
                Text(stringResource(if (field == PersonField.FROM) R.string.search_advanced_from else R.string.search_advanced_to))
            },
            placeholder = { Text(stringResource(R.string.search_advanced_person_hint)) },
            leadingIcon = { Icon(if (field == PersonField.FROM) Icons.Outlined.Person else Icons.Outlined.PersonOutline, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(
                onNext = {
                    actions.onCommitPerson(field)
                    defaultKeyboardAction(ImeAction.Next)
                },
            ),
        )
        if (hasSuggestions) {
            SuggestionList(items = shown, onPick = { actions.onPickSuggestion(field, it) })
        }
    }
}

/** Contacts matching the typed text, right under the field. */
@Composable
private fun SuggestionList(items: List<ContactSuggestion>, onPick: (ContactSuggestion) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            items.forEach { suggestion ->
                val address = suggestion.address
                val name = address.name?.takeIf { it.isNotBlank() }
                ListItem(
                    modifier = Modifier.clickable { onPick(suggestion) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { SenderAvatar(name = name, email = address.address, size = 36.dp) },
                    headlineContent = { Text(name ?: address.address, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = name?.let {
                        { Text(address.address, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    },
                )
            }
        }
    }
}

@Composable
private fun PanelTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    imeAction: ImeAction = ImeAction.Next,
    onSearch: () -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )
}

/** "Any time", the presets, and a custom range picked in a calendar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DateSection(form: AdvancedSearchForm, onChoose: (DateChoice) -> Unit, onPickCustom: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.search_advanced_date),
            modifier = Modifier
                .padding(top = 4.dp, bottom = 4.dp)
                .semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf(DateChoice.ANY) + DATE_PRESETS).forEach { choice ->
                FilterChip(
                    selected = form.date == choice,
                    onClick = { onChoose(choice) },
                    label = { Text(dateChoiceLabel(choice)) },
                )
            }
            val custom = form.date == DateChoice.CUSTOM && (form.customAfter != null || form.customBefore != null)
            FilterChip(
                selected = custom,
                onClick = onPickCustom,
                label = {
                    Text(if (custom) dateRangeLabel(form.customAfter, form.customBefore, null) else dateChoiceLabel(DateChoice.CUSTOM))
                },
                leadingIcon = {
                    Icon(Icons.Outlined.DateRange, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                },
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, icon: ImageVector, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

/** A calendar to pick the first and last day; a first day alone means "since". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDialog(
    after: LocalDate?,
    before: LocalDate?,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalDate?) -> Unit,
) {
    val years = DatePickerDefaults.YearRange
    val initial = remember(after, before) { pickerStart(after, before, years) }
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial.start,
        initialSelectedEndDateMillis = initial.end,
        initialDisplayedMonthMillis = initial.shownMonth,
        yearRange = years,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val start = state.selectedStartDateMillis
            TextButton(
                onClick = { if (start != null) onConfirm(pickerDate(start), state.selectedEndDateMillis?.let(::pickerDate)) },
                enabled = start != null,
            ) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    ) {
        DateRangePicker(state = state, modifier = Modifier.weight(1f))
    }
}
