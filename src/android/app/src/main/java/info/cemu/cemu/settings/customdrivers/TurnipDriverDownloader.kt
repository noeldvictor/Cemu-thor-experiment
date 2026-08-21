package info.cemu.cemu.settings.customdrivers

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class TurnipDriverDownload(
    val fileName: String,
    val releaseName: String,
    val zipBytes: ByteArray,
)

data class TurnipDriverAsset(
    val sourceName: String,
    val fileName: String,
    val releaseName: String,
    val downloadUrl: String,
    val isRecommended: Boolean,
)

class TurnipDriverDownloader(
    private val client: HttpClient = HttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun downloadLatestTurnipDriver(): TurnipDriverDownload? {
        val candidate = fetchTurnipDriverAssets().firstOrNull() ?: return null
        return downloadTurnipDriver(candidate)
    }

    suspend fun fetchTurnipDriverAssets(): List<TurnipDriverAsset> {
        val candidates = DRIVER_SOURCES
            .flatMap { source -> fetchSourceCandidates(source) }
            .sortedWith(
                compareBy<GithubAssetCandidate> { it.source.sortOrder }
                    .thenBy { it.releaseIndex }
                    .thenBy { turnipAssetScore(it.asset.name) }
            )

        return candidates.mapIndexed { index, it ->
            val releaseName = it.release.name ?: it.release.tagName
            TurnipDriverAsset(
                sourceName = it.source.name,
                fileName = it.asset.name,
                releaseName = releaseName,
                downloadUrl = it.asset.browserDownloadUrl,
                isRecommended = index == 0,
            )
        }
    }

    suspend fun downloadTurnipDriver(driverAsset: TurnipDriverAsset): TurnipDriverDownload? {
        val downloadResponse = client.get(driverAsset.downloadUrl)
        if (!downloadResponse.status.isSuccess())
            return null

        return TurnipDriverDownload(
            fileName = driverAsset.fileName,
            releaseName = driverAsset.releaseName,
            zipBytes = downloadResponse.bodyAsBytes(),
        )
    }

    private fun isTurnipZip(fileName: String): Boolean {
        // Also accept a bare .adpkg. That is the AdrenoTools package extension and is itself a
        // zip archive (meta.json plus the driver .so), so installDriverFromZip handles it
        // unchanged. MrPurple666 happens to publish assets as "...adpkg.zip" which the .zip
        // check already matched, but other repos do publish bare .adpkg.
        val isPackage = fileName.endsWith(".zip", ignoreCase = true) ||
                fileName.endsWith(".adpkg", ignoreCase = true)
        return isPackage && fileName.contains("turnip", ignoreCase = true)
    }

    private fun turnipAssetScore(fileName: String): Int {
        val isStandardTurnip = fileName.startsWith("Turnip", ignoreCase = true) &&
                SPECIALIZED_TURNIP_MARKERS.none { fileName.contains(it, ignoreCase = true) }
        return when {
            isStandardTurnip -> 0
            SPECIALIZED_TURNIP_MARKERS.none { fileName.contains(it, ignoreCase = true) } -> 1
            else -> 2
        }
    }

    private suspend fun fetchSourceCandidates(source: DriverSource): List<GithubAssetCandidate> {
        val response = client.get(source.releasesUrl)
        if (!response.status.isSuccess())
            return emptyList()

        val releases = json.decodeFromString<List<GithubRelease>>(response.body<String>())
        return releases.flatMapIndexed { releaseIndex, release ->
            release.assets
                .filter { isTurnipZip(it.name) }
                .map { GithubAssetCandidate(source, releaseIndex, release, it) }
        }
    }

    companion object {
        private val DRIVER_SOURCES = listOf(
            DriverSource(
                name = "Kimchi / K11MCH1",
                repo = "K11MCH1/AdrenoToolsDrivers",
                sortOrder = 0,
            ),
            DriverSource(
                name = "StevenMXZ",
                repo = "StevenMXZ/Adreno-Tools-Drivers",
                sortOrder = 1,
            ),
            DriverSource(
                name = "Banners-Turnip",
                repo = "The412Banner/Banners-Turnip",
                sortOrder = 2,
            ),
            DriverSource(
                name = "MrPurple",
                repo = "MrPurple666/purple-turnip",
                sortOrder = 3,
            ),
        )

        private val SPECIALIZED_TURNIP_MARKERS = listOf(
            "710",
            "720",
            "722",
            "A8xx",
            "CB_Perf_Fix",
            "experimental",
            "Gen8",
            "Gmem",
            "OneUI",
            "patched",
            "PREFER",
            "PROFILED",
            "Sysmem",
            "Test",
        )
    }
}

private data class DriverSource(
    val name: String,
    val repo: String,
    val sortOrder: Int,
) {
    val releasesUrl: String
        get() = "https://api.github.com/repos/$repo/releases?per_page=30"
}

private data class GithubAssetCandidate(
    val source: DriverSource,
    val releaseIndex: Int,
    val release: GithubRelease,
    val asset: GithubAsset,
)

@Serializable
private data class GithubRelease(
    @SerialName("tag_name")
    val tagName: String,
    val name: String? = null,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
private data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url")
    val browserDownloadUrl: String,
)
