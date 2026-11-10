package cn.paperforge.network

import android.content.Context
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*
import org.json.*

data class UpdateCandidate(
    val packageName: String,
    val title: String,
    val versionName: String,
    val versionCode: Long,
    val fileName: String,
    val bytes: Long,
    val sha256: String,
    val assetUrl: String,
    val releaseUrl: String,
    val channel: String,
    val abis: List<String>,
    /** Other places to fetch the same file from, tried in order when [assetUrl] fails. */
    val mirrors: List<String> = emptyList(),
) {
    fun json() =
        JSONObject()
            .put("mirrors", JSONArray(mirrors))
            .put("packageName", packageName)
            .put("title", title)
            .put("versionName", versionName)
            .put("versionCode", versionCode)
            .put("fileName", fileName)
            .put("bytes", bytes)
            .put("sha256", sha256)
            .put("assetUrl", assetUrl)
            .put("releaseUrl", releaseUrl)
            .put("channel", channel)
            .put("abis", JSONArray(abis))

    companion object {
        fun decode(j: JSONObject) =
            UpdateCandidate(
                j.getString("packageName"),
                j.getString("title"),
                j.getString("versionName"),
                j.optLong("versionCode", -1),
                j.getString("fileName"),
                j.getLong("bytes"),
                j.getString("sha256"),
                j.getString("assetUrl"),
                j.getString("releaseUrl"),
                j.getString("channel"),
                j.getJSONArray("abis").let { a -> List(a.length()) { a.getString(it) } },
                j.optJSONArray("mirrors")
                    ?.let { a -> List(a.length()) { a.getString(it) } }
                    .orEmpty(),
            )
    }
}

data class InstalledPackage(val versionCode: Long, val versionName: String)

data class UpdateCheck(
    val candidates: List<UpdateCandidate>,
    val releases: Int,
    val warnings: List<String>,
)

