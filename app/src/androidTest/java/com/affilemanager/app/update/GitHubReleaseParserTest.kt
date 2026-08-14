package com.affilemanager.app.update

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubReleaseParserTest {
    private val digest = "a".repeat(64)

    @Test
    fun acceptsMatchingStableApkWithGitHubDigest() {
        val release = GitHubReleaseParser.parse(json(), "sinegard/AF-File-Manager")

        assertEquals("0.9.5", release.version)
        assertEquals("AF-File-Manager-0.9.5.apk", release.asset.name)
        assertEquals(digest, release.asset.sha256)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAssetFromAnotherRepository() {
        GitHubReleaseParser.parse(
            json().replace("sinegard/AF-File-Manager/releases/download", "attacker/Other/releases/download"),
            "sinegard/AF-File-Manager",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsReleaseWithoutSha256Digest() {
        GitHubReleaseParser.parse(json().replace("sha256:$digest", ""), "sinegard/AF-File-Manager")
    }

    private fun json(): String = """
        {
          "tag_name": "v0.9.5",
          "html_url": "https://github.com/sinegard/AF-File-Manager/releases/tag/v0.9.5",
          "draft": false,
          "prerelease": false,
          "body": "Saugus leidimas",
          "assets": [
            {
              "name": "AF-File-Manager-0.9.5.apk",
              "browser_download_url": "https://github.com/sinegard/AF-File-Manager/releases/download/v0.9.5/AF-File-Manager-0.9.5.apk",
              "size": 1024,
              "digest": "sha256:$digest"
            }
          ]
        }
    """.trimIndent()
}
