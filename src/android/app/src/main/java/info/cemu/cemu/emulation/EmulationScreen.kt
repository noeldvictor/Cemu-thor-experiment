package info.cemu.cemu.emulation

import android.annotation.SuppressLint
import android.app.Activity
import android.app.GameManager
import android.app.GameState
import android.content.Context
import android.os.Build
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import info.cemu.cemu.R
import info.cemu.cemu.common.android.display.DisplayUtils
import info.cemu.cemu.common.settings.GamePadPosition
import info.cemu.cemu.common.settings.HotkeyAction
import info.cemu.cemu.common.ui.components.Slider
import info.cemu.cemu.common.ui.extensions.showMessage
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.emulation.emulatedusbdevices.EmulatedUSBDevicesDialog
import info.cemu.cemu.emulation.input.HotkeyManager
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurface
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurfaceView
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurfaceView.InputMode.DEFAULT
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurfaceView.InputMode.EDIT_POSITION
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurfaceView.InputMode.EDIT_SIZE
import info.cemu.cemu.nativeinterface.NativeEmulation
import info.cemu.cemu.nativeinterface.NativeInput
import info.cemu.cemu.nativeinterface.NativeSettings
import kotlinx.coroutines.launch

@Composable
fun EmulationScreen(
    gamePath: String,
    setMotionSensorEnabled: (Boolean) -> Unit,
    setInputListeningEnabled: (Boolean) -> Unit,
    onQuit: () -> Unit,
    viewModel: EmulationViewModel = viewModel(
        factory = EmulationViewModel.Factory, extras = MutableCreationExtras().apply {
            set(EmulationViewModel.LAUNCH_PATH_KEY, gamePath)
        }),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showQuitConfirmationDialog by remember { mutableStateOf(false) }
    var inputOverlayInputMode by rememberSaveable { mutableStateOf(DEFAULT) }
    var showEmulatedUSBDevices by remember { mutableStateOf(false) }

    val emulationError by viewModel.emulationError.collectAsState()
    val isEmulationInitialized by viewModel.isEmulationInitialized.collectAsState()
    val sideMenuState by viewModel.sideMenuState.collectAsState()
    val gamePadPosition by viewModel.gamePadPosition.collectAsState()
    val isInputOverlayVisible by viewModel.isInputOverlayVisible.collectAsState()
    val inputOverlaySettings by viewModel.inputOverlaySettings.collectAsState()
    val mainSurfaceDimensions by viewModel.mainSurfaceDimensions.collectAsState()
    val padSurfaceDimensions by viewModel.padSurfaceDimensions.collectAsState()
    val showStarFoxControllerHelp = remember(gamePath) { isStarFoxZeroLaunch(gamePath) }
    val context = LocalContext.current


    fun closeDrawer() {
        scope.launch {
            drawerState.close()
        }
    }

    suspend fun toggleMenu() {
        drawerState.apply {
            if (isClosed) {
                open()
            } else {
                close()
            }
        }
    }

    BackHandler {
        if (drawerState.isAnimationRunning) {
            return@BackHandler
        }

        scope.launch {
            toggleMenu()
        }
    }

    LaunchedEffect(drawerState.isClosed) {
        setInputListeningEnabled(drawerState.isClosed)
    }

    LaunchedEffect(isEmulationInitialized) {
        reportAndroidGameState(context, isGameplay = isEmulationInitialized)
    }

    DisposableEffect(Unit) {
        onDispose {
            reportAndroidGameState(context, isGameplay = false, isLoading = false)
        }
    }

    LaunchedEffect(sideMenuState.areScreensSwapped) {
        NativeEmulation.setSwapScreens(sideMenuState.areScreensSwapped)
    }

    LaunchedEffect(sideMenuState.isExternalScreenRotatedLeft) {
        NativeEmulation.setExternalScreenRotatedLeft(sideMenuState.isExternalScreenRotatedLeft)
    }

    LaunchedEffect(sideMenuState.fastForwardSpeed) {
        NativeEmulation.setFastForwardSpeed(sideMenuState.fastForwardSpeed)
    }

    LaunchedEffect(sideMenuState.isFastForwardEnabled) {
        NativeEmulation.setFastForwardEnabled(sideMenuState.isFastForwardEnabled)
    }

    LaunchedEffect(Unit) {
        HotkeyManager.actions.collect { action ->
            when (action) {
                HotkeyAction.QUIT -> showQuitConfirmationDialog = true
                HotkeyAction.TOGGLE_MENU -> toggleMenu()
                HotkeyAction.TOGGLE_FAST_FORWARD -> {
                    val isEnabled = NativeEmulation.toggleFastForward()
                    // keep the side menu checkbox in sync with the hotkey
                    viewModel.updateSideMenuState(sideMenuState.copy(isFastForwardEnabled = isEnabled))
                    snackbarHostState.showMessage(
                        scope,
                        if (isEnabled) tr("Fast forward on (%1x)").replace("%1", sideMenuState.fastForwardSpeed.toString())
                        else tr("Fast forward off")
                    )
                }
                HotkeyAction.SHOW_EMULATED_USB_DEVICES_DIALOG -> showEmulatedUSBDevices = true
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(520.dp),
            ) {
                EmulationSideMenuContent(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .fillMaxWidth(),
                    showControllerHelp = showStarFoxControllerHelp,
                    sideMenuState = sideMenuState,
                    updateState = {
                        viewModel.updateSideMenuState(it)
                        setMotionSensorEnabled(it.isMotionEnabled)
                        NativeEmulation.setReplaceTVWithPadView(it.isTVReplacedWithPad)
                    },
                    onEditInputOverlay = {
                        snackbarHostState.showMessage(scope, tr("Edit input positions"))
                        inputOverlayInputMode = EDIT_POSITION
                        closeDrawer()
                    },
                    onResetInputOverlay = {
                        viewModel.resetInputOverlayLayout()
                        closeDrawer()
                    },
                    onResetGuestProfiler = {
                        NativeEmulation.resetGuestProfiler()
                        viewModel.updateSideMenuState(sideMenuState.copy(isGuestProfilerEnabled = false))
                        snackbarHostState.showMessage(scope, tr("Guest profile reset"))
                    },
                    onDumpGuestProfiler = {
                        val dumpPath = NativeEmulation.dumpGuestProfiler()
                        snackbarHostState.showMessage(
                            scope,
                            if (dumpPath.isBlank()) tr("Guest profile dump failed") else tr("Guest profile dumped")
                        )
                    },
                    onQuit = {
                        showQuitConfirmationDialog = true
                        closeDrawer()
                    },
                    onShowEmulatedUSBDevices = {
                        showEmulatedUSBDevices = true
                        closeDrawer()
                    },
                )
            }
        },
    ) {
        EmulationSurfaces(
            sideMenuState = sideMenuState,
            gamePadPosition = gamePadPosition,
            isEmulationInitialized = isEmulationInitialized,
            mainSurfaceDimensions = mainSurfaceDimensions,
            padSurfaceDimensions = padSurfaceDimensions,
            mainHolderCallback = viewModel.mainHolderCallback,
            padHolderCallback = viewModel.padHolderCallback,
            onInitializeEmulation = viewModel::initializeEmulation,
        )

        InputOverlaySurface(
            isVisible = isInputOverlayVisible,
            inputOverlaySettings = inputOverlaySettings,
            inputMode = inputOverlayInputMode,
            onEditFinished = { viewModel.saveInputOverlayRectangles(it) },
        )

        if (inputOverlayInputMode != DEFAULT) {
            EditInputsLayout(
                inputMode = inputOverlayInputMode,
                onFinishClick = {
                    snackbarHostState.showMessage(scope, tr("Exited input edit mode"))
                    inputOverlayInputMode = DEFAULT
                },
                onMoveClick = {
                    snackbarHostState.showMessage(scope, tr("Edit input positions"))
                    inputOverlayInputMode = EDIT_POSITION
                },
                onResizeClick = {
                    snackbarHostState.showMessage(scope, tr("Edit input size"))
                    inputOverlayInputMode = EDIT_SIZE
                },
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }

    emulationError?.let {
        EmulationErrorDialog(it, onQuit)
    }

    if (!isEmulationInitialized) {
        EmulationLoadingDialog()
    }

    if (showQuitConfirmationDialog) {
        EmulationQuitConfirmationDialog(
            onQuit = onQuit,
            onDismiss = { showQuitConfirmationDialog = false },
        )
    }

    if (showEmulatedUSBDevices) {
        EmulatedUSBDevicesDialog(
            onDismiss = { showEmulatedUSBDevices = false },
        )
    }

    EmulationTextInputDialog()
}

@Composable
private fun EditInputsLayout(
    inputMode: InputOverlaySurfaceView.InputMode,
    onFinishClick: () -> Unit,
    onMoveClick: () -> Unit,
    onResizeClick: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = onFinishClick) { Text(tr("Done")) }

            Row(
                modifier = Modifier
                    .wrapContentSize()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconButton(enabled = inputMode != EDIT_POSITION, onClick = onMoveClick) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_move),
                        contentDescription = tr("Move")
                    )
                }

                FilledIconButton(enabled = inputMode != EDIT_SIZE, onClick = onResizeClick) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_resize),
                        contentDescription = tr("Resize"),
                    )
                }
            }
        }
    }
}

