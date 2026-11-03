package com.lyufeng.androidtoolkit.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL

internal data class UpdateRelease(
    val version: String,
    val downloadUrl: String,
    val size: Long,
    val sha256: String?,
)

internal class UpdateRepository {
    suspend fun check(currentVersion: String): UpdateRelease? = withContext(Dispatchers.IO) {
        val current = Version.parse(currentVersion)
        val release = fetchLatest() ?: return@withContext null
        try {
            if (release.getBoolean("draft") || release.getBoolean("prerelease")) {
                return@withContext null
            }
            val tag = release.getString("tag_name")
            if (!tag.startsWith("v")) throw IOException("Invalid release tag")
            val versionName = tag.removePrefix("v")
            if (Version.parse(versionName) <= current) return@withContext null

            val assets = release.getJSONArray("assets")
            val available = (0 until assets.length()).map(assets::getJSONObject)
            val asset = APK_NAMES.firstNotNullOfOrNull { name ->
                available.firstOrNull { it.getString("name") == name }
            } ?: throw IOException("Release does not contain an AndroidToolKit APK")

            val name = asset.getString("name")
            val downloadUrl = asset.getString("browser_download_url")
            validateDownloadUrl(downloadUrl, tag, name)
            val size = (asset.get("size") as? Number)?.toString()?.toLongOrNull()
                ?.takeIf { it > 0 } ?: throw IOException("Invalid APK size")
            val sha256 = if (asset.isNull("digest")) {
                null
            } else {
                val digest = asset.getString("digest")
                if (!SHA256_DIGEST.matches(digest)) throw IOException("Invalid APK digest")
                digest.removePrefix("sha256:").lowercase()
            }
            UpdateRelease(versionName, downloadUrl, size, sha256)
        } catch (error: JSONException) {
            throw IOException("Invalid GitHub release metadata", error)
        }
    }

    private fun fetchLatest(): JSONObject? {
        val connection = URL(RELEASE_API).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "AndroidToolKit")
            return when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_FOUND -> null
                HttpURLConnection.HTTP_OK -> {
                    val bytes = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            if (bytes.size() + count > MAX_METADATA_BYTES) {
                                throw IOException("GitHub release metadata is too large")
                            }
                            bytes.write(buffer, 0, count)
                        }
                    }
                    try {
                        JSONObject(bytes.toString(Charsets.UTF_8.name()))
                    } catch (error: JSONException) {
                        throw IOException("Invalid GitHub release metadata", error)
                    }
                }
                else -> throw IOException("GitHub returned HTTP $status")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun validateDownloadUrl(url: String, tag: String, name: String) {
        val uri = try {
            URI(url)
        } catch (error: URISyntaxException) {
            throw IOException("Invalid APK download URL", error)
        }
        if (uri.scheme != "https" || !uri.host.equals("github.com", ignoreCase = true) ||
            uri.port != -1 || uri.userInfo != null || uri.rawQuery != null ||
            uri.rawFragment != null || uri.rawPath != "/$REPOSITORY/releases/download/$tag/$name"
        ) {
            throw IOException("Invalid APK download URL")
        }
    }

    private data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
        override fun compareTo(other: Version): Int =
            compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

        companion object {
            fun parse(value: String): Version {
                if (!VERSION.matches(value)) throw IOException("Invalid version: $value")
                val parts = value.split('.').map {
                    it.toIntOrNull() ?: throw IOException("Invalid version: $value")
                }
                return Version(parts[0], parts[1], parts.getOrElse(2) { 0 })
            }
        }
    }

    private companion object {
        const val REPOSITORY = "lyufeng000/AndroidToolKit"
        const val RELEASE_API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
        const val MAX_METADATA_BYTES = 2 * 1024 * 1024
        val APK_NAMES = listOf("AndroidToolKit.apk", "AndroidToolKit-debug.apk")
        val VERSION = Regex("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?")
        val SHA256_DIGEST = Regex("sha256:[0-9a-fA-F]{64}")
    }
}
