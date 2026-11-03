package com.lyufeng.flash

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

class ImportBudget {
    private var bytes = 0L
    private var files = 0
    fun entry() { require(++files <= 12000) { "项目超过 12000 个文件" } }
    fun consume(count: Int) { bytes += count; require(bytes <= 512L * 1024 * 1024) { "项目展开后超过 512 MB" } }
    fun copy(input: InputStream, target: File) {
        target.parentFile!!.mkdirs()
        target.outputStream().use { out ->
            val buffer = ByteArray(32768)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val count = input.read(buffer)
                if (count < 0) break
                consume(count); out.write(buffer, 0, count)
            }
        }
    }
}

object ArchiveImport {
    fun safeFile(root: File, name: String): File {
        require(name.isNotBlank() && !name.startsWith('/') && !name.contains('\\') && !name.contains(':')) { "项目文件路径无效" }
        require(name.split('/').none { it == ".." || it == "." }) { "项目包含越界路径" }
        require(name.none { it == '{' || it == '}' || it.code < 32 }) { "项目文件名含有不支持的字符" }
        require(name.split('/').size <= 32) { "项目目录超过 32 层" }
        val file = File(root, name).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator)) { "项目包含越界路径" }
        return file
    }
    fun unzip(input: InputStream, root: File, budget: ImportBudget = ImportBudget()) {
        val names = HashSet<String>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                budget.entry()
                val file = safeFile(root, entry.name.trimEnd('/'))
                require(names.add(file.canonicalPath.lowercase())) { "ZIP 包含重复文件名" }
                if (entry.isDirectory) file.mkdirs() else budget.copy(zip, file)
                zip.closeEntry()
            }
        }
    }
}

class ProjectImporter(private val context: Context) {
    fun import(uri: Uri, directory: Boolean): ImportedProject {
        val root = File(context.filesDir, "flash/projects/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val label: String
            if (directory) {
                val tree = DocumentFile.fromTreeUri(context, uri) ?: error("无法读取目录")
                label = tree.name ?: "项目目录"
                copyTree(tree, root, "", ImportBudget(), 0)
            } else {
                label = displayName(uri)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    if (label.endsWith(".zip", true)) ArchiveImport.unzip(input, root)
                    else {
                        val ext = label.substringAfterLast('.', "").lowercase()
                        require(ext in FirmwareFiles.extensions) { "请选择 ZIP、固件文件或项目目录。其他压缩格式请先解压。" }
                        ImportBudget().copy(input, File(root, "firmware.$ext"))
                    }
                } ?: error("无法读取所选文件")
            }
            return inspect(root, label)
        } catch (error: Exception) { root.deleteRecursively(); throw error }
    }
    fun inspect(root: File, label: String): ImportedProject {
        val all = FirmwareFiles.scan(root)
        require(all.isNotEmpty()) { "项目目录为空" }
        val images = all.filter { it.extension.lowercase() in FirmwareFiles.extensions }.sortedBy { it.relativeTo(root).path }
        val sources = all.filter { it.extension.lowercase() in FirmwareFiles.sourceExtensions }
        val plans = runCatching { SourceProjects.discover(root, all) }
        return ImportedProject(root, label, images, sources, plans.getOrDefault(emptyList()), plans.exceptionOrNull()?.message)
    }
    private fun copyTree(tree: DocumentFile, root: File, prefix: String, budget: ImportBudget, depth: Int) {
        require(depth < 32) { "项目目录超过 32 层" }
        val seen = HashSet<String>()
        for (child in tree.listFiles()) {
            budget.entry()
            val name = child.name ?: error("无法读取文件名")
            require(!name.contains('/')) { "项目文件名无效" }
            require(seen.add(name.lowercase())) { "目录包含重复文件名" }
            val relative = prefix + name
            val dest = ArchiveImport.safeFile(root, relative)
            if (child.isDirectory) copyTree(child, root, "$relative/", budget, depth + 1)
            else context.contentResolver.openInputStream(child.uri)?.use { budget.copy(it, dest) } ?: error("无法读取 $name")
        }
    }
    private fun displayName(uri: Uri): String = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: uri.lastPathSegment ?: "文件"
}
