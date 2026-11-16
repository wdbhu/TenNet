package com.example.kernelsustyleuikit.ui.util

import com.example.kernelsustyleuikit.templateApp
import okhttp3.Request

fun checkNewVersion(): LatestVersionInfo {
    if (!isNetworkAvailable(templateApp)) return LatestVersionInfo()
    val url = "https://api.github.com/repos/wdbhu/TenNet/releases/latest"
    // default null value if failed
    val defaultValue = LatestVersionInfo()
    runCatching {
        templateApp.okhttpClient.newCall(Request.Builder().url(url).build()).execute()
            .use { response ->
                if (!response.isSuccessful) {
                    return defaultValue
                }
                val body = response.body.string()
                val json = org.json.JSONObject(body)
                val changelog = json.optString("body")

                val assets = json.getJSONArray("assets")
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    if (!name.endsWith(".apk")) {
                        continue
                    }

                    // 兼容多种命名，只要能抠出「版本名」和「版本码」即可：
                    //   KernelSUStyleUIKit_v7.18_718-release.apk
                    //   TenNet_v7.18_718.apk
                    //   TenNet-7.18-718.apk
                    val regex = Regex("v?(\\d+(?:\\.\\d+)*)[_\\-](\\d+)")
                    val matchResult = regex.find(name) ?: continue
                    val versionCode = matchResult.groupValues[2].toIntOrNull() ?: continue
                    val downloadUrl = asset.getString("browser_download_url")

                    return LatestVersionInfo(
                        versionCode,
                        downloadUrl,
                        changelog
                    )
                }

            }
    }
    return defaultValue
}
