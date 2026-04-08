package com.tungsten.fclauncher.plugins

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import com.mio.data.Renderer
import com.tungsten.fclauncher.utils.FCLPath
import org.json.JSONObject
import java.io.File

object RendererPlugin {
    private var isInit = false
    private const val PACKAGE_FLAGS = PackageManager.GET_META_DATA or PackageManager.GET_SHARED_LIBRARY_FILES

    @JvmStatic
    val rendererList: MutableList<Renderer> = mutableListOf()
        get() {
            if (!isInit) {
                init(FCLPath.CONTEXT)
            }
            return field
        }

    @JvmStatic
    fun init(context: Context) {
        isInit = true
        val queryIntentActivities = context.packageManager.queryIntentActivities(
            Intent("android.intent.action.MAIN"),
            PACKAGE_FLAGS
        )
        queryIntentActivities.forEach { parse(it.activityInfo.applicationInfo) }
    }

    @JvmStatic
    fun isAvailable(): Boolean {
        return rendererList.isNotEmpty()
    }

    @JvmStatic
    fun refresh(context: Context) {
        rendererList.clear()
        isInit = false
        init(context)
    }

    private fun parse(info: ApplicationInfo) {
        if (info.flags and ApplicationInfo.FLAG_SYSTEM == 0) {
            val metaData = info.metaData ?: return
            if (metaData.getBoolean("fclPlugin", false)) {
                val rendererString = metaData.getString("renderer") ?: return
                val des = metaData.getString("des") ?: return
                
                // 从 meta-data 读取默认环境变量
                val boatEnvString = metaData.getString("boatEnv") ?: ""
                val pojavEnvString = metaData.getString("pojavEnv") ?: ""

                val externalLibDir = metaData.getString("nativeLibraryDir")
                // 处理 nativeLibraryDir，如果是外部路径则复制
                val nativeLibraryDir = if (externalLibDir != null && isExternalPath(externalLibDir)) {
                    copyExternalLibs(FCLPath.CONTEXT, externalLibDir)
                } else {
                    externalLibDir ?: info.nativeLibraryDir
                }

                val externalJsonDir = metaData.getString("jsonConfigDir")
                // 处理 jsonConfigDir，如果是外部路径则复制
                val jsonConfigDir = if (externalJsonDir != null && isExternalPath(externalJsonDir)) {
                    copyExternalJsonConfigs(FCLPath.CONTEXT, externalJsonDir)
                } else {
                    externalJsonDir
                }

                val renderer = rendererString.split(":")
                if (renderer.size < 3) return

                // 解析环境变量列表
                var boatEnv = if (boatEnvString.isNotEmpty()) boatEnvString.split(":") else emptyList()
                var pojavEnv = if (pojavEnvString.isNotEmpty()) pojavEnvString.split(":") else emptyList()

                // ✅ 从 JSON 文件读取环境变量配置（覆盖 meta-data）
                if (jsonConfigDir != null) {
                    parseEnvFromJson(jsonConfigDir)?.let { (jsonBoatEnv, jsonPojavEnv) ->
                        if (jsonBoatEnv.isNotEmpty()) boatEnv = jsonBoatEnv
                        if (jsonPojavEnv.isNotEmpty()) pojavEnv = jsonPojavEnv
                    }
                }

                val minMCVer = metaData.safeGetString("minMCVer") ?: ""
                val maxMCVer = metaData.safeGetString("maxMCVer") ?: ""
                
                addRenderer(
                    Renderer(
                        renderer[0],
                        des,
                        renderer[1],
                        renderer[2],
                        nativeLibraryDir,
                        boatEnv,
                        pojavEnv,
                        info.packageName,
                        minMCVer,
                        maxMCVer
                    )
                )
            }
        }
    }

    /**
     * 从 JSON 文件解析环境变量配置
     * 返回 (boatEnv, pojavEnv)
     */
    private fun parseEnvFromJson(configDir: String): Pair<List<String>, List<String>>? {
        val dir = File(configDir)
        if (!dir.exists() || !dir.isDirectory) return null

        // 查找第一个 .json 文件
        val jsonFile = dir.listFiles()?.firstOrNull { it.extension == "json" } ?: return null

        return try {
            val json = JSONObject(jsonFile.readText())
            val envJson = json.optJSONObject("env")

            val boatEnv = envJson?.optJSONArray("boat")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()

            val pojavEnv = envJson?.optJSONArray("pojav")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()

            Pair(boatEnv, pojavEnv)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun isExternalPath(path: String): Boolean {
        return path.startsWith("/storage/emulated/") ||
                path.startsWith("/sdcard/") ||
                path.startsWith("/mnt/sdcard/")
    }

    private fun copyExternalLibs(context: Context, externalPath: String): String {
        // ✅ 去掉末尾的斜杠，防止目录名变成空字符串
        val normalizedPath = externalPath.trimEnd('/')
        
        val externalDir = File(normalizedPath)
        if (!externalDir.exists() || !externalDir.isDirectory) {
            return externalPath
        }

        // ✅ 使用父目录名作为目标目录，避免路径问题
        val targetDir = File(context.filesDir, "external_renderers/${externalDir.name}")
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        externalDir.listFiles()?.forEach { sourceFile ->
            if (sourceFile.extension == "so") {
                val targetFile = File(targetDir, sourceFile.name)
                try {
                    sourceFile.copyTo(targetFile, overwrite = true)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return targetDir.absolutePath
    }

    private fun copyExternalJsonConfigs(context: Context, externalPath: String): String {
        // ✅ 去掉末尾的斜杠
        val normalizedPath = externalPath.trimEnd('/')
        
        val externalDir = File(normalizedPath)
        if (!externalDir.exists() || !externalDir.isDirectory) {
            return externalPath
        }

        val targetDir = File(context.filesDir, "external_json_configs/${externalDir.name}")
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        externalDir.listFiles()?.forEach { sourceFile ->
            if (sourceFile.extension == "json") {
                val targetFile = File(targetDir, sourceFile.name)
                try {
                    sourceFile.copyTo(targetFile, overwrite = true)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return targetDir.absolutePath
    }

    private fun addRenderer(renderer: Renderer) {
        rendererList.removeIf { it.id == renderer.id }
        rendererList.add(renderer)
    }

    private fun Bundle.safeGetString(key: String): String? {
        return if (containsKey(key)) {
            runCatching { getString(key) }.getOrNull()
                ?: runCatching { getFloat(key).toString() }.getOrNull()
        } else null
    }
}