package cn.paperforge.network.lan

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.*

data class LanItem(
    val id: String,
    val name: String,
    val direction: String,
    val total: Long,
    val sha256: String,
    val mime: String,
    val state: String,
    val bytes: Long = 0,
    val peer: String = "",
    val detail: String = "",
    val created: Long = System.currentTimeMillis(),
) {
    fun json() =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("direction", direction)
            .put("total", total)
            .put("sha256", sha256)
            .put("mime", mime)
            .put("state", state)
            .put("bytes", bytes)
            .put("peer", peer)
            .put("detail", detail)
            .put("created", created)

    companion object {
        fun decode(j: JSONObject) =
            LanItem(
                j.getString("id"),
                j.getString("name"),
                j.getString("direction"),
                j.getLong("total"),
                j.optString("sha256"),
                j.optString("mime", "application/octet-stream"),
                j.getString("state"),
                j.optLong("bytes"),
                j.optString("peer"),
                j.optString("detail"),
                j.optLong("created"),
            )
    }
}

data class LanPeer(val id: String, val name: String, val tokenHash: String, val created: Long) {
    fun json() =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("tokenHash", tokenHash)
            .put("created", created)
}

/** Durable queue copies belong to this optional tool; original document providers are read-only. */
class LanLedger private constructor(private val context: Context) {
    val root = File(context.filesDir, "lan").apply { mkdirs() }
    private val atomic = AtomicFile(File(root, "ledger.json"))
    val items = MutableStateFlow<List<LanItem>>(emptyList())
    val peers = MutableStateFlow<List<LanPeer>>(emptyList())

    init {
        if (atomic.baseFile.exists() || File(atomic.baseFile.path + ".bak").exists()) {
            val json = JSONObject(atomic.openRead().bufferedReader().use { it.readText() })
            val rows = json.optJSONArray("items") ?: JSONArray()
            items.value =
                List(rows.length()) { LanItem.decode(rows.getJSONObject(it)) }
                    .map { row ->
                        val file = file(row.id)
                        if (row.state == "ADDING")
                            row.copy(state = "FAILED", detail = "文件准备曾被中断，请重新选择原文件")
                        else if (row.state in setOf("RECEIVING", "SENDING"))
                            row.copy(
                                bytes = if (row.direction == "IN") file.length() else row.bytes,
                                state = "PAUSED",
                                detail = "队列已恢复，可继续传输",
                            )
                        else if (row.state in setOf("READY", "SENT", "WAITING") && !file.isFile)
                            row.copy(state = "FAILED", detail = "队列副本已移除")
                        else row
                    }
            val clients = json.optJSONArray("peers") ?: JSONArray()
            peers.value =
                List(clients.length()) {
                    clients.getJSONObject(it).let { j ->
                        LanPeer(
                            j.getString("id"),
                            j.getString("name"),
                            j.getString("tokenHash"),
                            j.getLong("created"),
                        )
                    }
                }
        }
    }

    fun file(id: String): File {
        require(id.matches(Regex("[0-9a-f-]{36}"))) { "队列标识不正确" }
        return File(root, "objects/$id/payload")
    }

    fun item(id: String) = items.value.firstOrNull { it.id == id }

