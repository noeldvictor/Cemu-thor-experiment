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
        // Candidates are sorted by sortOrder before anything else, so whichever source is first
        // here is what the Recommended button downloads - regardless of how old its newest
        // release is. K11MCH1 used to be the obvious default, but as of 2026-08-21 its latest
        // release is v26.0.0-rc08 from 2026-01-08, seven months stale, while MrPurple shipped
        // T30 four days earlier and Banners-Turnip had published three builds that same day.
        //
        // MrPurple leads because it is both actively maintained and curated. Banners-Turnip is
        // deliberately last: it rebuilds on every upstream Mesa commit (r7, r8 and r9 all landed
        // on one day), which is great to have available but a poor default, since any individual
        // build can carry an upstream regression. K11MCH1 stays high because it is the
        // long-standing reference and may resume releases.
        private val DRIVER_SOURCES = listOf(
            DriverSource(
                name = "MrPurple",
                repo = "MrPurple666/purple-turnip",
                sortOrder = 0,
            ),
            DriverSource(
                name = "Kimchi / K11MCH1",
                repo = "K11MCH1/AdrenoToolsDrivers",
                sortOrder = 1,
            ),
            DriverSource(
                name = "StevenMXZ",
                repo = "StevenMXZ/Adreno-Tools-Drivers",
                sortOrder = 2,
            ),
            DriverSource(
                name = "Banners-Turnip",
                repo = "The412Banner/Banners-Turnip",
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
