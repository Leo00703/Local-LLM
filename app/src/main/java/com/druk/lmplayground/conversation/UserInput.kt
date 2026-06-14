package com.druk.lmplayground.conversation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R

enum class UserInputStatus {
    IDLE,
    NOT_LOADED,
    GENERATING
}

@Preview
@Composable
fun UserInputPreview() {
    UserInput(onMessageSent = {})
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserInput(
    /**
     * On tablets / wide layouts, the parent renders a permanent sidebar so the
     * chat pane only occupies part of the screen width. The original
     * tonally-elevated bottom dock then looks like a half-coloured band that
     * stops at the sidebar. Setting this to true flattens the surface so the
     * input flows into the chat pane background.
     */
    integrateWithSurface: Boolean = false,
    /**
     * On the tablet (flat-surface) path the parent toggles this true only when
     * the message list actually has content scrolled behind the input dock —
     * so the divider acts as a scroll-edge indicator rather than always-on
     * chrome. Defaults to [integrateWithSurface] for the standalone preview.
     */
    showTopDivider: Boolean = integrateWithSurface,
    modifier: Modifier = Modifier,
    /**
     * When supplied, the input dock blurs the chat content scrolling behind it
     * (frosted glass) instead of painting an opaque tonal surface. The blur
     * region spans the full dock — including the area behind the navigation bar
     * and keyboard — since the nav/ime padding lives on the inner content.
     */
    hazeState: HazeState? = null,
    hazeStyle: HazeStyle = HazeStyle.Unspecified,
    status: UserInputStatus = UserInputStatus.IDLE,
    contextUsed: Int = 0,
    contextTotal: Int = 0,
    onContextClick: (() -> Unit)? = null,
    focusRequester: FocusRequester = remember { FocusRequester() },
    supportsThinking: Boolean = false,
    thinkingEnabled: Boolean = true,
    onThinkingToggle: () -> Unit = {},
    onSwipeUp: () -> Unit = {},
    onMessageSent: (String) -> Unit,
    onCancelClicked: () -> Unit = {},
    resetScroll: () -> Unit = {},
) {

    var textState by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }

    // Used to decide if the keyboard should be shown
    var textFieldFocusState by remember { mutableStateOf(false) }

    var dragAccumulator by remember { mutableStateOf(0f) }
    val swipeThreshold = -150f // negative = upward
    val draggableState = rememberDraggableState { delta ->
        dragAccumulator += delta
    }

    val frosted = hazeState != null
    Surface(
        // Frosted: paint nothing of our own and let hazeEffect blur the chat
        // behind the whole dock. Otherwise keep the original tonal surface.
        modifier = if (frosted) {
            Modifier.fillMaxWidth().hazeEffect(hazeState!!, hazeStyle)
        } else {
            Modifier
        },
        color = if (frosted) Color.Transparent else MaterialTheme.colorScheme.surface,
        tonalElevation = if (frosted || integrateWithSurface) 0.dp else 2.dp,
        contentColor = MaterialTheme.colorScheme.secondary
    ) {
        Column(
            modifier = modifier.draggable(
                state = draggableState,
                orientation = Orientation.Vertical,
                onDragStarted = { dragAccumulator = 0f },
                onDragStopped = {
                    if (dragAccumulator < swipeThreshold) {
                        onSwipeUp()
                    }
                    dragAccumulator = 0f
                }
            )
        ) {
            // Scroll-edge divider: drawn only when the parent says the message
            // list has content hidden behind the input. At the bottom of the
            // chat we let the input float without a line.
            // Left inset (matches the top-bar divider) keeps the line from
            // butting against the master card on tablet layouts.
            if (showTopDivider) {
                androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
            UserInputText(
                status,
                contextUsed = contextUsed,
                contextTotal = contextTotal,
                onContextClick = onContextClick,
                focusRequester = focusRequester,
                supportsThinking = supportsThinking,
                thinkingEnabled = thinkingEnabled,
                onThinkingToggle = onThinkingToggle,
                textFieldValue = textState,
                onTextChanged = { textState = it },
                // Only show the keyboard if there's no input selector and text field has focus
                keyboardShown = textFieldFocusState,
                // Close extended selector if text field receives focus
                onTextFieldFocused = { focused ->
                    if (focused) {
                        resetScroll()
                    }
                    textFieldFocusState = focused
                },
                sendMessageEnabled = textState.text.isNotBlank(),
                onMessageSent = {
                    onMessageSent(textState.text)
                    // Reset text field and close keyboard
                    textState = TextFieldValue()
                    // Move scroll to bottom
                    resetScroll()
                },
                onCancelClicked = onCancelClicked,
                // On tablet landscape (integrateWithSurface) reduce the row's
                // vertical padding from 8dp → 2dp so the input dock saves ~12dp
                // of message area — vertical space is the constrained dimension
                // when the IME is open.
                compact = integrateWithSurface
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
fun Modifier.clearFocusOnKeyboardDismiss(): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }
    var keyboardAppearedSinceLastFocused by remember { mutableStateOf(false) }
    if (isFocused) {
        val imeIsVisible = WindowInsets.isImeVisible
        val focusManager = LocalFocusManager.current
        LaunchedEffect(imeIsVisible) {
            if (imeIsVisible) {
                keyboardAppearedSinceLastFocused = true
            } else if (keyboardAppearedSinceLastFocused) {
                focusManager.clearFocus()
            }
        }
    }
    onFocusEvent {
        if (isFocused != it.isFocused) {
            isFocused = it.isFocused
            if (isFocused) {
                keyboardAppearedSinceLastFocused = false
            }
        }
    }
}

val KeyboardShownKey = SemanticsPropertyKey<Boolean>("KeyboardShownKey")
var SemanticsPropertyReceiver.keyboardShownProperty by KeyboardShownKey

@ExperimentalFoundationApi
@Composable
private fun UserInputText(
    status: UserInputStatus,
    contextUsed: Int = 0,
    contextTotal: Int = 0,
    onContextClick: (() -> Unit)? = null,
    focusRequester: FocusRequester,
    supportsThinking: Boolean = false,
    thinkingEnabled: Boolean = true,
    onThinkingToggle: () -> Unit = {},
    keyboardType: KeyboardType = KeyboardType.Text,
    onTextChanged: (TextFieldValue) -> Unit,
    textFieldValue: TextFieldValue,
    keyboardShown: Boolean,
    onTextFieldFocused: (Boolean) -> Unit,
    sendMessageEnabled: Boolean,
    onMessageSent: () -> Unit,
    onCancelClicked: () -> Unit,
    compact: Boolean = false
) {
    val a11ylabel = stringResource(id = R.string.textfield_desc)
    val textStartPadding = if (supportsThinking) 4.dp else 32.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (compact) 2.dp else 8.dp)
            .heightIn(min = if (compact) 40.dp else 48.dp, max = 320.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (supportsThinking) {
            val isDisabled = status == UserInputStatus.GENERATING
            IconButton(
                onClick = onThinkingToggle,
                enabled = !isDisabled,
                modifier = Modifier.padding(start = 4.dp)
            ) {
                Icon(
                    imageVector = if (thinkingEnabled) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb,
                    contentDescription = if (thinkingEnabled) stringResource(R.string.disable_thinking) else stringResource(R.string.enable_thinking),
                    modifier = if (isDisabled) Modifier.alpha(0.8f) else Modifier,
                    tint = LocalContentColor.current
                )
            }
        }

        Box(Modifier.weight(1f)) {
            UserInputTextField(
                true,
                focusRequester,
                textFieldValue,
                onTextChanged,
                onTextFieldFocused,
                keyboardType,
                textStartPadding,
                Modifier.semantics {
                    contentDescription = a11ylabel
                    keyboardShownProperty = keyboardShown
                }
            )
        }

        // Context-window meter, sitting just left of the Send button. Hidden
        // until a model/session exists (NOT_LOADED) so it doesn't show a
        // meaningless 0% before load.
        if (status != UserInputStatus.NOT_LOADED && contextTotal > 0) {
            ContextWindowIndicator(
                used = contextUsed,
                total = contextTotal,
                onClick = onContextClick,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp)
            )
        }

        val border = if (!sendMessageEnabled) {
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        } else {
            null
        }

        val disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)

        val buttonColors = ButtonDefaults.buttonColors(
            disabledContainerColor = Color.Transparent,
            disabledContentColor = disabledContentColor
        )

        // Send button
        Box {
            when (status) {
                UserInputStatus.IDLE, UserInputStatus.NOT_LOADED -> {
                    Button(
                        modifier = Modifier.align(Alignment.Center).padding(end = 8.dp),
                        enabled = sendMessageEnabled && status == UserInputStatus.IDLE,
                        onClick = onMessageSent,
                        colors = buttonColors,
                        border = border,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            stringResource(id = R.string.send),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
                UserInputStatus.GENERATING -> {
                    Button(
                        modifier = Modifier.align(Alignment.Center).padding(end = 8.dp),
                        onClick = onCancelClicked,
                        colors = buttonColors,
                        border = border,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            stringResource(id = R.string.stop),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.UserInputTextField(
    isEnabled: Boolean,
    focusRequester: FocusRequester,
    textFieldValue: TextFieldValue,
    onTextChanged: (TextFieldValue) -> Unit,
    onTextFieldFocused: (Boolean) -> Unit,
    keyboardType: KeyboardType,
    startPadding: Dp,
    modifier: Modifier = Modifier
) {
    var lastFocusState by remember { mutableStateOf(false) }
    BasicTextField(
        value = textFieldValue,
        onValueChange = { onTextChanged(it) },
        modifier = modifier
            .padding(start = startPadding)
            .align(Alignment.CenterStart)
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .clearFocusOnKeyboardDismiss()
            .onFocusChanged { state ->
                if (lastFocusState != state.isFocused) {
                    onTextFieldFocused(state.isFocused)
                }
                lastFocusState = state.isFocused
            },
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.None
        ),
        enabled = isEnabled,
        maxLines = 4,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurfaceVariant),
        textStyle = LocalTextStyle.current.copy(color = LocalContentColor.current)
    )

    val disableContentColor =
        MaterialTheme.colorScheme.onSurfaceVariant
    if (textFieldValue.text.isEmpty()) {
        Text(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = startPadding),
            text = stringResource(R.string.textfield_hint),
            style = MaterialTheme.typography.bodyLarge.copy(color = disableContentColor)
        )
    }
}

/**
 * A compact circular gauge of how full the model's context window is
 * (used / total KV tokens), shown next to the Send button. The arc turns from
 * primary → tertiary → error as the window fills. Non-interactive.
 */
@Composable
private fun ContextWindowIndicator(
    used: Int,
    total: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val fraction = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
    val animated by animateFloatAsState(targetValue = fraction, label = "contextFraction")
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val error = MaterialTheme.colorScheme.error
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val arcColor = when {
        fraction < 0.75f -> primary
        fraction < 0.9f -> tertiary
        else -> error
    }
    Box(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(20.dp)) {
            val strokeWidth = 2.dp.toPx()
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            drawArc(
                color = arcColor,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
    }
}