private enum class SideMenuSection {
    DISPLAY,
    PERFORMANCE,
    AUDIO,
    CONTROLS,
    CONTROLLER_HELP,
    TOOLS,
}

@Composable
private fun EmulationSideMenuContent(
    modifier: Modifier = Modifier,
    showControllerHelp: Boolean,
    sideMenuState: SideMenuState,
    updateState: (SideMenuState) -> Unit,
    onShowEmulatedUSBDevices: () -> Unit,
    onEditInputOverlay: () -> Unit,
    onResetInputOverlay: () -> Unit,
    onResetGuestProfiler: () -> Unit,
    onDumpGuestProfiler: () -> Unit,
    onQuit: () -> Unit,
) {
    var selectedSection by rememberSaveable { mutableStateOf(SideMenuSection.DISPLAY) }

    Row(modifier = modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .width(160.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SideMenuNavItem(
                label = tr("Display"),
                selected = selectedSection == SideMenuSection.DISPLAY,
                onClick = { selectedSection = SideMenuSection.DISPLAY },
            )
            SideMenuNavItem(
                label = tr("Performance"),
                selected = selectedSection == SideMenuSection.PERFORMANCE,
                onClick = { selectedSection = SideMenuSection.PERFORMANCE },
            )
            SideMenuNavItem(
                label = tr("Audio"),
                selected = selectedSection == SideMenuSection.AUDIO,
                onClick = { selectedSection = SideMenuSection.AUDIO },
            )
            SideMenuNavItem(
                label = tr("Controls"),
                selected = selectedSection == SideMenuSection.CONTROLS,
                onClick = { selectedSection = SideMenuSection.CONTROLS },
            )
            if (showControllerHelp) {
                SideMenuNavItem(
                    label = tr("Controller Help"),
                    selected = selectedSection == SideMenuSection.CONTROLLER_HELP,
                    onClick = { selectedSection = SideMenuSection.CONTROLLER_HELP },
                )
            }
            SideMenuNavItem(
                label = tr("Tools"),
                selected = selectedSection == SideMenuSection.TOOLS,
                onClick = { selectedSection = SideMenuSection.TOOLS },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            TextButtonItem(
                label = tr("Exit"),
                onClick = onQuit,
            )
        }

        VerticalDivider(modifier = Modifier.fillMaxHeight())

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 8.dp)
        ) {
            Text(
                text = selectedSection.title(),
                modifier = Modifier.padding(8.dp),
                fontSize = 20.sp,
            )

            HorizontalDivider(modifier = Modifier.padding(bottom = 4.dp))

            when (selectedSection) {
                SideMenuSection.DISPLAY -> DisplayMenuContent(sideMenuState, updateState)
                SideMenuSection.PERFORMANCE -> PerformanceMenuContent(
                    sideMenuState = sideMenuState,
                    updateState = updateState,
                    onResetGuestProfiler = onResetGuestProfiler,
                    onDumpGuestProfiler = onDumpGuestProfiler,
                )
                SideMenuSection.AUDIO -> AudioMenuContent(sideMenuState, updateState)
                SideMenuSection.CONTROLS -> ControlsMenuContent(
                    sideMenuState = sideMenuState,
                    updateState = updateState,
                    onEditInputOverlay = onEditInputOverlay,
                    onResetInputOverlay = onResetInputOverlay,
                )
                SideMenuSection.CONTROLLER_HELP -> ControllerHelpMenuContent(
                    sideMenuState = sideMenuState,
                    updateState = updateState,
                )
                SideMenuSection.TOOLS -> ToolsMenuContent(onShowEmulatedUSBDevices)
            }
        }
    }
}

