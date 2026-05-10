package info.cemu.cemu.settings.input.hotkeys

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.cemu.cemu.common.settings.AppSettings
import info.cemu.cemu.common.settings.AppSettingsStore
import info.cemu.cemu.common.settings.DEFAULT_HOTKEY_SETTINGS
import info.cemu.cemu.common.settings.HotkeyAction
import info.cemu.cemu.common.settings.HotkeyCombo
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HotkeyViewModel(
    private val dataStore: DataStore<AppSettings> = AppSettingsStore.dataStore
) : ViewModel() {
    val hotkeys = dataStore.data.map { DEFAULT_HOTKEY_SETTINGS + it.hotkeySettings }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        DEFAULT_HOTKEY_SETTINGS,
    )

    fun setHotkeyMapping(action: HotkeyAction, combo: HotkeyCombo) = viewModelScope.launch {
        dataStore.updateData {
            it.copy(hotkeySettings = it.hotkeySettings.toMutableMap().apply { set(action, combo) })
        }
    }

    fun clearHotkeyMapping(action: HotkeyAction) = viewModelScope.launch {
        dataStore.updateData {
            it.copy(
                hotkeySettings = it.hotkeySettings.toMutableMap().apply {
                    if (action in DEFAULT_HOTKEY_SETTINGS) {
                        set(action, HotkeyCombo(emptySet()))
                    } else {
                        remove(action)
                    }
                }
            )
        }
    }
}
