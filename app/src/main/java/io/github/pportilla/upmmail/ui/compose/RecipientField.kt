package io.github.pportilla.upmmail.ui.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PermContactCalendar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.pportilla.upmmail.R
import io.github.pportilla.upmmail.model.ContactSource
import io.github.pportilla.upmmail.model.ContactSuggestion
import io.github.pportilla.upmmail.ui.components.SenderAvatar

/** Width of the "From" / "To" / "Cc" / "Bcc" labels, so the fields line up. */
internal val FieldLabelWidth = 56.dp

/** About three and a half rows: the list and the field still fit above the keyboard. */
private val SuggestionListMaxHeight = 264.dp

/**
 * A recipient list edited as chips plus a text input, with autocomplete suggestions shown right
 * under it. Stateless: the caller turns typed text into chips (see [RecipientTokenizer]).
 *
 * @param label field name ("To", "Cc"...), also its accessibility label.
 * @param recipients the committed chips.
 * @param input the text being typed.
 * @param suggestions autocomplete results for [input]; shown when not empty.
 * @param selectedIndex chip selected with Backspace, highlighted until removed.
 * @param highlightInvalid draws invalid chips filled, after a failed send attempt.
 * @param onBackspaceOnEmpty Backspace in an empty input; returns true if it was handled.
 * @param onImeAction IME action / Enter with text; returns false to let the focus move on.
 * @param onMoveNext moves the focus to the next field after IME Next on an empty input; by
 *   default the platform's next focus target.
 * @param trailing optional content at the end of the first line (e.g. the Cc/Bcc chevron).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun RecipientField(
    label: String,
    recipients: List<Recipient>,
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    suggestions: List<ContactSuggestion>,
    onSuggestionClick: (ContactSuggestion) -> Unit,
    onRemove: (Int) -> Unit,
    onEdit: (Int) -> Unit,
    onBackspaceOnEmpty: () -> Boolean,
    onImeAction: () -> Boolean,
    onFocusChanged: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    selectedIndex: Int? = null,
    highlightInvalid: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onMoveNext: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    // Keeps the field and its suggestions above the keyboard when the list opens (Cc / Bcc).
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val hasSuggestions = suggestions.isNotEmpty()
    LaunchedEffect(hasSuggestions) {
        if (hasSuggestions) {
            withFrameNanos { }
            bringIntoViewRequester.bringIntoView()
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                // A tap anywhere on the row (not on a chip) puts the caret in the input. A gesture
                // detector rather than clickable, so the row itself never takes the focus.
                .pointerInput(focusRequester) { detectTapGestures { focusRequester.tryRequestFocus() } }
                .padding(start = 16.dp, end = if (trailing != null) 4.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.width(FieldLabelWidth),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                recipients.forEachIndexed { index, recipient ->
                    RecipientChip(
                        recipient = recipient,
                        selected = index == selectedIndex || (highlightInvalid && !recipient.valid),
                        onEdit = { onEdit(index) },
                        onRemove = { onRemove(index) },
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
                RecipientInput(
                    label = label,
                    value = input,
                    onValueChange = onInputChange,
                    onBackspaceOnEmpty = onBackspaceOnEmpty,
                    onImeAction = onImeAction,
                    onFocusChanged = onFocusChanged,
                    focusRequester = focusRequester,
                    imeAction = imeAction,
                    onMoveNext = onMoveNext,
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(min = 96.dp)
                        .heightIn(min = 48.dp),
                )
            }
            trailing?.invoke()
        }
        if (suggestions.isNotEmpty()) {
            SuggestionPopupList(
                suggestions = suggestions,
                query = input.text,
                onClick = onSuggestionClick,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun RecipientInput(
    label: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onBackspaceOnEmpty: () -> Boolean,
    onImeAction: () -> Boolean,
    onFocusChanged: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    imeAction: ImeAction,
    onMoveNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    // Soft keyboards send a Backspace key event when there is nothing left to delete.
                    Key.Backspace -> value.text.isEmpty() && onBackspaceOnEmpty()
                    Key.Enter, Key.NumPadEnter -> value.text.isNotBlank() && onImeAction()
                    else -> false
                }
            }
            .semantics { contentDescription = label },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Email,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onNext = {
                if (!onImeAction()) {
                    if (onMoveNext != null) onMoveNext() else defaultKeyboardAction(ImeAction.Next)
                }
            },
            onDone = { if (!onImeAction()) defaultKeyboardAction(ImeAction.Done) },
        ),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) { inner() }
        },
    )
}

/** One recipient. Tapping it offers to edit it back into text or to remove it. */
@Composable
private fun RecipientChip(
    recipient: Recipient,
    selected: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val invalidDescription = stringResource(R.string.compose_chip_invalid_state)
    Box(modifier) {
        InputChip(
            selected = selected,
            onClick = { menuOpen = true },
            label = {
                Text(
                    text = recipient.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
            },
            avatar = {
                if (recipient.valid) {
                    SenderAvatar(
                        name = recipient.address.name,
                        email = recipient.address.address,
                        size = InputChipDefaults.AvatarSize,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(InputChipDefaults.AvatarSize),
                        tint = if (selected) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.error,
                    )
                }
            },
            colors = if (recipient.valid) {
                InputChipDefaults.inputChipColors()
            } else {
                InputChipDefaults.inputChipColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    labelColor = MaterialTheme.colorScheme.onErrorContainer,
                    selectedContainerColor = MaterialTheme.colorScheme.error,
                    selectedLabelColor = MaterialTheme.colorScheme.onError,
                )
            },
            border = InputChipDefaults.inputChipBorder(
                enabled = true,
                selected = selected,
                borderColor = if (recipient.valid) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.error,
            ),
            modifier = if (recipient.valid) Modifier else Modifier.semantics { stateDescription = invalidDescription },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            Text(
                text = if (recipient.valid) recipient.address.toString() else stringResource(R.string.compose_chip_invalid, recipient.address.address),
                style = MaterialTheme.typography.labelLarge,
                color = if (recipient.valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.compose_chip_edit)) },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.compose_chip_remove)) },
                leadingIcon = { Icon(Icons.Outlined.Close, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onRemove()
                },
            )
        }
    }
}