/** Private assets use the API endpoint. Authorization never follows a redirect to another host. */
class GitHubUpdates(
    private val token: String,
    private val apiRoot: String = "https://api.github.com",
    private val integrated: Boolean = false,
    /**
     * Where public release files are served, and the mirrors in front of it; tests point these at a
     * local server.
     */
    private val webRoot: String = "https://github.com",
    private val mirrors: List<String> = mirrorPrefixes,
) {
    /** [path] on [webRoot], then the same file through each mirror, in the order to try them. */
    private fun sources(path: String): List<String> =
        (webRoot + path).let { direct -> listOf(direct) + mirrors.map { it + direct } }

    companion object {
        val packages =
            linkedMapOf(
                "cn.paperforge.app" to "PaperForge",
                "cn.paperforge.office" to "Office 引擎",
                "cn.paperforge.formats" to "格式引擎",
                "cn.paperforge.network" to "联网工具",
            )

        @Suppress("DEPRECATION")
        fun installed(context: Context): Map<String, InstalledPackage> =
            (if (NetworkFiles.integrated(context)) listOf(context.packageName) else packages.keys)
                .mapNotNull { id ->
                    runCatching { context.packageManager.getPackageInfo(id, 0) }
                        .getOrNull()
                        ?.let { info ->
                            id to
                                InstalledPackage(
                                    if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
                                    else info.versionCode.toLong(),
                                    info.versionName.orEmpty(),
                                )
                        }
                }
                .toMap()

        fun compareVersions(a: String, b: String): Int {
            fun parse(text: String): Pair<List<Long>, List<String>> {
                val match =
                    Regex("^[vV]?(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:-([^+]+))?(?:\\+.*)?$")
                        .matchEntire(text.trim()) ?: error("无法比较版本：$text")
                return (1..3).map { match.groupValues[it].ifEmpty { "0" }.toLong() } to
                    match.groupValues[4].split('.').filter { it.isNotEmpty() }
            }
            val (x, xp) = parse(a)
            val (y, yp) = parse(b)
            for (i in 0..2) if (x[i] != y[i]) return x[i].compareTo(y[i])
            if (xp.isEmpty() || yp.isEmpty())
                return if (xp.isEmpty() && yp.isEmpty()) 0 else if (xp.isEmpty()) 1 else -1
            for (i in 0 until minOf(xp.size, yp.size)) if (xp[i] != yp[i]) {
                val xn = xp[i].toLongOrNull()
                val yn = yp[i].toLongOrNull()
                return if (xn != null && yn != null) xn.compareTo(yn)
                else if (xn != null) -1 else if (yn != null) 1 else xp[i].compareTo(yp[i])
            }
            return xp.size.compareTo(yp.size)
        }

        fun compatible(abis: List<String>, supported: List<String>) =
            abis.isEmpty() || abis.any { it in supported }

        /**
         * Public GitHub download proxies that are usually reachable from mainland China, where
         * github.com often stalls. They only carry the bytes: every file is still checked against
         * the release's SHA256, and an APK must also carry PaperForge's own signature, so a proxy
         * cannot slip in a different file.
         */
        val mirrorPrefixes =
            listOf("https://ghfast.top/", "https://gh-proxy.com/", "https://ghproxy.net/")
    }

    suspend fun connect(
        address: String,
        binary: Boolean = false,
        range: Long = 0,
        timeoutMillis: Int = 15000,
    ): HttpURLConnection {
        var url = URL(address)
        repeat(6) {
            currentCoroutineContext().ensureActive()
            require(url.protocol == "https" || url.host in setOf("127.0.0.1", "localhost", "::1")) {
                "更新地址必须使用 HTTPS"
            }
            val connection =
                (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    // A manual check must revalidate Android/proxy caches after a new release.
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache, max-age=0")
                    setRequestProperty("Pragma", "no-cache")
                    connectTimeout = timeoutMillis
                    readTimeout = timeoutMillis
                    setRequestProperty(
                        "Accept",
                        if (binary) "application/octet-stream" else "application/vnd.github+json",
                    )
                    setRequestProperty("User-Agent", "PaperForge-Network/1")
                    setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
                    if (
                        url.host == "api.github.com" &&
                            url.port in setOf(-1, 443) &&
                            token.isNotBlank()
                    )
                        setRequestProperty("Authorization", "Bearer $token")
                    if (range > 0) setRequestProperty("Range", "bytes=$range-")
                    setRequestProperty("Accept-Encoding", "identity")
                }
            val status =
                try {
                    connection.responseCode
                } catch (e: Exception) {
                    connection.disconnect()
                    throw e
                }
            if (status in setOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location") ?: error("更新下载重定向缺少地址")
                val next = URL(url, location)
                connection.disconnect()
                require(
                    next.protocol == "https" || next.host == url.host && url.protocol == "http"
                ) {
                    "更新下载不接受降级连接"
                }
                url = next
            } else {
                if (status !in 200..299) {
                    connection.disconnect()
                    error(
                        when (status) {
                            401 -> "GitHub 访问令牌无效或已过期"
                            403,
                            429 -> "GitHub 权限或请求限额不足，稍后重试"
                            404 -> "仓库或附件不存在，私有仓库请配置 Contents 只读令牌"
                            else -> "检查/下载未完成（HTTP $status）"
                        }
                    )
                }
                return connection
            }
        }
        error("更新下载重定向次数过多")
    }

    private suspend fun text(
        url: String,
        binary: Boolean = false,
        timeoutMillis: Int = 15000,
    ): String {
        val connection = connect(url, binary, timeoutMillis = timeoutMillis)
        try {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The newest installable files. When the GitHub API cannot be reached, as often happens on an
     * unsteady line in mainland China, a public repository's latest release is read from its own
     * release manifest, fetched from github.com or a mirror.
     */
    suspend fun check(
        repository: String,
        channel: String,
        installed: Map<String, InstalledPackage>,
        supported: List<String> = Build.SUPPORTED_ABIS.toList(),
    ): UpdateCheck {
        val parts = repository.trim().split('/')
        require(parts.size == 2 && parts.all { it.matches(Regex("[A-Za-z0-9_.-]+")) }) {
            "仓库格式应为 用户名/仓库名"
        }
        val api =
            try {
                checkApi(parts, channel, installed, supported)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (token.isNotBlank() || channel != "stable") throw e
                return checkLatest(parts, installed, supported, apiAvailable = false) ?: throw e
            }
        if (api.candidates.isNotEmpty() || token.isNotBlank() || channel != "stable") return api
        // A successful, cached API response can still omit a just-published release. Do not call
        // the installation current until the public latest manifest has also been checked.
        val latest = checkLatest(parts, installed, supported, apiAvailable = true)
        return if (latest == null)
            api.copy(warnings = (api.warnings + "无法核对最新发布清单，请稍后重试").distinct())
        else
            UpdateCheck(
                latest.candidates,
                maxOf(api.releases, latest.releases),
                (api.warnings + latest.warnings).distinct(),
            )
    }

    /**
     * Compare quick responses in parallel: a cached mirror must not win just by answering first.
     */
    private suspend fun checkLatest(
        parts: List<String>,
        installed: Map<String, InstalledPackage>,
        supported: List<String>,
        apiAvailable: Boolean,
    ): UpdateCheck? {
        val base = "/${parts[0]}/${parts[1]}/releases/latest/download/"
        val attempts = coroutineScope {
            sources(base + "release-manifest.json")
                .map { source ->
                    async(Dispatchers.IO) {
                        try {
                            Result.success(JSONObject(text(source, true, timeoutMillis = 2000)))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Result.failure<JSONObject>(e)
                        }
                    }
                }
                .awaitAll()
        }
        var checked = false
        val warnings = mutableListOf<String>()
        val candidates = mutableListOf<UpdateCandidate>()
        for (attempt in attempts) {
            currentCoroutineContext().ensureActive()
            val data = attempt.getOrNull() ?: continue
            try {
                val items = data.getJSONArray("artifacts")
                if (integrated && data.optString("distribution") != "full") {
                    warnings += "最新发布清单不含完整应用，无法确认是否为最新版本"
                    continue
                }
                val groupChannel = data.optString("channel", "stable")
                if (groupChannel != "stable") {
                    warnings += "最新发布清单不属于稳定渠道，无法确认是否为最新版本"
                    continue
                }
                var matchingPackage = false
                for (j in 0 until items.length()) {
                    val item = items.getJSONObject(j)
                    val id = item.getString("applicationId")
                    if (id !in packages || integrated && id != "cn.paperforge.app") continue
                    matchingPackage = true
                    val code = item.getLong("versionCode")
                    if (code <= (installed[id]?.versionCode ?: 0)) continue
                    val abiArray = item.getJSONArray("abis")
                    val abis = List(abiArray.length()) { abiArray.getString(it) }
                    if (!compatible(abis, supported)) {
                        warnings += "最新版本没有适配当前设备架构的安装包"
                        continue
                    }
                    val hash = item.getString("sha256").lowercase()
                    require(hash.matches(Regex("[0-9a-f]{64}"))) { "最新发布清单缺少有效的文件哈希" }
                    val file = item.getString("file")
                    val versionName = item.getString("versionName")
                    // Format-1 PaperForge releases use v<versionName>; an explicit tag also works.
                    val tag = data.optString("tag").ifBlank { "v$versionName" }
                    require(tag.matches(Regex("[A-Za-z0-9._-]+"))) { "最新发布清单的版本标签无效" }
                    val urls = sources("/${parts[0]}/${parts[1]}/releases/download/$tag/$file")
                    candidates +=
                        UpdateCandidate(
                            id,
                            packages.getValue(id),
                            versionName,
                            code,
                            file,
                            item.getLong("bytes"),
                            hash,
                            urls.first(),
                            "$webRoot/${parts[0]}/${parts[1]}/releases/tag/$tag",
                            groupChannel,
                            abis,
                            urls.drop(1),
                        )
                }
                if (!matchingPackage) {
                    warnings += "最新发布清单没有当前应用的信息，无法确认是否为最新版本"
                    continue
                }
                checked = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One stale, incomplete or unreachable mirror must not hide a newer valid one.
                if (e is JSONException || e is IllegalArgumentException)
                    warnings += "最新发布清单未能完整核对，请稍后重试"
            }
        }
        if (attempts.any { it.isFailure } && candidates.isEmpty() && checked)
            warnings += "部分更新源未能及时核对，请稍后重试"
        if (!apiAvailable && checked) warnings += "GitHub 接口暂时无法连接，已通过发布清单检查最新版本"
        return if (checked || warnings.isNotEmpty())
            UpdateCheck(
                candidates
                    .groupBy { it.packageName }
                    .values
                    .map { g -> g.maxBy { it.versionCode } },
                1,
                warnings.distinct(),
            )
        else null
    }

    private suspend fun checkApi(
        parts: List<String>,
        channel: String,
        installed: Map<String, InstalledPackage>,
        supported: List<String>,
    ): UpdateCheck {
        val root = "$apiRoot/repos/${parts[0]}/${parts[1]}"
        val candidates = mutableListOf<UpdateCandidate>()
        val warnings = mutableListOf<String>()
        var page = 1
        var releases = 0
        while (true) {
            val list = JSONArray(text("$root/releases?per_page=100&page=$page"))
            for (i in 0 until list.length()) {
                currentCoroutineContext().ensureActive()
                val release = list.getJSONObject(i)
                if (
                    release.optBoolean("draft") ||
                        channel == "stable" && release.optBoolean("prerelease")
                )
                    continue
                releases++
                val assets = release.getJSONArray("assets")
                val files =
                    (0 until assets.length())
                        .map { assets.getJSONObject(it) }
                        .associateBy { it.getString("name") }
                val tag = release.getString("tag_name")
                val releaseUrl = release.getString("html_url")
                fun url(asset: JSONObject) = "$root/releases/assets/${asset.getLong("id")}"
                // A public repository's files can also be fetched without the API, directly and
                // through mirrors.
                fun mirrors(asset: JSONObject) =
                    if (token.isNotBlank()) emptyList()
                    else
                        sources(
                            "/${parts[0]}/${parts[1]}/releases/download/$tag/${asset.getString("name")}"
                        )
                val manifests =
                    files.values.filter { it.getString("name").endsWith("release-manifest.json") }
                if (manifests.isNotEmpty()) {
                    for (manifest in manifests) {
                        val data = JSONObject(text(url(manifest), true))
                        if (integrated && data.optString("distribution") != "full") continue
                        val groupChannel =
                            data.optString(
                                "channel",
                                if (release.optBoolean("prerelease")) "prerelease" else "stable",
                            )
                        if (groupChannel == "development" && channel != "development") continue
                        val items = data.getJSONArray("artifacts")
                        for (j in 0 until items.length()) {
                            val item = items.getJSONObject(j)
                            val id = item.getString("applicationId")
                            if (id !in packages || integrated && id != "cn.paperforge.app") continue
                            val code = item.getLong("versionCode")
                            if (code <= (installed[id]?.versionCode ?: 0)) continue
                            val abiArray = item.getJSONArray("abis")
                            val abis = List(abiArray.length()) { abiArray.getString(it) }
                            if (!compatible(abis, supported)) {
                                warnings += "$tag 有新版本，但没有适配当前设备架构的安装包"
                                continue
                            }
                            val asset = files[item.getString("file")]
                            if (asset == null) {
                                warnings += "$tag 缺少 ${item.getString("file")}"
                                continue
                            }
                            require(item.getLong("bytes") == asset.getLong("size")) {
                                "$tag 的附件大小与清单不符"
                            }
                            val hash = item.getString("sha256").lowercase()
                            require(hash.matches(Regex("[0-9a-f]{64}"))) { "$tag 缺少有效的文件哈希" }
                            candidates +=
                                UpdateCandidate(
                                    id,
                                    packages.getValue(id),
                                    item.getString("versionName"),
                                    code,
                                    asset.getString("name"),
                                    asset.getLong("size"),
                                    hash,
                                    url(asset),
                                    releaseUrl,
                                    groupChannel,
                                    abis,
                                    mirrors(asset),
                                )
                        }
                    }
                } else {
                    // Older PaperForge releases predate the manifest; use asset digest or
                    // SHA256SUMS.
                    var sums: Map<String, String>? = null
                    for (asset in files.values.filter { it.getString("name").endsWith(".apk") }) {
                        val name = asset.getString("name")
                        if (integrated && !name.contains("-full-")) continue
                        val id =
                            when {
                                name.startsWith("PaperForge-Office") -> "cn.paperforge.office"
                                name.startsWith("PaperForge-Formats") -> "cn.paperforge.formats"
                                name.startsWith("PaperForge-Network") -> "cn.paperforge.network"
                                name.startsWith("PaperForge-") -> "cn.paperforge.app"
                                else -> continue
                            }
                        val newer =
                            installed[id]?.let {
                                runCatching { compareVersions(tag, it.versionName) > 0 }
                                    .getOrElse {
                                        warnings += "$tag 无法与已装版本比较"
                                        false
                                    }
                            } ?: true
                        if (!newer) continue
                        val abis =
                            when {
                                name.contains("arm64") -> listOf("arm64-v8a")
                                name.contains("x86_64") -> listOf("x86_64")
                                else -> emptyList()
                            }
                        if (!compatible(abis, supported)) continue
                        var hash = asset.optString("digest").removePrefix("sha256:").lowercase()
                        if (!hash.matches(Regex("[0-9a-f]{64}"))) {
                            if (sums == null)
                                sums =
                                    files.values
                                        .firstOrNull {
                                            it.getString("name").endsWith("SHA256SUMS.txt")
                                        }
                                        ?.let {
                                            text(url(it), true)
                                                .lineSequence()
                                                .mapNotNull { line ->
                                                    Regex("^([0-9a-fA-F]{64})\\s+[* ]?(.+)$")
                                                        .matchEntire(line.trim())
                                                        ?.let { m ->
                                                            m.groupValues[2] to
                                                                m.groupValues[1].lowercase()
                                                        }
                                                }
                                                .toMap()
                                        } ?: emptyMap()
                            hash = sums[name].orEmpty()
                        }
                        if (!hash.matches(Regex("[0-9a-f]{64}"))) {
                            warnings += "$tag 的 $name 缺少可核对哈希"
                            continue
                        }
                        candidates +=
                            UpdateCandidate(
                                id,
                                packages.getValue(id),
                                tag.removePrefix("v"),
                                -1,
                                name,
                                asset.getLong("size"),
                                hash,
                                url(asset),
                                releaseUrl,
                                if (release.optBoolean("prerelease")) "prerelease" else "stable",
                                abis,
                                mirrors(asset),
                            )
                    }
                }
            }
            if (list.length() < 100) break
            page++
        }
        val chosen =
            candidates
                .groupBy { it.packageName }
                .values
                .map { group ->
                    group
                        .sortedWith { a, b ->
                            val version =
                                if (a.versionCode >= 0 && b.versionCode >= 0)
                                    b.versionCode.compareTo(a.versionCode)
                                else compareVersions(b.versionName, a.versionName)
                            if (version != 0) version else a.bytes.compareTo(b.bytes)
                        }
                        .first()
                }
        return UpdateCheck(chosen, releases, warnings.distinct())
    }
}