@Composable
private fun SideMenuSection.title(): String = when (this) {
    SideMenuSection.DISPLAY -> tr("Display")
    SideMenuSection.PERFORMANCE -> tr("Performance")
    SideMenuSection.AUDIO -> tr("Audio")
    SideMenuSection.CONTROLS -> tr("Controls")
    SideMenuSection.CONTROLLER_HELP -> tr("Controller Help")
    SideMenuSection.TOOLS -> tr("Tools")
}

@Composable
private fun SideMenuNavItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .minimumInteractiveComponentSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier
                .padding(end = 8.dp)
                .weight(1f),
            fontSize = if (selected) 17.sp else 16.sp,
        )

        Icon(
            painter = painterResource(id = R.drawable.ic_chevron_right),
            contentDescription = null,
            modifier = Modifier.alpha(if (selected) 1f else 0f),
        )
    }
}

@Composable
private fun DisplayMenuContent(
    sideMenuState: SideMenuState,
    updateState: (SideMenuState) -> Unit,
) {
        CheckboxItem(
            label = tr("Show PAD"),
            checked = sideMenuState.isPadVisible,
            onCheckedChange = { updateState(sideMenuState.copy(isPadVisible = it)) },
        )

        CheckboxItem(
            label = tr("External PAD screen"),
            checked = sideMenuState.isPadOnExternalDisplay,
            onCheckedChange = { updateState(sideMenuState.copy(isPadOnExternalDisplay = it)) },
            enabled = sideMenuState.isPadVisible,
        )

        CheckboxItem(
            label = tr("Swap screens"),
            checked = sideMenuState.areScreensSwapped,
            onCheckedChange = { updateState(sideMenuState.copy(areScreensSwapped = it)) },
        )

        CheckboxItem(
            label = tr("Replace TV with PAD"),
            checked = sideMenuState.isTVReplacedWithPad,
            onCheckedChange = { updateState(sideMenuState.copy(isTVReplacedWithPad = it)) },
        )

        CheckboxItem(
            label = tr("Rotate external screen left"),
            checked = sideMenuState.isExternalScreenRotatedLeft,
            onCheckedChange = { updateState(sideMenuState.copy(isExternalScreenRotatedLeft = it)) },
            enabled = sideMenuState.isPadOnExternalDisplay,
        )

        Slider(
            label = tr("PAD render scale"),
            value = sideMenuState.padRenderScalePercent,
            valueFrom = PAD_RENDER_SCALE_MIN,
            valueTo = PAD_RENDER_SCALE_MAX,
            steps = 1,
            enabled = sideMenuState.isPadVisible && sideMenuState.isPadOnExternalDisplay,
            onValueChange = {
                updateState(
                    sideMenuState.copy(
                        padRenderScalePercent = normalizePadRenderScalePercent(it)
                    )
                )
            },
            labelFormatter = { "$it%" },
        )
}

