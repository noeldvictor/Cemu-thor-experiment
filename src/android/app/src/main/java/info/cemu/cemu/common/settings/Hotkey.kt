package info.cemu.cemu.common.settings

import android.view.KeyEvent
import kotlinx.serialization.Serializable

@Serializable
data class HotkeyCombo(
    val keys: Set<Int>
)

enum class HotkeyAction {
    QUIT,
    TOGGLE_MENU,
    TOGGLE_FAST_FORWARD,
    SHOW_EMULATED_USB_DEVICES_DIALOG,
}

val DEFAULT_HOTKEY_SETTINGS: Map<HotkeyAction, HotkeyCombo> = mapOf(
    HotkeyAction.TOGGLE_FAST_FORWARD to HotkeyCombo(
        setOf(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_R1)
    )
)
