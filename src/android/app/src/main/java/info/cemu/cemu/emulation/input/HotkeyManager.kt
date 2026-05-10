package info.cemu.cemu.emulation.input

import android.view.KeyEvent
import info.cemu.cemu.common.android.inputevent.isFromPhysicalController
import info.cemu.cemu.common.settings.DEFAULT_HOTKEY_SETTINGS
import info.cemu.cemu.common.settings.HotkeyAction
import info.cemu.cemu.common.settings.HotkeyCombo
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.collections.iterator

object HotkeyManager {
    private val pressedKeys: MutableSet<Int> = mutableSetOf()
    private val activeActions: MutableSet<HotkeyAction> = mutableSetOf()

    private var hotkeyMappings: Map<HotkeyAction, HotkeyCombo> = DEFAULT_HOTKEY_SETTINGS
    fun setHotkeyMappings(hotkeyMappings: Map<HotkeyAction, HotkeyCombo>) {
        this.hotkeyMappings = DEFAULT_HOTKEY_SETTINGS + hotkeyMappings
        activeActions.clear()
    }

    fun triggerAction(action: HotkeyAction) {
        _actions.tryEmit(action)
    }

    fun onKeyEvent(keyEvent: KeyEvent): Boolean {
        if (!keyEvent.isFromPhysicalController()) {
            return false
        }

        if (keyEvent.action == KeyEvent.ACTION_DOWN) {
            pressedKeys.add(keyEvent.keyCode)
        } else {
            pressedKeys.remove(keyEvent.keyCode)
        }

        return checkHotkeys()
    }

    private fun checkHotkeys(): Boolean {
        var shouldConsumeInput = false

        for ((action, combo) in hotkeyMappings) {
            val comboKeys = combo.keys

            val isActive = comboKeys.isNotEmpty() && pressedKeys.containsAll(comboKeys)

            if (!isActive) {
                activeActions.remove(action)
                continue
            }

            shouldConsumeInput = true

            if (activeActions.add(action)) {
                _actions.tryEmit(action)
            }
        }

        return shouldConsumeInput
    }


    private val _actions =
        MutableSharedFlow<HotkeyAction>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    val actions = _actions.asSharedFlow()
}