@Composable
private fun PerformanceMenuContent(
    sideMenuState: SideMenuState,
    updateState: (SideMenuState) -> Unit,
    onResetGuestProfiler: () -> Unit,
    onDumpGuestProfiler: () -> Unit,
) {
        CheckboxItem(
            label = tr("Show FPS"),
            checked = sideMenuState.isFPSOverlayVisible,
            onCheckedChange = {
                updateState(
                    sideMenuState.copy(
                        isFPSOverlayVisible = it,
                        isPerfOverlayVisible = if (it) false else sideMenuState.isPerfOverlayVisible,
                    )
                )
            },
        )

        CheckboxItem(
            label = tr("Show perf details"),
            checked = sideMenuState.isPerfOverlayVisible,
            onCheckedChange = { updateState(sideMenuState.copy(isPerfOverlayVisible = it)) },
        )

        CheckboxItem(
            label = tr("Async shader compile"),
            checked = sideMenuState.isAsyncShaderCompileEnabled,
            onCheckedChange = { updateState(sideMenuState.copy(isAsyncShaderCompileEnabled = it)) },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

        CheckboxItem(
            label = tr("Fast forward"),
            checked = sideMenuState.isFastForwardEnabled,
            onCheckedChange = { updateState(sideMenuState.copy(isFastForwardEnabled = it)) },
        )

        Slider(
            label = tr("Fast forward speed"),
            value = sideMenuState.fastForwardSpeed,
            valueFrom = FAST_FORWARD_SPEED_MIN,
            valueTo = FAST_FORWARD_SPEED_MAX,
            steps = 1,
            onValueChange = {
                updateState(sideMenuState.copy(fastForwardSpeed = normalizeFastForwardSpeed(it)))
            },
            labelFormatter = { "${it}x" },
        )

        CheckboxItem(
            label = tr("Skip GX2DrawDone sync (session)"),
            checked = sideMenuState.skipGX2DrawDoneSync,
            onCheckedChange = { updateState(sideMenuState.copy(skipGX2DrawDoneSync = it)) },
        )

        CheckboxItem(
            label = tr("Skip accurate barriers (session)"),
            checked = sideMenuState.skipAccurateBarriers,
            onCheckedChange = { updateState(sideMenuState.copy(skipAccurateBarriers = it)) },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

        CheckboxItem(
            label = tr("Profile guest PPC blocks"),
            checked = sideMenuState.isGuestProfilerEnabled,
            onCheckedChange = { updateState(sideMenuState.copy(isGuestProfilerEnabled = it)) },
        )

        TextButtonItem(
            label = tr("Reset guest profile"),
            onClick = onResetGuestProfiler,
        )

        TextButtonItem(
            label = tr("Dump guest profile"),
            onClick = onDumpGuestProfiler,
        )
}

@Composable
private fun AudioMenuContent(
    sideMenuState: SideMenuState,
    updateState: (SideMenuState) -> Unit,
) {
    CheckboxItem(
        label = tr("GamePad audio"),
        checked = sideMenuState.isGamePadAudioEnabled,
        onCheckedChange = { enabled ->
            updateState(
                sideMenuState.copy(
                    isGamePadAudioEnabled = enabled,
                    gamePadVolume = if (enabled && sideMenuState.gamePadVolume == 0) 50 else sideMenuState.gamePadVolume,
                )
            )
        },
    )

    Slider(
        label = tr("GamePad volume"),
        value = sideMenuState.gamePadVolume,
        valueFrom = NativeSettings.AUDIO_MIN_VOLUME,
        steps = 19,
        valueTo = NativeSettings.AUDIO_MAX_VOLUME,
        enabled = sideMenuState.isGamePadAudioEnabled,
        onValueChange = { updateState(sideMenuState.copy(gamePadVolume = it)) },
        labelFormatter = { "$it%" },
    )
}

@Composable
private fun ControlsMenuContent(
    sideMenuState: SideMenuState,
    updateState: (SideMenuState) -> Unit,
    onEditInputOverlay: () -> Unit,
    onResetInputOverlay: () -> Unit,
) {
        CheckboxItem(
            label = tr("Enable motion"),
            checked = sideMenuState.isMotionEnabled,
            onCheckedChange = { updateState(sideMenuState.copy(isMotionEnabled = it)) },
        )

        CheckboxItem(
            label = tr("Show input overlay"),
            checked = sideMenuState.isInputOverlayVisible,
            onCheckedChange = { updateState(sideMenuState.copy(isInputOverlayVisible = it)) },
        )

        TextButtonItem(
            label = tr("Edit inputs"),
            enabled = sideMenuState.isInputOverlayVisible,
            onClick = onEditInputOverlay,
        )

        TextButtonItem(
            label = tr("Reset input overlay"),
            enabled = sideMenuState.isInputOverlayVisible,
            onClick = onResetInputOverlay,
        )
}

@Composable
private fun ToolsMenuContent(onShowEmulatedUSBDevices: () -> Unit) {
    TextButtonItem(
        label = tr("Emulated USB Devices"),
        onClick = onShowEmulatedUSBDevices,
    )
}

@Composable
private fun ControllerHelpMenuContent(
    sideMenuState: SideMenuState,
    updateState: (SideMenuState) -> Unit,
) {
    var isR2Laser by rememberSaveable { mutableStateOf(true) }
    var isDeviceGyro by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isR2Laser = isStarFoxR2LaserMappingActive()
        isDeviceGyro = isStarFoxDeviceGyroActive() && sideMenuState.isMotionEnabled
    }

    LaunchedEffect(sideMenuState.isMotionEnabled) {
        isDeviceGyro = isStarFoxDeviceGyroActive() && sideMenuState.isMotionEnabled
    }

    Text(
        text = tr("Star Fox Zero"),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        fontSize = 17.sp,
    )

    CheckboxItem(
        label = tr("R2 fires laser"),
        checked = isR2Laser,
        onCheckedChange = { enabled ->
            if (enabled != isR2Laser) {
                NativeInput.swapControllerMappings(
                    STAR_FOX_CONTROLLER_INDEX,
                    NativeInput.VPADButton.A,
                    NativeInput.VPADButton.ZR,
                )
            }
            isR2Laser = isStarFoxR2LaserMappingActive()
        },
    )

    CheckboxItem(
        label = tr("Device gyro aiming"),
        checked = isDeviceGyro,
        onCheckedChange = { enabled ->
            if (enabled) {
                updateState(sideMenuState.copy(isMotionEnabled = true))
            }
            setStarFoxDeviceGyroActive(enabled)
            if (!enabled) {
                updateState(sideMenuState.copy(isMotionEnabled = false))
            }
            isDeviceGyro = enabled
        },
    )

    val laserInput = if (isR2Laser) tr("R2") else tr("A")
    val transformInput = if (isR2Laser) tr("A") else tr("R2")
    val aimInput = if (isDeviceGyro) tr("Device gyro") else tr("Right stick")
    val aimAction = if (isDeviceGyro) {
        tr("Aim / cockpit gyro (handheld motion)")
    } else {
        tr("Aim / cockpit gyro (low sensitivity, inverted Y)")
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

    ControllerHelpRow(aimInput, aimAction)
    ControllerHelpRow(laserInput, tr("Laser / charge shot"))
    ControllerHelpRow(transformInput, tr("Transform / confirm"))
    ControllerHelpRow(tr("B"), tr("Smart bomb"))
    ControllerHelpRow(tr("X"), tr("Boost"))
    ControllerHelpRow(tr("Y"), tr("Brake / hover"))
    ControllerHelpRow(tr("L / R"), tr("Bank / barrel roll"))
    ControllerHelpRow(tr("L2"), tr("Target mode"))
    ControllerHelpRow(tr("Select"), tr("Recenter aim"))
    ControllerHelpRow(tr("L3 / R3"), tr("U-turn / somersault"))

    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

    Text(
        text = tr("This profile keeps aiming separate from boost, brake, and maneuvers. Use device gyro for real motion, or right stick for handheld stick aiming."),
        modifier = Modifier.padding(8.dp),
        fontSize = 14.sp,
    )
}

@Composable
private fun ControllerHelpRow(
    input: String,
    action: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = input,
            modifier = Modifier.width(96.dp),
            fontSize = 15.sp,
        )
        Text(
            text = action,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
        )
    }
}

