package com.lyufeng.androidtoolkit.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal class UpdateApkProvider : FileProvider()

internal class UpdateInstaller(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.cacheDir, "app-updates")
    private val staging = File(app.cacheDir, "app-update-downloads")

    suspend fun download(release: UpdateRelease): File = withContext(Dispatchers.IO) {
        require(release.size in 1..MAX_APK_BYTES) { "更新 APK 大小无效" }
        val expectedDigest = release.sha256?.removePrefix("sha256:")?.lowercase()
        require(expectedDigest == null || expectedDigest.matches(SHA256_PATTERN)) { "更新 APK 的 SHA-256 无效" }
        check(directory.isDirectory || directory.mkdirs() || directory.isDirectory) { "无法创建更新缓存目录" }
        check(staging.isDirectory || staging.mkdirs() || staging.isDirectory) { "无法创建下载缓存目录" }
        val digest = expectedDigest?.let { MessageDigest.getInstance("SHA-256") }
        val partial = File.createTempFile("update-", ".apk", staging)
        try {
            val connection = openDownload(release.downloadUrl)
            try {
                val contentLength = connection.getHeaderField("Content-Length")?.toLongOrNull()
                check(contentLength == null || contentLength == release.size) { "更新 APK 大小与发布记录不一致" }
                connection.inputStream.use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytes = 0L
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            bytes += count
                            check(bytes <= release.size) { "更新 APK 超出发布记录中的大小" }
                            digest?.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        check(bytes == release.size) { "更新 APK 下载不完整" }
                    }
                }
            } finally {
                connection.disconnect()
            }
            if (digest != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual == expectedDigest) { "更新 APK 的 SHA-256 校验失败" }
            }
            ensureActive()
            verifyApk(partial, release.version)
            ensureActive()
            val apk = File(directory, "AndroidToolKit-update.apk")
            check(partial.renameTo(apk)) { "无法保存更新 APK" }
            apk
        } finally {
            partial.delete()
        }
    }

    fun install(file: File): Boolean {
        val apk = file.canonicalFile
        require(apk.parentFile == directory.canonicalFile && apk.extension == "apk" && apk.isFile) {
            "更新 APK 已失效，请重新下载"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", app.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.update-apk", apk)
        app.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        return true
    }

    private fun openDownload(url: String): HttpsURLConnection {
        var destination = URI(url)
        repeat(MAX_REDIRECTS + 1) { redirect ->
            val host = destination.host?.lowercase()
            val githubRelease = host == "github.com" &&
                destination.path?.startsWith(RELEASE_PATH, ignoreCase = true) == true &&
                destination.path.split('/').none { it == "." || it == ".." }
            check(
                destination.scheme.equals("https", ignoreCase = true) &&
                    destination.rawUserInfo == null && destination.rawFragment == null &&
                    destination.port in setOf(-1, 443) &&
                    (githubRelease || (redirect > 0 && host in REDIRECT_HOSTS)),
            ) { "更新 APK 下载地址不受支持" }

            val connection = (destination.toURL().openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                val status = connection.responseCode
                if (status == HttpURLConnection.HTTP_OK) return connection
                check(status in REDIRECT_STATUS && redirect < MAX_REDIRECTS) { "更新 APK 下载失败（HTTP $status）" }
                val location = connection.getHeaderField("Location") ?: error("更新 APK 跳转地址缺失")
                destination = destination.resolve(location)
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            connection.disconnect()
        }
        error("更新 APK 跳转次数过多")
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(file: File, releaseVersion: String) {
        val manager = app.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val current = manager.getPackageInfo(app.packageName, flags)
        val candidate = manager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("无法读取更新 APK")
        check(candidate.packageName == app.packageName) { "更新 APK 的应用包名不匹配" }
        check(candidate.versionName == releaseVersion) { "更新 APK 的版本名称与发布标签不一致" }
        check(versionCode(candidate) > versionCode(current)) { "更新 APK 的版本号必须高于当前版本" }
        val installedSigners = signers(current)
        check(installedSigners.isNotEmpty() && signers(candidate) == installedSigners) { "更新 APK 的签名不匹配" }
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        info.versionCode.toLong()
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.signingInfo?.apkContentsSigners.orEmpty().toSet()
    } else {
        info.signatures.orEmpty().toSet()
    }

    private companion object {
        const val MAX_APK_BYTES = 512L * 1024 * 1024
        const val MAX_REDIRECTS = 5
        const val RELEASE_PATH = "/lyufeng000/AndroidToolKit/releases/download/"
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        val REDIRECT_HOSTS = setOf(
            "release-assets.githubusercontent.com",
            "objects.githubusercontent.com",
            "github-releases.githubusercontent.com",
        )
        val REDIRECT_STATUS = setOf(301, 302, 303, 307, 308)
    }
}
