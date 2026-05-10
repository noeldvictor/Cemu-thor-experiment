package info.cemu.cemu.emulation

import android.view.SurfaceHolder
import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import info.cemu.cemu.common.either.Either
import info.cemu.cemu.common.either.Error
import info.cemu.cemu.common.either.Success
import info.cemu.cemu.common.either.attemptWithContext
import info.cemu.cemu.common.either.bind
import info.cemu.cemu.common.either.mapError
import info.cemu.cemu.common.settings.AppSettings
import info.cemu.cemu.common.settings.AppSettingsStore
import info.cemu.cemu.common.settings.GamePadPosition
import info.cemu.cemu.common.settings.InputOverlayRect
import info.cemu.cemu.common.settings.InputOverlaySettings
import info.cemu.cemu.common.settings.OverlayInputConfig
import info.cemu.cemu.nativeinterface.NativeEmulation
import info.cemu.cemu.nativeinterface.NativeEmulation.PrepareTitleResult
import info.cemu.cemu.nativeinterface.NativeException
import info.cemu.cemu.nativeinterface.NativeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SideMenuState(
    val isMotionEnabled: Boolean = false,
    val isTVReplacedWithPad: Boolean = false,
    val isPadVisible: Boolean = true,
    val isPadOnExternalDisplay: Boolean = true,
    val areScreensSwapped: Boolean = false,
    val isExternalScreenRotatedLeft: Boolean = false,
    val isInputOverlayVisible: Boolean = false,
    val isFPSOverlayVisible: Boolean = false,
    val isAsyncShaderCompileEnabled: Boolean = true,
    val skipGX2DrawDoneSync: Boolean = false,
    val skipAccurateBarriers: Boolean = false,
    val isGamePadAudioEnabled: Boolean = false,
    val gamePadVolume: Int = 0,
)

class ConditionFlags(
    var isMainConditionMet: Boolean = false, var isPadConditionMet: Boolean = false
) {
    fun get(isMain: Boolean): Boolean {
        return if (isMain) isMainConditionMet else isPadConditionMet
    }

    fun set(isMain: Boolean, value: Boolean) {
        if (isMain) {
            isMainConditionMet = value
        } else {
            isPadConditionMet = value
        }
    }
}

data class SurfaceDimensions(val width: Int = 1, val height: Int = 1)

sealed interface NativeError {
    data class SurfaceCreationError(val message: String) : NativeError
    data class RendererInitializationError(val message: String) : NativeError

    object GameFilesNotFoundError : NativeError
    object NoDiscKeysError : NativeError
    object NoTitleTikError : NativeError
    data class UnknownTilePrepareError(val launchPath: String) : NativeError
    data class SystemInitializationError(val message: String) : NativeError

    object LaunchingTitleError : NativeError
}

