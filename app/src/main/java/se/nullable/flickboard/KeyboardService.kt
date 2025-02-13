package se.nullable.flickboard

import android.content.ComponentName
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import se.nullable.flickboard.model.Action
import se.nullable.flickboard.model.ModifierState
import se.nullable.flickboard.model.SearchDirection
import se.nullable.flickboard.model.ShiftState
import se.nullable.flickboard.model.TextBoundary
import se.nullable.flickboard.ui.ConfiguredKeyboard
import se.nullable.flickboard.ui.EnabledLayers
import se.nullable.flickboard.ui.FlickBoardParent
import se.nullable.flickboard.ui.LocalAppSettings
import se.nullable.flickboard.ui.ProvideDisplayLimits

class KeyboardService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle = lifecycleRegistry

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry =
        savedStateRegistryController.savedStateRegistry

    private var cursor: CursorAnchorInfo? = null

    private var activeModifiers = ModifierState()

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        var cursorUpdatesRequested = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                currentInputConnection.requestCursorUpdates(
                    InputConnection.CURSOR_UPDATE_MONITOR,
                    InputConnection.CURSOR_UPDATE_FILTER_EDITOR_BOUNDS,
                )
        // Even on modern android, some apps only support unfiltered requestCursorUpdates,
        // so fall back to trying that.
        cursorUpdatesRequested = cursorUpdatesRequested ||
                currentInputConnection.requestCursorUpdates(
                    InputConnection.CURSOR_UPDATE_MONITOR,
                )
        if (!cursorUpdatesRequested) {
            println("no cursor data :(")
        }
    }

    override fun onCreateInputView(): View {
        window.window?.decorView?.let { decorView ->
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
        }
        return ComposeView(this).also { view ->
            view.setContent {
                FlickBoardParent {
                    ProvideDisplayLimits {
                        val appSettings = LocalAppSettings.current
                        Surface {
                            ConfiguredKeyboard(
                                onAction = { action ->
                                    when (action) {
                                        is Action.Text ->
                                            currentInputConnection.commitText(action.character, 1)

                                        is Action.Delete -> {
                                            if (cursor?.selectionStart != cursor?.selectionEnd) {
                                                // if selection is non-empty, delete it regardless of the mode requested by the user
                                                currentInputConnection.commitText("", 0)
                                            } else {
                                                val length =
                                                    findBoundary(
                                                        action.boundary,
                                                        action.direction,
                                                        coalesce = true
                                                    )
                                                currentInputConnection.deleteSurroundingText(
                                                    if (action.direction == SearchDirection.Backwards) length else 0,
                                                    if (action.direction == SearchDirection.Forwards) length else 0,
                                                )
                                            }
                                        }

                                        is Action.Enter -> {
                                            if (currentInputEditorInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) {
                                                currentInputConnection.commitText("\n", 1)
                                            } else {
                                                currentInputConnection.performEditorAction(
                                                    when {
                                                        currentInputEditorInfo.actionLabel != null ->
                                                            currentInputEditorInfo.actionId

                                                        else -> currentInputEditorInfo.imeOptions and
                                                                (EditorInfo.IME_ACTION_DONE or
                                                                        EditorInfo.IME_ACTION_GO or
                                                                        EditorInfo.IME_ACTION_NEXT or
                                                                        EditorInfo.IME_ACTION_SEARCH or
                                                                        EditorInfo.IME_ACTION_SEND)
                                                    }
                                                )
                                            }
                                        }

                                        is Action.Jump -> {
                                            val currentPos = cursor?.let {
                                                when (action.direction) {
                                                    SearchDirection.Backwards -> it.selectionStart
                                                    SearchDirection.Forwards -> it.selectionEnd
                                                }
                                            } ?: 0
                                            val newPos = currentPos + findBoundary(
                                                action.boundary,
                                                action.direction,
                                                coalesce = true,
                                            ) * action.direction.factor
                                            currentInputConnection.setSelection(newPos, newPos)
                                        }

                                        is Action.JumpLineKeepPos -> {
                                            // Yes, this is a horror beyond comprehension.
                                            // Yes, this should really be the editor's responsibility...
                                            // How is this different from Action.Jump? Action.Jump jumps to *the boundary*,
                                            // for TextBoundary.Line this is equivalent to Home/End.
                                            val currentPos = cursor?.let {
                                                when (action.direction) {
                                                    SearchDirection.Backwards -> it.selectionStart
                                                    SearchDirection.Forwards -> it.selectionEnd
                                                }
                                            } ?: 0
                                            // Find our position on the current line
                                            val posOnLine = findBoundary(
                                                TextBoundary.Line,
                                                SearchDirection.Backwards
                                            )
                                            val lineSearchSkip = when (action.direction) {
                                                // When going backwards, we need to find the linebreak before the current one
                                                SearchDirection.Backwards -> posOnLine + 1
                                                SearchDirection.Forwards -> 0
                                            }
                                            val lineJumpOffset = when (action.direction) {
                                                // This is baked into the search skip when going backwards
                                                SearchDirection.Backwards -> 0
                                                // but when going forwards we also need to skip the newline character itself
                                                SearchDirection.Forwards -> 1
                                            }
                                            // Find the offset to the target line
                                            val targetLineOffset = (findBoundary(
                                                TextBoundary.Line,
                                                action.direction,
                                                skip = lineSearchSkip,
                                            )) * action.direction.factor + lineJumpOffset
                                            // To reconstruct the position on the line, we also need to clamp
                                            // to the length of the new line, if it is shorter than the current one
                                            val targetLineLength = when (action.direction) {
                                                // When jumping backwards, we already know the length of the
                                                // line since we're jumping into it
                                                SearchDirection.Backwards -> -targetLineOffset - posOnLine - 1
                                                // When jumping forwards.. search for the next newline after the current one
                                                SearchDirection.Forwards -> findBoundary(
                                                    TextBoundary.Line,
                                                    action.direction,
                                                    skip = targetLineOffset,
                                                    endOfBufferOffset = -1,
                                                ) - targetLineOffset
                                            }
                                            val newPos = (currentPos + targetLineOffset +
                                                    posOnLine.coerceAtMost(targetLineLength))
                                                .coerceAtLeast(0)
                                            currentInputConnection.setSelection(newPos, newPos)
                                        }

                                        is Action.ToggleShift, Action.ToggleCtrl, Action.ToggleAlt -> {
                                            // handled internally in Keyboard
                                        }

                                        Action.Copy ->
                                            currentInputConnection.performContextMenuAction(android.R.id.copy)

                                        Action.Cut ->
                                            currentInputConnection.performContextMenuAction(android.R.id.cut)

                                        Action.Paste ->
                                            currentInputConnection.performContextMenuAction(android.R.id.paste)

                                        Action.SelectAll ->
                                            currentInputConnection.performContextMenuAction(android.R.id.selectAll)

                                        Action.Settings -> startActivity(
                                            Intent.makeMainActivity(
                                                ComponentName(this, MainActivity::class.java)
                                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )

                                        is Action.SwitchLetterLayer -> {
                                            appSettings.activeLetterLayerIndex.currentValue =
                                                (appSettings.activeLetterLayerIndex.currentValue
                                                        + action.direction.factor)
                                                    .mod(appSettings.letterLayers.currentValue.size)
                                        }

                                        Action.ToggleLayerOrder -> {
                                            when (appSettings.enabledLayers.currentValue) {
                                                EnabledLayers.Letters ->
                                                    appSettings.enabledLayers.currentValue =
                                                        EnabledLayers.Numbers

                                                EnabledLayers.Numbers ->
                                                    appSettings.enabledLayers.currentValue =
                                                        EnabledLayers.Letters

                                                // Both layers are enabled, so switch their sides by toggling handedness
                                                EnabledLayers.All ->
                                                    appSettings.handedness.currentValue =
                                                        !appSettings.handedness.currentValue
                                            }
                                        }

                                        is Action.AdjustCellHeight ->
                                            appSettings.keyHeight.currentValue += action.amount
                                    }
                                },
                                onModifierStateUpdated = { newModifiers ->
                                    if (newModifiers != activeModifiers) {
                                        var modifierMask = when (newModifiers.shift) {
                                            ShiftState.Normal -> 0
                                            ShiftState.Shift -> KeyEvent.META_SHIFT_LEFT_ON
                                            ShiftState.CapsLock -> KeyEvent.META_CAPS_LOCK_ON
                                        }
                                        if (newModifiers.ctrl) {
                                            modifierMask =
                                                modifierMask or KeyEvent.META_CTRL_LEFT_ON
                                        }
                                        if (newModifiers.alt) {
                                            modifierMask = modifierMask or KeyEvent.META_ALT_LEFT_ON
                                        }
                                        fun newKeyEvent(isDown: Boolean, code: Int): KeyEvent =
                                            KeyEvent(
                                                0,
                                                0,
                                                when {
                                                    isDown -> KeyEvent.ACTION_DOWN
                                                    else -> KeyEvent.ACTION_UP
                                                },
                                                code,
                                                0,
//                                                0,
                                                KeyEvent.normalizeMetaState(modifierMask),
                                                KeyCharacterMap.VIRTUAL_KEYBOARD,
                                                0,
                                                KeyEvent.FLAG_SOFT_KEYBOARD,
                                            )
                                        if (newModifiers.shift.isShift != activeModifiers.shift.isShift) {
                                            currentInputConnection.sendKeyEvent(
                                                newKeyEvent(
                                                    newModifiers.shift.isShift,
                                                    KeyEvent.KEYCODE_SHIFT_LEFT
                                                )
                                            )
                                        }
                                        if (newModifiers.shift.isCapsLock != activeModifiers.shift.isCapsLock) {
                                            currentInputConnection.sendKeyEvent(
                                                newKeyEvent(
                                                    newModifiers.shift.isCapsLock,
                                                    KeyEvent.KEYCODE_CAPS_LOCK
                                                )
                                            )
                                        }
                                        if (newModifiers.ctrl != activeModifiers.ctrl) {
                                            currentInputConnection.sendKeyEvent(
                                                newKeyEvent(
                                                    newModifiers.ctrl,
                                                    KeyEvent.KEYCODE_CTRL_LEFT
                                                )
                                            )
                                        }
                                        if (newModifiers.alt != activeModifiers.alt) {
                                            currentInputConnection.sendKeyEvent(
                                                newKeyEvent(
                                                    newModifiers.alt,
                                                    KeyEvent.KEYCODE_ALT_LEFT
                                                )
                                            )
                                        }
                                        activeModifiers = newModifiers
                                    }
                                },
                                enterKeyLabel = currentInputEditorInfo.actionLabel?.toString(),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun findBoundary(
        boundary: TextBoundary,
        direction: SearchDirection,
        skip: Int = 0,
        coalesce: Boolean = false,
        endOfBufferOffset: Int = 0,
    ): Int {
        val boundaryChars = when (boundary) {
            TextBoundary.Letter -> return 1
            TextBoundary.Word -> charArrayOf(' ', '\t', '\n')
            TextBoundary.Line -> charArrayOf('\n')
        }
        val searchBufferSize = 1000
        val searchBuffer = when (direction) {
            SearchDirection.Backwards -> currentInputConnection.getTextBeforeCursor(
                searchBufferSize,
                0
            )?.reversed()

            SearchDirection.Forwards -> currentInputConnection.getTextAfterCursor(
                searchBufferSize,
                0
            )
        }?.drop(skip) ?: ""
        val initialDelimiters = when {
            coalesce -> searchBuffer.takeWhile { boundaryChars.contains(it) }.length
            else -> 0
        }
        val boundaryIndex = searchBuffer
            .indexOfAny(boundaryChars, initialDelimiters)
            .takeUnless { it == -1 }
        return skip + (boundaryIndex ?: (searchBuffer.length + endOfBufferOffset))
    }

    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo?) {
        super.onUpdateCursorAnchorInfo(cursorAnchorInfo)
        cursor = cursorAnchorInfo
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        super.onStartInputView(editorInfo, restarting)
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }
}