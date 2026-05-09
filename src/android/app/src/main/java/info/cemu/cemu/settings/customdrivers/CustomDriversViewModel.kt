@file:OptIn(ExperimentalPathApi::class, ExperimentalUuidApi::class)

package info.cemu.cemu.settings.customdrivers

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import info.cemu.cemu.common.customdrivers.DriverMetadata
import info.cemu.cemu.common.customdrivers.META_FILE_NAME
import info.cemu.cemu.common.customdrivers.SUPPORTED_SCHEMA_VERSION
import info.cemu.cemu.common.customdrivers.getCustomDriversDir
import info.cemu.cemu.common.customdrivers.parseInstalledDrivers
import info.cemu.cemu.common.io.decodeJsonFromFile
import info.cemu.cemu.common.io.unzip
import info.cemu.cemu.nativeinterface.NativeActiveSettings
import info.cemu.cemu.nativeinterface.NativeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.moveTo
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class Driver(
    val path: String,
    val metadata: DriverMetadata,
    val selected: Boolean = false,
)

enum class DriverInstallStatus {
    Installed,
    AlreadyInstalled,
    ErrorInstalling,
    ErrorDownloading,
}

enum class DriverInstallProgress {
    FetchingDrivers,
    Downloading,
    Installing,
}

class CustomDriversViewModel : ViewModel() {
    private val selectedDriverPath = MutableStateFlow(NativeSettings.getCustomDriverPath())
    val isSystemDriverSelected = selectedDriverPath.map { it == null }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        false
    )

    private val _installedDrivers = MutableStateFlow<List<Driver>>(emptyList())
    val installedDrivers = _installedDrivers.asStateFlow()

    init {
        viewModelScope.launch {
            val selectedDriver = selectedDriverPath.value

            _installedDrivers.value = parseInstalledDrivers().map {
                Driver(
                    it.path,
                    it.metadata,
                    selected = selectedDriver == it.path,
                )
            }
        }
    }

    private val _isDriverInstallInProgress = MutableStateFlow(false)
    val isDriverInstallInProgress = _isDriverInstallInProgress.asStateFlow()

    private val _driverInstallProgress = MutableStateFlow(DriverInstallProgress.Installing)
    val driverInstallProgress = _driverInstallProgress.asStateFlow()

    fun installDriver(
        context: Context,
        driverZipUri: Uri,
        onInstallFinished: (DriverInstallStatus) -> Unit,
    ) {
        _isDriverInstallInProgress.value = true
        _driverInstallProgress.value = DriverInstallProgress.Installing
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val status = installDriverFromZip(
                    openInputStream = { context.contentResolver.openInputStream(driverZipUri) },
                    selectAfterInstall = false,
                )
                onInstallFinished(status)
            } finally {
                _isDriverInstallInProgress.value = false
            }
        }
    }

    fun downloadAndUseLatestTurnipDriver(
        onInstallFinished: (DriverInstallStatus) -> Unit,
    ) {
        _isDriverInstallInProgress.value = true
        _driverInstallProgress.value = DriverInstallProgress.Downloading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val download = TurnipDriverDownloader().downloadLatestTurnipDriver()
                if (download == null) {
                    onInstallFinished(DriverInstallStatus.ErrorDownloading)
                    return@launch
                }

                _driverInstallProgress.value = DriverInstallProgress.Installing
                val status = installDriverFromZip(
                    openInputStream = { download.zipBytes.inputStream() },
                    selectAfterInstall = true,
                )
                onInstallFinished(status)
            } catch (exception: Exception) {
                onInstallFinished(DriverInstallStatus.ErrorDownloading)
            } finally {
                _isDriverInstallInProgress.value = false
            }
        }
    }

    fun fetchAvailableTurnipDrivers(
        onFinished: (List<TurnipDriverAsset>) -> Unit,
        onError: () -> Unit,
    ) {
        _isDriverInstallInProgress.value = true
        _driverInstallProgress.value = DriverInstallProgress.FetchingDrivers
        viewModelScope.launch {
            try {
                val driverAssets = withContext(Dispatchers.IO) {
                    TurnipDriverDownloader().fetchTurnipDriverAssets()
                }

                if (driverAssets.isEmpty())
                    onError()
                else
                    onFinished(driverAssets)
            } catch (exception: Exception) {
                onError()
            } finally {
                _isDriverInstallInProgress.value = false
            }
        }
    }

    fun downloadAndUseTurnipDriver(
        driverAsset: TurnipDriverAsset,
        onInstallFinished: (DriverInstallStatus) -> Unit,
    ) {
        _isDriverInstallInProgress.value = true
        _driverInstallProgress.value = DriverInstallProgress.Downloading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val download = TurnipDriverDownloader().downloadTurnipDriver(driverAsset)
                if (download == null) {
                    onInstallFinished(DriverInstallStatus.ErrorDownloading)
                    return@launch
                }

                _driverInstallProgress.value = DriverInstallProgress.Installing
                val status = installDriverFromZip(
                    openInputStream = { download.zipBytes.inputStream() },
                    selectAfterInstall = true,
                )
                onInstallFinished(status)
            } catch (exception: Exception) {
                onInstallFinished(DriverInstallStatus.ErrorDownloading)
            } finally {
                _isDriverInstallInProgress.value = false
            }
        }
    }

    private fun installDriverFromZip(
        openInputStream: () -> InputStream?,
        selectAfterInstall: Boolean,
    ): DriverInstallStatus {
        val tempDir =
            Path(NativeActiveSettings.getUserDataPath()).resolve(Uuid.random().toString())

        try {
            tempDir.createDirectories()

            val inputStream = openInputStream()
            if (inputStream == null) {
                tempDir.deleteIfExists()
                return DriverInstallStatus.ErrorInstalling
            }
            inputStream.use {
                unzip(it, tempDir)
            }

            val metadata =
                decodeJsonFromFile<DriverMetadata>(tempDir.resolve(META_FILE_NAME).toFile())
            if (metadata == null
                || metadata.minApi > Build.VERSION.SDK_INT
                || metadata.schemaVersion != SUPPORTED_SCHEMA_VERSION
                || !tempDir.resolve(metadata.libraryName).exists()
            ) {
                tempDir.deleteIfExists()
                return DriverInstallStatus.ErrorInstalling
            }

            val existingDriver = _installedDrivers.value.firstOrNull { it.metadata == metadata }
            if (existingDriver != null) {
                tempDir.deleteIfExists()
                if (selectAfterInstall)
                    setDriverSelected(existingDriver)
                return DriverInstallStatus.AlreadyInstalled
            }

            val customDriversDir = getCustomDriversDir()
            customDriversDir.createDirectories()
            val driverPath = tempDir.moveTo(customDriversDir.resolve(tempDir.fileName))

            val driver = Driver(
                metadata = metadata,
                path = driverPath.toString(),
                selected = selectAfterInstall,
            )
            _installedDrivers.value = _installedDrivers.value
                .map { if (selectAfterInstall) it.copy(selected = false) else it }
                .toMutableList()
                .apply {
                    add(driver)
                    sortBy { it.metadata.name }
                }

            if (selectAfterInstall) {
                NativeSettings.setCustomDriverPath(driver.path)
                selectedDriverPath.value = driver.path
            }

            return DriverInstallStatus.Installed
        } catch (exception: Exception) {
            tempDir.deleteIfExists()
            return DriverInstallStatus.ErrorInstalling
        }
    }

    private fun java.nio.file.Path.deleteIfExists() {
        if (exists())
            deleteRecursively()
    }

    fun deleteDriver(driver: Driver) {
        if (!_installedDrivers.value.any { it == driver })
            return

        _installedDrivers.value -= driver
        if (selectedDriverPath.value == driver.path) {
            selectedDriverPath.value = null
            NativeSettings.setCustomDriverPath(null)
        }

        viewModelScope.launch(Dispatchers.IO) {
            Path(driver.path).toFile().deleteRecursively()
        }
    }

    fun setSystemDriverSelected() {
        if (selectedDriverPath.value == null)
            return

        val installedDrivers = _installedDrivers.value.toMutableList()
        val oldSelectedDriverIndex = installedDrivers.indexOfFirst { it.selected }
        if (oldSelectedDriverIndex != -1) {
            installedDrivers[oldSelectedDriverIndex] =
                installedDrivers[oldSelectedDriverIndex].copy(selected = false)
            _installedDrivers.value = installedDrivers
        }

        selectedDriverPath.value = null
        NativeSettings.setCustomDriverPath(null)
    }

    fun setDriverSelected(driver: Driver) {
        if (selectedDriverPath.value == driver.path)
            return

        val installedDrivers = _installedDrivers.value.toMutableList()

        val oldSelectedDriverIndex = installedDrivers.indexOfFirst { it.selected }
        if (oldSelectedDriverIndex != -1)
            installedDrivers[oldSelectedDriverIndex] =
                installedDrivers[oldSelectedDriverIndex].copy(selected = false)

        val newSelectedDriverIndex = installedDrivers.indexOf(driver)
        if (newSelectedDriverIndex == -1)
            return
        installedDrivers[newSelectedDriverIndex] = driver.copy(selected = true)

        _installedDrivers.value = installedDrivers

        NativeSettings.setCustomDriverPath(driver.path)
        selectedDriverPath.value = driver.path
    }
}