class EmulationViewModel(
    private val launchPath: String,
    private val dataStore: DataStore<AppSettings> = AppSettingsStore.dataStore
) : ViewModel() {
    private val savedGX2DrawDoneSync = NativeSettings.getGX2DrawDoneSync()
    private val savedAccurateBarriers = NativeSettings.getAccurateBarriers()

    private val _emulationError = MutableStateFlow<NativeError?>(null)
    val emulationError = _emulationError.asStateFlow()

    private val _sideMenuState = MutableStateFlow(SideMenuState())
    val sideMenuState = _sideMenuState.asStateFlow()

    private val _mainSurfaceDimensions = MutableStateFlow(SurfaceDimensions())
    val mainSurfaceDimensions = _mainSurfaceDimensions.asStateFlow()

    private val _padSurfaceDimensions = MutableStateFlow(SurfaceDimensions())
    val padSurfaceDimensions = _padSurfaceDimensions.asStateFlow()

    val isInputOverlayVisible =
        sideMenuState.map { it.isInputOverlayVisible }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                false,
            )

    init {
        viewModelScope.launch {
            val settings = dataStore.data.first()
            _sideMenuState.update {
                it.copy(
                    isPadVisible = settings.emulationSettings.isPadVisible,
                    isPadOnExternalDisplay = settings.emulationSettings.isPadOnExternalDisplay,
                    isExternalScreenRotatedLeft = settings.emulationSettings.isExternalScreenRotatedLeft,
                    isInputOverlayVisible = settings.inputOverlaySettings.isOverlayEnabled,
                    isFPSOverlayVisible = isFPSOverlayVisible(),
                    isAsyncShaderCompileEnabled = NativeSettings.getAsyncShaderCompile(),
                    skipGX2DrawDoneSync = !savedGX2DrawDoneSync,
                    skipAccurateBarriers = !savedAccurateBarriers,
                    isGamePadAudioEnabled = NativeSettings.getAudioDeviceEnabled(false),
                    gamePadVolume = NativeSettings.getAudioDeviceVolume(false),
                )
            }
        }
    }

    val inputOverlaySettings = dataStore.data.map { it.inputOverlaySettings }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        InputOverlaySettings(),
    )

    fun saveInputOverlayRectangles(inputOverlayRectMap: Map<OverlayInputConfig, InputOverlayRect>) {
        viewModelScope.launch {
            dataStore.updateData {
                val overlaySettings =
                    it.inputOverlaySettings.copy(inputOverlayRectMap = inputOverlayRectMap)

                it.copy(inputOverlaySettings = overlaySettings)
            }
        }
    }

    fun resetInputOverlayLayout() {
        viewModelScope.launch {
            dataStore.updateData {
                val overlaySettings =
                    it.inputOverlaySettings.copy(inputOverlayRectMap = emptyMap())

                it.copy(inputOverlaySettings = overlaySettings)
            }
        }
    }

    fun updateSideMenuState(sideMenuState: SideMenuState) {
        val oldState = _sideMenuState.value
        _sideMenuState.value = sideMenuState

        if (oldState.isFPSOverlayVisible != sideMenuState.isFPSOverlayVisible) {
            setFPSOverlayVisible(sideMenuState.isFPSOverlayVisible)
        }

        var shouldSaveSettings = false
        if (oldState.isAsyncShaderCompileEnabled != sideMenuState.isAsyncShaderCompileEnabled) {
            NativeSettings.setAsyncShaderCompile(sideMenuState.isAsyncShaderCompileEnabled)
            shouldSaveSettings = true
        }

        if (oldState.skipGX2DrawDoneSync != sideMenuState.skipGX2DrawDoneSync) {
            NativeSettings.setGX2DrawDoneSync(!sideMenuState.skipGX2DrawDoneSync)
        }

        if (oldState.skipAccurateBarriers != sideMenuState.skipAccurateBarriers) {
            NativeSettings.setAccurateBarriers(!sideMenuState.skipAccurateBarriers)
        }

        if (oldState.isGamePadAudioEnabled != sideMenuState.isGamePadAudioEnabled) {
            NativeSettings.setAudioDeviceEnabled(sideMenuState.isGamePadAudioEnabled, false)
            shouldSaveSettings = true
        }

        if (oldState.gamePadVolume != sideMenuState.gamePadVolume) {
            NativeSettings.setAudioDeviceVolume(sideMenuState.gamePadVolume, false)
            shouldSaveSettings = true
        }

        if (shouldSaveSettings) {
            saveSettingsPreservingSessionPerformanceOverrides()
        }

        if (oldState.isPadVisible != sideMenuState.isPadVisible ||
            oldState.isPadOnExternalDisplay != sideMenuState.isPadOnExternalDisplay ||
            oldState.isExternalScreenRotatedLeft != sideMenuState.isExternalScreenRotatedLeft
        ) {
            viewModelScope.launch {
                dataStore.updateData {
                    it.copy(
                        emulationSettings = it.emulationSettings.copy(
                            isPadVisible = sideMenuState.isPadVisible,
                            isPadOnExternalDisplay = sideMenuState.isPadOnExternalDisplay,
                            isExternalScreenRotatedLeft = sideMenuState.isExternalScreenRotatedLeft,
                        )
                    )
                }
            }
        }
    }

    private fun isFPSOverlayVisible() =
        NativeSettings.getOverlayPosition() != NativeSettings.OverlayScreenPosition.DISABLED &&
                NativeSettings.isOverlayFPSEnabled()

    private fun setFPSOverlayVisible(enabled: Boolean) {
        if (enabled) {
            disableNonFPSOverlayStats()

            if (NativeSettings.getOverlayPosition() == NativeSettings.OverlayScreenPosition.DISABLED) {
                NativeSettings.setOverlayPosition(NativeSettings.OverlayScreenPosition.TOP_LEFT)
            }
        }

        NativeSettings.setOverlayFPSEnabled(enabled)

        if (!enabled && !hasOtherOverlayStatsEnabled()) {
            NativeSettings.setOverlayPosition(NativeSettings.OverlayScreenPosition.DISABLED)
        }

        saveSettingsPreservingSessionPerformanceOverrides()
    }

    private fun saveSettingsPreservingSessionPerformanceOverrides() {
        val currentGX2DrawDoneSync = NativeSettings.getGX2DrawDoneSync()
        val currentAccurateBarriers = NativeSettings.getAccurateBarriers()

        NativeSettings.setGX2DrawDoneSync(savedGX2DrawDoneSync)
        NativeSettings.setAccurateBarriers(savedAccurateBarriers)
        NativeSettings.saveSettings()
        NativeSettings.setGX2DrawDoneSync(currentGX2DrawDoneSync)
        NativeSettings.setAccurateBarriers(currentAccurateBarriers)
    }

    private fun disableNonFPSOverlayStats() {
        NativeSettings.setOverlayDrawCallsPerFrameEnabled(false)
        NativeSettings.setOverlayCPUUsageEnabled(false)
        NativeSettings.setOverlayCPUPerCoreUsageEnabled(false)
        NativeSettings.setOverlayRAMUsageEnabled(false)
        NativeSettings.setOverlayVRAMUsageEnabled(false)
        NativeSettings.setOverlayDebugEnabled(false)
    }

    private fun hasOtherOverlayStatsEnabled() =
        NativeSettings.isOverlayDrawCallsPerFrameEnabled() ||
                NativeSettings.isOverlayCPUUsageEnabled() ||
                NativeSettings.isOverlayCPUPerCoreUsageEnabled() ||
                NativeSettings.isOverlayRAMUsageEnabled() ||
                NativeSettings.isOverlayVRAMUsageEnabled() ||
                NativeSettings.isOverlayDebugEnabled()

    val gamePadPosition = dataStore.data.map { it.emulationSettings.gamePadPosition }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            GamePadPosition.RIGHT,
        )

    val destroyedSurfaces = ConditionFlags()
    var setSurfaces = ConditionFlags()

    private fun updateSurfaceDimensions(isMainCanvas: Boolean, width: Int, height: Int) {
        val newDimensions = SurfaceDimensions(
            width = width.coerceAtLeast(1),
            height = height.coerceAtLeast(1),
        )
        if (isMainCanvas) {
            _mainSurfaceDimensions.value = newDimensions
        } else {
            _padSurfaceDimensions.value = newDimensions
        }
    }

    private inner class CanvasSurfaceHolderCallback(val isMainCanvas: Boolean) :
        SurfaceHolder.Callback {

        override fun surfaceCreated(surfaceHolder: SurfaceHolder) {}

        override fun surfaceChanged(
            surfaceHolder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            try {
                NativeEmulation.setSurfaceSize(width, height, isMainCanvas)
                updateSurfaceDimensions(isMainCanvas, width, height)

                if (setSurfaces.get(isMainCanvas)) {
                    return
                }

                NativeEmulation.setSurface(surfaceHolder.surface, isMainCanvas)
                val mainSurfaceWasDestroyed = destroyedSurfaces.get(isMain = true)

                if (mainSurfaceWasDestroyed && isMainCanvas) {
                    NativeEmulation.resumeTitle()
                }

                setSurfaces.set(isMainCanvas, true)

                val padSurfaceWasSet = setSurfaces.get(isMain = false)
                if ((!isMainCanvas && !mainSurfaceWasDestroyed) || (isMainCanvas && padSurfaceWasSet)) {
                    NativeEmulation.initializeSurface(isMainCanvas = false)
                }

                destroyedSurfaces.set(isMainCanvas, false)
            } catch (exception: NativeException) {
                _emulationError.value = NativeError.SurfaceCreationError(exception.message!!)
            }
        }

        override fun surfaceDestroyed(surfaceHolder: SurfaceHolder) {
            if (setSurfaces.get(isMain = false)) {
                NativeEmulation.clearPadSurface()
                setSurfaces.set(isMain = false, false)
                destroyedSurfaces.set(isMain = false, true)
            }

            if (isMainCanvas) {
                NativeEmulation.pauseTitle()

                setSurfaces.set(isMain = true, false)
                destroyedSurfaces.set(isMain = true, true)
            }
        }
    }

    val mainHolderCallback: SurfaceHolder.Callback = CanvasSurfaceHolderCallback(true)
    val padHolderCallback: SurfaceHolder.Callback = CanvasSurfaceHolderCallback(false)

    private suspend fun initializeSystems() = attemptWithContext(Dispatchers.IO) {
        NativeEmulation.initializeSystems()
    }.mapError { NativeError.SystemInitializationError(it) }

    private suspend fun initializeRenderer() = attemptWithContext(Dispatchers.IO) {
        NativeEmulation.initializeRenderer()
        NativeEmulation.initializeSurface(isMainCanvas = true)
    }.mapError { NativeError.RendererInitializationError(it) }

    private suspend fun prepareTitle(): Either<Unit, NativeError> =
        attemptWithContext(Dispatchers.IO) { NativeEmulation.prepareTitle(launchPath) }
            .fold(
                onSuccess = { result ->
                    when (result) {
                        PrepareTitleResult.SUCCESSFUL -> Success(Unit)
                        PrepareTitleResult.ERROR_GAME_BASE_FILES_NOT_FOUND -> Error(NativeError.GameFilesNotFoundError)
                        PrepareTitleResult.ERROR_NO_DISC_KEY -> Error(NativeError.NoDiscKeysError)
                        PrepareTitleResult.ERROR_NO_TITLE_TIK -> Error(NativeError.NoTitleTikError)
                        else -> Error(NativeError.UnknownTilePrepareError(launchPath))
                    }
                },
                onError = { Error(NativeError.UnknownTilePrepareError(launchPath)) }
            )

    private suspend fun launchTitle() =
        attemptWithContext(Dispatchers.IO) { NativeEmulation.launchTitle() }
            .mapError { NativeError.LaunchingTitleError }

    private val _isEmulationInitialized = MutableStateFlow(false)
    val isEmulationInitialized = _isEmulationInitialized.asStateFlow()
    private var emulationInitializationJob: Job? = null
    fun initializeEmulation() {
        if (_isEmulationInitialized.value || emulationInitializationJob != null) {
            return
        }

        emulationInitializationJob = viewModelScope.launch {
            prepareTitle()
                .bind { initializeSystems() }
                .bind { initializeRenderer() }
                .bind { launchTitle() }
                .onError { _emulationError.value = it }

            _isEmulationInitialized.value = true
        }
    }

    companion object {
        val LAUNCH_PATH_KEY = object : CreationExtras.Key<String> {}
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                EmulationViewModel(
                    this[LAUNCH_PATH_KEY] as String
                )
            }
        }
    }
}
