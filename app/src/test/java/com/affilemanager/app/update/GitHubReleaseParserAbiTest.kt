package com.affilemanager.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubReleaseParserAbiTest {
    @Test
    fun selectsTheFirstPublishedApkMatchingTheDeviceAbi() {
        val release = GitHubReleaseParser.parse(
            releaseJson(
                "AF-File-Manager-0.41.0-x86_64.apk",
                "AF-File-Manager-0.41.0-arm64-v8a.apk",
                "AF-File-Manager-0.41.0.apk",
            ),
            REPOSITORY,
            listOf("arm64-v8a", "armeabi-v7a"),
        )
        assertEquals("AF-File-Manager-0.41.0-arm64-v8a.apk", release.asset.name)
    }

    @Test
    fun universalApkRemainsABackwardCompatibleFallback() {
        val release = GitHubReleaseParser.parse(
            releaseJson("AF-File-Manager-0.41.0.apk"),
            REPOSITORY,
            listOf("arm64-v8a"),
        )
        assertEquals("AF-File-Manager-0.41.0.apk", release.asset.name)
    }

    @Test
    fun refusesAnApkForAnotherArchitecture() {
        assertThrows(IllegalArgumentException::class.java) {
            GitHubReleaseParser.parse(
                releaseJson("AF-File-Manager-0.41.0-x86.apk"),
                REPOSITORY,
                listOf("arm64-v8a"),
            )
        }
    }

    private fun releaseJson(vararg assets: String): String {
        val assetJson = assets.joinToString(",") { name ->
            """{"name":"$name","size":1234,"browser_download_url":"https://github.com/$REPOSITORY/releases/download/v0.41.0/$name","digest":"sha256:${"a".repeat(64)}"}"""
        }
        return """{"draft":false,"prerelease":false,"tag_name":"v0.41.0","html_url":"https://github.com/$REPOSITORY/releases/tag/v0.41.0","body":"notes","assets":[$assetJson]}"""
    }

    private companion object {
        const val REPOSITORY = "sinegard/AF-File-Manager"
    }
}