private fun isStarFoxZeroLaunch(gamePath: String): Boolean {
    val normalizedPath = gamePath.lowercase()
    return normalizedPath.contains("star fox zero") ||
            normalizedPath.contains("101b0400") ||
            normalizedPath.contains("101b0500")
}

private const val STAR_FOX_CONTROLLER_INDEX = 0

private fun isStarFoxR2LaserMappingActive(): Boolean {
    val mapping = NativeInput.getControllerMapping(
        STAR_FOX_CONTROLLER_INDEX,
        NativeInput.VPADButton.ZR,
    )
    return mapping.isEmpty() || mapping.contains("Button R2")
}

private fun isStarFoxDeviceGyroActive(): Boolean =
    !NativeInput.getVPADRightStickMotion(STAR_FOX_CONTROLLER_INDEX)

private fun setStarFoxDeviceGyroActive(enabled: Boolean) {
    if (enabled) {
        NativeInput.setDeviceControllerIndex(STAR_FOX_CONTROLLER_INDEX)
    }
    NativeInput.setVPADRightStickMotion(STAR_FOX_CONTROLLER_INDEX, !enabled)
}

@Composable
private fun CheckboxItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f)
            .clickable(enabled) { onCheckedChange(!checked) }
            .padding(8.dp)
            .minimumInteractiveComponentSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier
                .padding(end = 8.dp)
                .weight(1f),
            fontSize = 16.sp,
        )

        Checkbox(
            checked = checked,
            onCheckedChange = null,
        )
    }
}

