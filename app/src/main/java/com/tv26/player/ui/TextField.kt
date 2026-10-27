package com.tv26.player.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Textfeld der App. Wie das normale OutlinedTextField, aber für die Fernbedienung:
 * Hoch/Runter verlassen das Feld (sonst bleibt der Fokus auf dem Fernseher im Feld stecken).
 * Links/Rechts bewegen weiter den Cursor. Tippen geht über die Bildschirmtastatur
 * oder über Fernbedienungs-Apps am Handy (z. B. Amazon Fire TV App).
 */
@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = false,
    minLines: Int = 1,
) {
    val focus = LocalFocusManager.current
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.onPreviewKeyEvent { e ->
            // Mehrzeilige Felder brauchen Hoch/Runter selbst
            if (!singleLine || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionUp -> { focus.moveFocus(FocusDirection.Up); true }
                Key.DirectionDown -> { focus.moveFocus(FocusDirection.Down); true }
                else -> false
            }
        },
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        isError = isError,
        supportingText = supportingText,
        visualTransformation = visualTransformation,
        // Fernseher: Tastatur erst bei OK zeigen, nicht schon beim Darüberfahren mit der Fernbedienung
        keyboardOptions = if (com.tv26.player.data.Device.isTv) keyboardOptions.copy(showKeyboardOnFocus = false) else keyboardOptions,
        singleLine = singleLine,
        minLines = minLines,
    )
}
