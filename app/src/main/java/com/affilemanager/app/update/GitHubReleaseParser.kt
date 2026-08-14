package com.affilemanager.app.update

import org.json.JSONObject
import java.net.URI

object GitHubReleaseParser {
    private const val MAX_APK_SIZE = 250L * 1024L * 1024L
    private val digestPattern = Regex("^[0-9a-f]{64}$")

    fun parse(json: String, repository: String): AppRelease {
        require(repository.matches(Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"))) { "Netinkamas GitHub repozitorijos vardas" }
        val root = JSONObject(json)
        require(!root.optBoolean("draft", false)) { "Juodraštinis leidimas neatnaujinamas" }
        require(!root.optBoolean("prerelease", false)) { "Bandomasis leidimas neatnaujinamas" }

        val tag = root.getString("tag_name").trim()
        val version = UpdateVersionRules.normalized(tag)
        val pageUrl = root.getString("html_url").also { validateReleasePage(it, repository, tag) }
        val assets = root.getJSONArray("assets")
        val expectedName = "AF-File-Manager-$version.apk"
        val candidates = buildList {
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) add(asset)
            }
        }
        val assetJson = candidates.firstOrNull { it.optString("name") == expectedName }
            ?: candidates.singleOrNull()
            ?: throw IllegalArgumentException("Leidime nėra vienareikšmio AF File Manager APK")
        val name = assetJson.getString("name")
        require(name == expectedName) { "APK vardas neatitinka leidimo versijos" }
        val size = assetJson.getLong("size")
        require(size in 1..MAX_APK_SIZE) { "APK dydis neleistinas" }
        val downloadUrl = assetJson.getString("browser_download_url")
            .also { validateAssetUrl(it, repository, tag, name) }
        val digest = assetJson.getString("digest")
            .removePrefix("sha256:")
            .lowercase()
        require(digestPattern.matches(digest)) { "GitHub leidime nėra tinkamos SHA-256 kontrolinės sumos" }

        return AppRelease(
            tag = tag,
            version = version,
            notes = root.optString("body").take(4_000),
            pageUrl = pageUrl,
            asset = AppReleaseAsset(name, downloadUrl, size, digest),
        )
    }

    private fun validateReleasePage(url: String, repository: String, tag: String) {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true)) { "Nesaugus leidimo adresas" }
        require(uri.path == "/$repository/releases/tag/$tag") { "Leidimo adresas nepriklauso nustatytai repozitorijai" }
    }

    private fun validateAssetUrl(url: String, repository: String, tag: String, name: String) {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true)) { "Nesaugus APK adresas" }
        require(uri.path == "/$repository/releases/download/$tag/$name") { "APK adresas nepriklauso nustatytam leidimui" }
    }
}