@Composable
private fun TextButtonItem(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp)
            .heightIn(min = 48.dp)
            .wrapContentHeight(align = Alignment.CenterVertically),
        fontSize = 16.sp,
    )
}

@Composable
private fun EmulationSurfaces(
    sideMenuState: SideMenuState,
    gamePadPosition: GamePadPosition,
    isEmulationInitialized: Boolean,
    mainSurfaceDimensions: SurfaceDimensions,
    padSurfaceDimensions: SurfaceDimensions,
    mainHolderCallback: SurfaceHolder.Callback,
    padHolderCallback: SurfaceHolder.Callback,
    onInitializeEmulation: () -> Unit
) {
    val isVertical = gamePadPosition.isVertical()
    val appearsAfterTV = gamePadPosition.appearsAfterTV()
    val context = LocalContext.current
    val activity = context as? Activity
    val padDisplay = if (activity != null) rememberPadDisplay(activity) else null
    val isPadVisibleEffective = sideMenuState.isPadVisible && isEmulationInitialized
    val usePadPresentation =
        isPadVisibleEffective && sideMenuState.isPadOnExternalDisplay && padDisplay != null

    val mainTouchListener = remember { CanvasOnTouchListener() }
    val padTouchListener = remember { CanvasOnTouchListener() }
    val padPresentationTouchListener = remember { CanvasOnTouchListener() }

    val isMainTargetingTV = !sideMenuState.areScreensSwapped
    val mainTargetDimensions =
        if (isMainTargetingTV) mainSurfaceDimensions else padSurfaceDimensions

    val isPadTargetingTV = sideMenuState.areScreensSwapped
    val padTargetDimensions =
        if (isPadTargetingTV) mainSurfaceDimensions else padSurfaceDimensions

    val rotatePresentationTouch = usePadPresentation && sideMenuState.isExternalScreenRotatedLeft

    LaunchedEffect(isPadTargetingTV, padTargetDimensions, rotatePresentationTouch) {
        padPresentationTouchListener.updateConfiguration(
            isTv = isPadTargetingTV,
            surfaceWidth = padTargetDimensions.width,
            surfaceHeight = padTargetDimensions.height,
            rotateLeft = rotatePresentationTouch,
        )
    }

    DisposableEffect(
        activity,
        padDisplay,
        usePadPresentation,
        sideMenuState.isExternalScreenRotatedLeft,
        sideMenuState.padRenderScalePercent,
    ) {
        val activityNonNull = activity ?: return@DisposableEffect onDispose {}
        if (!usePadPresentation)
            return@DisposableEffect onDispose {}

        val padDisplayNonNull = padDisplay
        NativeEmulation.setExternalScreenRotatedLeft(sideMenuState.isExternalScreenRotatedLeft)

        val padPresentation = PadPresentation(
            context = activityNonNull,
            display = padDisplayNonNull,
            rotateLeft = sideMenuState.isExternalScreenRotatedLeft,
            renderScalePercent = sideMenuState.padRenderScalePercent,
            holderCallback = padHolderCallback,
            touchListener = padPresentationTouchListener,
        )
        padPresentation.show()

        onDispose { padPresentation.dismiss() }
    }

    @Composable
    fun MainSurface(modifier: Modifier) {
        EmulationSurface(
            modifier = modifier,
            holderCallback = mainHolderCallback,
            touchListener = mainTouchListener,
            touchIsTv = isMainTargetingTV,
            touchSurfaceWidth = mainTargetDimensions.width,
            touchSurfaceHeight = mainTargetDimensions.height,
            afterInit = { onInitializeEmulation() },
        )
    }

    @Composable
    fun PadSurface(modifier: Modifier) {
        if (isPadVisibleEffective && !usePadPresentation) {
            EmulationSurface(
                modifier = modifier,
                holderCallback = padHolderCallback,
                touchListener = padTouchListener,
                touchIsTv = isPadTargetingTV,
                touchSurfaceWidth = padTargetDimensions.width,
                touchSurfaceHeight = padTargetDimensions.height,
            )
        }
    }

    @Composable
    fun SurfacesInOrder(itemModifier: Modifier) {
        if (appearsAfterTV) {
            MainSurface(itemModifier)
            PadSurface(itemModifier)
        } else {
            PadSurface(itemModifier)
            MainSurface(itemModifier)
        }
    }

    LinearLayout(isVertical) { itemModifier ->
        SurfacesInOrder(itemModifier)
    }
}