/** The autocomplete list, drawn as a raised card right below the field it belongs to. */
@Composable
private fun SuggestionPopupList(
    suggestions: List<ContactSuggestion>,
    query: String,
    onClick: (ContactSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlight = SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
    ) {
        Column(
            Modifier
                .heightIn(max = SuggestionListMaxHeight)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
        ) {
            suggestions.forEach { suggestion ->
                val address = suggestion.address
                val name = address.name?.takeIf { it.isNotBlank() }
                ListItem(
                    modifier = Modifier.clickable { onClick(suggestion) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { SenderAvatar(name = name, email = address.address, size = 36.dp) },
                    headlineContent = {
                        Text(
                            text = highlighted(name ?: address.address, query, highlight),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = name?.let {
                        {
                            Text(
                                text = highlighted(address.address, query, highlight),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    trailingContent = if (suggestion.source == ContactSource.DEVICE) {
                        {
                            Icon(
                                imageVector = Icons.Outlined.PermContactCalendar,
                                contentDescription = stringResource(R.string.compose_device_contact),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** [text] with the words matching [query] in [style]. */
private fun highlighted(text: String, query: String, style: SpanStyle): AnnotatedString {
    val ranges = matchRanges(text, query)
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var position = 0
        for (range in ranges) {
            if (range.first > position) append(text.substring(position, range.first))
            withStyle(style) { append(text.substring(range.first, range.last + 1)) }
            position = range.last + 1
        }
        if (position < text.length) append(text.substring(position))
    }
}

/** Requests the focus, ignoring requesters not attached yet. Returns true if it worked. */
internal fun FocusRequester.tryRequestFocus(): Boolean = try {
    requestFocus()
    true
} catch (_: IllegalStateException) {
    false
}
