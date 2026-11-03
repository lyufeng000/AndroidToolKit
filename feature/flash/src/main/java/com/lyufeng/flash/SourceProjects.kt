package com.lyufeng.flash

import org.json.JSONObject
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

object SourceProjects {
    val cpus = setOf("cortex-m0", "cortex-m0plus", "cortex-m1", "cortex-m3", "cortex-m4", "cortex-m7", "cortex-m23", "cortex-m33", "cortex-m35p", "cortex-m55", "cortex-m85")

    fun discover(root: File, files: List<File>): List<BuildPlan> {
        val manifests = files.filter { it.name == "toolkit-build.json" }
        if (manifests.isNotEmpty()) return manifests.map { manifest(it, root) }
        return files.filter { it.name == ".cproject" }.flatMap { cube(it, root) }
    }
    private fun path(base: File, root: File, value: String): File {
        val normalized = value.replace('\\', '/')
        require(!normalized.startsWith('/') && !normalized.contains(':') && !normalized.contains('$')) { "工程路径需要位于导入目录内：$value" }
        val file = File(base, normalized).canonicalFile
        require(file == root.canonicalFile || file.path.startsWith(root.canonicalPath + File.separator)) { "工程引用了导入目录以外的文件：$value" }
        return file
    }
    private fun JSONObject.strings(name: String): List<String> {
        val array = optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).map { array.getString(it) }
    }
    fun manifest(file: File, root: File): BuildPlan {
        require(file.length() < 1024 * 1024) { "编译配置过大" }
        val json = JSONObject(file.readText())
        require(json.getInt("version") == 1) { "不支持此编译配置版本" }
        val base = file.parentFile!!
        val cpu = json.getString("cpu")
        require(cpu in cpus) { "当前源码编译支持 Cortex-M，未知 CPU：$cpu" }
        val sources = json.strings("sources").map { path(base, root, it) }
        require(sources.isNotEmpty() && sources.size <= 4000) { "请在 toolkit-build.json 中列出源文件" }
        require(sources.all { it.isFile && it.extension.lowercase() in FirmwareFiles.sourceExtensions }) { "编译配置引用了不存在或不支持的源文件" }
        val includes = json.strings("includeDirs").map { path(base, root, it) }
        require(includes.all { it.isDirectory }) { "编译配置引用的头文件目录不存在" }
        val linker = path(base, root, json.getString("linkerScript"))
        require(linker.isFile && linker.extension == "ld") { "请提供 GNU 格式的 .ld 链接脚本" }
        val libraries = json.strings("libraries").map { path(base, root, it) }
        require(libraries.all { it.isFile && it.extension == "a" }) { "静态库不存在或格式无效" }
        return BuildPlan(json.optString("name", base.name), base, sources, includes, json.strings("defines"), linker, cpu,
            checkedFlags(json.strings("flags")), libraries, checkedLinkFlags(json.strings("linkFlags")))
    }
    fun checkedFlags(flags: List<String>): List<String> {
        val allowed = Regex("-(O[0-3sgz]|g[0-3]?|std=(c|gnu|c\\+\\+|gnu\\+\\+)[0-9]+|f(no-)?(exceptions|rtti|short-enums|short-wchar|common|strict-aliasing)|mfloat-abi=(soft|softfp|hard)|mfpu=[a-zA-Z0-9+_.-]+|W[a-zA-Z0-9=+_.-]+)")
        require(flags.all { it.matches(allowed) }) { "编译参数不受支持，请使用标准优化、语言、FPU 或诊断参数" }
        return flags
    }
    private fun checkedLinkFlags(flags: List<String>): List<String> {
        require(flags.all { it == "--gc-sections" || it.matches(Regex("--(defsym|entry)=[A-Za-z0-9_=x]+")) }) { "链接参数不受支持" }
        return flags
    }
    private fun cube(file: File, root: File): List<BuildPlan> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        val doc = factory.newDocumentBuilder().parse(file)
        val configs = doc.getElementsByTagName("cconfiguration")
        val result = mutableListOf<BuildPlan>()
        for (index in 0 until configs.length) {
            val config = configs.item(index) as Element
            val options = config.getElementsByTagName("option")
            var cpu = ""
            var script = ""
            val includes = linkedSetOf<File>()
            val defines = linkedSetOf<String>()
            val flags = linkedSetOf<String>()
            fun resolve(value: String): File {
                var clean = value.trim('"').replace("\u0024{ProjDirPath}", ".").replace("\u0024{workspace_loc:/\u0024{ProjName}}", ".")
                if (clean.startsWith("../")) clean = clean.removePrefix("../") // CubeIDE build directory is Debug/ or Release/.
                return path(file.parentFile!!, root, clean)
            }
            for (o in 0 until options.length) {
                val option = options.item(o) as Element
                val kind = option.getAttribute("superClass").lowercase()
                val value = option.getAttribute("value").trim('"')
                when {
                    kind.endsWith(".mcpu") || kind.endsWith(".target_mcu") -> if (value in cpus) cpu = value
                    kind.contains("linker") && kind.endsWith(".script") -> script = value
                    kind.endsWith(".definedsymbols") || kind.endsWith(".includepaths") -> {
                        val values = option.getElementsByTagName("listOptionValue")
                        for (v in 0 until values.length) {
                            val item = (values.item(v) as Element).getAttribute("value").trim('"')
                            if (kind.endsWith(".definedsymbols")) defines.add(item) else includes.add(resolve(item))
                        }
                    }
                }
            }
            // CubeIDE commonly stores the CPU choice in the .ioc rather than .cproject.
            if (cpu.isEmpty()) {
                val ioc = file.parentFile!!.listFiles()?.firstOrNull { it.extension == "ioc" }?.readText().orEmpty()
                cpu = Regex("(?m)^Mcu\\.Cpu=(Cortex-M[0-9]+)\\s*$").find(ioc)?.groupValues?.get(1)?.lowercase().orEmpty()
            }
            require(cpu in cpus && script.isNotBlank()) { "无法确定 CubeIDE 的 CPU 或链接脚本，请添加 toolkit-build.json" }
            val entries = config.getElementsByTagName("entry")
            val sourceRoots = mutableListOf<File>()
            val excluded = mutableSetOf<String>()
            for (e in 0 until entries.length) {
                val entry = entries.item(e) as Element
                if (entry.getAttribute("kind") == "sourcePath") {
                    sourceRoots.add(resolve(entry.getAttribute("name").ifBlank { "." }))
                    excluded.addAll(entry.getAttribute("excluding").split('|').filter { it.isNotEmpty() })
                }
            }
            if (sourceRoots.isEmpty()) sourceRoots.add(file.parentFile!!)
            val sources = sourceRoots.flatMap { it.walkTopDown().filter { f ->
                f.isFile && f.extension.lowercase() in FirmwareFiles.sourceExtensions &&
                    f.relativeTo(file.parentFile!!).invariantSeparatorsPath.split('/').none { it in setOf("Debug", "Release", "build", ".git") } &&
                    excluded.none { ex -> f.relativeTo(file.parentFile!!).invariantSeparatorsPath.startsWith(ex.trimEnd('/')) }
            }.toList() }.distinct()
            val name = (config.getElementsByTagName("configuration").item(0) as? Element)?.getAttribute("name") ?: "CubeIDE"
            result.add(BuildPlan("CubeIDE · $name", file.parentFile!!, sources, includes.toList(), defines.toList(), resolve(script), cpu, flags.toList()))
        }
        return result
    }
}