@Composable
private fun LinearLayout(
    isVertical: Boolean,
    content: @Composable (Modifier) -> Unit,
) {
    if (isVertical) {
        Column(modifier = Modifier.fillMaxSize()) {
            content(Modifier.weight(1f))
        }
    } else {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(modifier = Modifier.fillMaxSize()) {
                content(Modifier.weight(1f))
            }
        }
    }
}

@Composable
@SuppressLint("ClickableViewAccessibility")
private fun EmulationSurface(
    modifier: Modifier,
    holderCallback: SurfaceHolder.Callback,
    touchListener: CanvasOnTouchListener,
    touchIsTv: Boolean,
    touchSurfaceWidth: Int,
    touchSurfaceHeight: Int,
    rotateLeft: Boolean = false,
    afterInit: () -> Unit = {}
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                var firstChange = true

                setOnTouchListener(touchListener)

                holder.addCallback(holderCallback)

                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceChanged(
                        holder: SurfaceHolder, format: Int, width: Int, height: Int
                    ) {
                        if (firstChange) {
                            afterInit()
                            firstChange = false
                        }
                    }

                    override fun surfaceCreated(holder: SurfaceHolder) {
                        holder.surface.setFrameRate(60f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {}
                })
            }
        },
        update = {
            touchListener.updateConfiguration(
                isTv = touchIsTv,
                surfaceWidth = touchSurfaceWidth,
                surfaceHeight = touchSurfaceHeight,
                rotateLeft = rotateLeft,
            )
        },
    )
}