    @Synchronized
    private fun save() {
        val json =
            JSONObject()
                .put("format", 1)
                .put("items", JSONArray(items.value.map { it.json() }))
                .put("peers", JSONArray(peers.value.map { it.json() }))
        val stream = atomic.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Throwable) {
            atomic.failWrite(stream)
            throw e
        }
    }

    @Synchronized
    fun update(row: LanItem) {
        items.value = items.value.filterNot { it.id == row.id } + row
        save()
    }

    @Synchronized
    fun progress(id: String, bytes: Long, state: String, detail: String = "") {
        val row = item(id) ?: return
        update(row.copy(bytes = bytes, state = state, detail = detail))
    }

    @Synchronized
    fun remove(id: String) {
        file(id).delete()
        items.value = items.value.filterNot { it.id == id }
        save()
    }

    @Synchronized
    fun recoverActive() {
        items.value =
            items.value.map { row ->
                if (row.state in setOf("RECEIVING", "SENDING"))
                    row.copy(
                        bytes = file(row.id).length().coerceAtMost(row.total),
                        state = "PAUSED",
                        detail = "传输已关闭，文件与断点保留",
                    )
                else row
            }
        save()
    }

    @Synchronized
    fun pair(name: String): Pair<LanPeer, String> {
        val token = randomToken()
        val peer =
            LanPeer(
                UUID.randomUUID().toString(),
                name.trim().take(60).ifEmpty { "浏览器" },
                digest(token.toByteArray()),
                System.currentTimeMillis(),
            )
        peers.value = peers.value + peer
        save()
        return peer to token
    }

    fun authenticate(token: String, now: Long = System.currentTimeMillis()): LanPeer? =
        token
            .takeIf { it.length in 32..128 }
            ?.let { value ->
                val hash = digest(value.toByteArray())
                peers.value.firstOrNull {
                    it.tokenHash == hash && it.created <= now && now - it.created < 2592000000L
                }
            }

    @Synchronized
    fun revoke(id: String) {
        peers.value = peers.value.filterNot { it.id == id }
        save()
    }

    @Synchronized
    fun incoming(name: String, size: Long, hash: String, mime: String, peer: String): LanItem {
        require(size >= 0 && hash.matches(Regex("[0-9a-f]{64}"))) { "文件长度或校验值不正确" }
        val clean = cleanName(name)
        val existing =
            items.value.firstOrNull {
                it.direction == "IN" &&
                    it.peer == peer &&
                    it.name == clean &&
                    it.total == size &&
                    it.sha256 == hash &&
                    it.state != "CANCELLED"
            }
        if (existing != null && existing.state != "FAILED" && file(existing.id).exists())
            return existing.copy(bytes = file(existing.id).length())
        val row =
            existing?.copy(bytes = 0, state = "WAITING", detail = "")
                ?: LanItem(
                    UUID.randomUUID().toString(),
                    clean,
                    "IN",
                    size,
                    hash,
                    mime.takeIf { it.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")) }
                        ?: "application/octet-stream",
                    "WAITING",
                    peer = peer,
                )
        file(row.id).apply {
            parentFile!!.mkdirs()
            outputStream().close()
        }
        update(row)
        return row
    }

    suspend fun add(uri: Uri, peer: String = ""): LanItem =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val name =
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: uri.lastPathSegment ?: "文件"
            val id = UUID.randomUUID().toString()
            val target = file(id).apply { parentFile!!.mkdirs() }
            val row =
                LanItem(
                    id,
                    cleanName(name),
                    "OUT",
                    0,
                    "",
                    resolver.getType(uri) ?: "application/octet-stream",
                    "ADDING",
                    peer = peer,
                )
            update(row)
            try {
                val hash = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                resolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(target).use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            hash.update(buffer, 0, n)
                            bytes += n
                        }
                        output.fd.sync()
                    }
                } ?: error("无法打开所选文件")
                row.copy(
                        total = bytes,
                        sha256 = hex(hash.digest()),
                        bytes = 0,
                        state = "WAITING",
                        detail = "等待已配对设备领取",
                    )
                    .also(::update)
            } catch (e: Throwable) {
                progress(id, target.length(), "FAILED", e.message ?: "准备文件未完成，请重新添加原文件")
                throw e
            }
        }

    companion object {
        @Volatile private var instance: LanLedger? = null

        fun get(context: Context) =
            instance
                ?: synchronized(this) {
                    instance ?: LanLedger(context.applicationContext).also { instance = it }
                }

        fun cleanName(name: String) =
            name.replace(Regex("[\\\\/\\p{Cntrl}]"), "_").trim().take(180).ifEmpty { "文件" }

        fun randomToken() =
            android.util.Base64.encodeToString(
                ByteArray(32).also { SecureRandom().nextBytes(it) },
                android.util.Base64.NO_WRAP or
                    android.util.Base64.NO_PADDING or
                    android.util.Base64.URL_SAFE,
            )

        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

        fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

        fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val data = ByteArray(65536)
                while (true) {
                    val n = stream.read(data)
                    if (n < 0) break
                    digest.update(data, 0, n)
                }
            }
            return hex(digest.digest())
        }
    }
}
