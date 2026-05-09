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
        val response = client.get(RELEASES_URL)
        if (!response.status.isSuccess())
            return emptyList()

        val releases = json.decodeFromString<List<GithubRelease>>(response.body<String>())
        val candidates = releases
            .flatMapIndexed { releaseIndex, release ->
                release.assets.map { GithubAssetCandidate(releaseIndex, release, it) }
            }
            .filter { isTurnipZip(it.asset.name) }
            .sortedWith(
                compareBy<GithubAssetCandidate> { it.releaseIndex }
                    .thenBy { turnipAssetScore(it.asset.name) }
            )

        return candidates.map {
            val releaseName = it.release.name ?: it.release.tagName
            TurnipDriverAsset(
                fileName = it.asset.name,
                releaseName = releaseName,
                downloadUrl = it.asset.browserDownloadUrl,
                isRecommended = turnipAssetScore(it.asset.name) == 0,
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
        return fileName.endsWith(".zip", ignoreCase = true) &&
                fileName.contains("turnip", ignoreCase = true)
    }

    private fun turnipAssetScore(fileName: String): Int {
        val isStandardTurnip = fileName.startsWith("Turnip_", ignoreCase = true) &&
                !fileName.contains("Gmem", ignoreCase = true) &&
                !fileName.contains("Sysmem", ignoreCase = true)
        return when {
            isStandardTurnip -> 0
            !fileName.contains("Gmem", ignoreCase = true) &&
                    !fileName.contains("Sysmem", ignoreCase = true) -> 1
            else -> 2
        }
    }

    companion object {
        private const val RELEASES_URL =
            "https://api.github.com/repos/K11MCH1/AdrenoToolsDrivers/releases?per_page=30"
    }
}

private data class GithubAssetCandidate(
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