@Composable
private fun rememberPadDisplay(activity: Activity): Display? {
    val displayManager =
        remember(activity) { activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager }
    var padDisplay by remember { mutableStateOf<Display?>(null) }

    fun updatePadDisplay() {
        padDisplay =
            if (activity.display.displayId == Display.DEFAULT_DISPLAY) {
                DisplayUtils.getExternalDisplay(activity)
            } else {
                DisplayUtils.getInternalDisplay(activity)
            }
    }

    DisposableEffect(displayManager, activity) {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = updatePadDisplay()
            override fun onDisplayRemoved(displayId: Int) = updatePadDisplay()
            override fun onDisplayChanged(displayId: Int) = updatePadDisplay()
        }

        updatePadDisplay()
        displayManager.registerDisplayListener(listener, null)
        onDispose { displayManager.unregisterDisplayListener(listener) }
    }

    return padDisplay
}

private fun reportAndroidGameState(
    context: Context,
    isGameplay: Boolean,
    isLoading: Boolean = !isGameplay,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return
    }

    val gameManager = context.getSystemService(GameManager::class.java) ?: return
    val mode =
        if (isGameplay) GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE else GameState.MODE_NONE
    gameManager.setGameState(GameState(isLoading, mode))
}

// The guest timer scales in powers of two, so only 2x/4x/8x are representable.
private const val FAST_FORWARD_SPEED_MIN = 2
private const val FAST_FORWARD_SPEED_MAX = 8

private fun normalizeFastForwardSpeed(value: Int): Int =
    when {
        value <= 3 -> 2
        value <= 6 -> 4
        else -> 8
    }

private const val PAD_RENDER_SCALE_MIN = 50
private const val PAD_RENDER_SCALE_MAX = 100

private fun normalizePadRenderScalePercent(value: Int): Int =
    when {
        value <= 62 -> 50
        value <= 87 -> 75
        else -> 100
    }

@Composable
private fun EmulationQuitConfirmationDialog(onQuit: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        title = { Text(tr("Exit confirmation")) },
        text = { Text(tr("Are you sure you want to exit?")) },
        confirmButton = { TextButton(onClick = onQuit) { Text(tr("Yes")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("No")) } },
        onDismissRequest = onDismiss,
    )
}

@Composable
private fun EmulationLoadingDialog() {
    AlertDialog(
        title = { Text(tr("Initializing emulation")) },
        text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
        confirmButton = {},
        onDismissRequest = {},
    )
}

@Composable
private fun EmulationErrorDialog(error: NativeError, onQuit: () -> Unit) {
    val errorMessage = remember(error) {
        when (error) {
            is NativeError.SystemInitializationError -> tr("Failed to initialize")

            is NativeError.RendererInitializationError ->
                tr("Failed creating renderer: {0}", error.message)

            is NativeError.SurfaceCreationError -> tr("Failed creating surface: {0}", error.message)

            NativeError.GameFilesNotFoundError -> tr("Unable to launch game because the base files were not found.")
            NativeError.NoDiscKeysError -> tr("Could not decrypt title. Make sure that keys.txt contains the correct disc key for this title.")
            NativeError.NoTitleTikError -> tr("Could not decrypt title because title.tik is missing.")
            is NativeError.UnknownTilePrepareError ->
                tr("Unable to launch game\nPath: {0}", error.launchPath)

            NativeError.LaunchingTitleError -> tr("Failed to launch title")
        }
    }
    AlertDialog(
        title = { Text(tr("Error")) },
        text = { Text(errorMessage) },
        confirmButton = { TextButton(onClick = onQuit) { Text(tr("Quit")) } },
        onDismissRequest = {},
    )
}
