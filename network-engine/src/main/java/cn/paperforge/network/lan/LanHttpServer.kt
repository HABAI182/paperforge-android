package cn.paperforge.network.lan

import android.content.Context
import cn.paperforge.network.R
import java.io.*
import java.net.*
import java.util.concurrent.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.*

data class PairingTicket(val nonce: String, val expires: Long, val pin: String = "")

/** Streaming local HTTP transport. Files are reachable only after explicit device pairing. */
class LanHttpServer(private val context: Context, port: Int, bindAddress: String = "0.0.0.0") :
    Closeable {
    private val ledger = LanLedger.get(context)
    private val socket =
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, port), 8)
        }
    val port: Int
        get() = socket.localPort

    val ticket = MutableStateFlow(newTicket())
    @Volatile
    var lastActivity = System.currentTimeMillis()
        private set

    private val pool =
        ThreadPoolExecutor(
            4,
            4,
            30,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(24),
            { runnable -> Thread(runnable, "PaperForge-LAN").apply { isDaemon = true } },
        )
    private val connections = ConcurrentHashMap<Socket, String>()
    private val itemLocks = ConcurrentHashMap<String, Any>()
    private var closed = false
    private var failedPairs = 0
    private var pairWindow = System.currentTimeMillis()

    private class HttpFailure(val status: Int, message: String) : IOException(message)

    private class ResponseStream(output: OutputStream) : FilterOutputStream(output) {
        var started = false

        override fun write(value: Int) {
            started = true
            out.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            started = true
            out.write(bytes, offset, length)
        }
    }

    init {
        Thread {
                while (!socket.isClosed) {
                    val client =
                        try {
                            socket.accept()
                        } catch (_: IOException) {
                            break
                        }
                    client.soTimeout = 30000
                    connections[client] = ""
                    try {
                        pool.execute {
                            client.use { serve(it) }
                            connections.remove(client)
                        }
                    } catch (_: RejectedExecutionException) {
                        connections.remove(client)
                        client.close()
                    }
                }
            }
            .apply {
                name = "PaperForge-LAN-listener"
                isDaemon = true
                start()
            }
    }

    @Synchronized
    fun rotateTicket() {
        ticket.value = newTicket()
        failedPairs = 0
        pairWindow = System.currentTimeMillis()
    }

    private fun newTicket() =
        PairingTicket(
            LanLedger.randomToken(),
            System.currentTimeMillis() + 300000,
            java.security.SecureRandom().nextInt(100000000).toString().padStart(8, '0'),
        )

    private fun line(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (out.size() < 8192) {
            val b = input.read()
            if (b < 0) throw EOFException()
            if (b == 10) return out.toString("ISO-8859-1").trimEnd('\r')
            out.write(b)
        }
        throw HttpFailure(431, "请求头过长")
    }

    private data class Request(
        val method: String,
        val uri: URI,
        val headers: Map<String, String>,
        val input: InputStream,
    ) {
        val length
            get() = headers["content-length"]?.toLongOrNull() ?: 0

        fun json(): JSONObject {
            if (length !in 0..65536) throw HttpFailure(413, "操作参数过长")
            val bytes = ByteArray(length.toInt())
            var count = 0
            while (count < bytes.size) {
                val n = input.read(bytes, count, bytes.size - count)
                if (n < 0) throw EOFException()
                count += n
            }
            return if (bytes.isEmpty()) JSONObject() else JSONObject(String(bytes, Charsets.UTF_8))
        }
    }

    private fun headers(
        output: OutputStream,
        status: Int,
        bytes: Long,
        type: String = "application/json; charset=utf-8",
        extra: Map<String, String> = emptyMap(),
    ) {
        val reason =
            when (status) {
                200 -> "OK"
                206 -> "Partial Content"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                404 -> "Not Found"
                409 -> "Conflict"
                416 -> "Range Not Satisfiable"
                else -> "Request Failed"
            }
        val text = buildString {
            append(
                "HTTP/1.1 $status $reason\r\nConnection: close\r\nContent-Length: $bytes\r\nContent-Type: $type\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n"
            )
            extra.forEach { (key, value) -> append("$key: $value\r\n") }
            append("\r\n")
        }
        output.write(text.toByteArray(Charsets.ISO_8859_1))
    }

    private fun json(
        output: OutputStream,
        value: JSONObject,
        status: Int = 200,
        extra: Map<String, String> = emptyMap(),
    ) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        headers(output, status, bytes.size.toLong(), extra = extra)
        output.write(bytes)
    }

    private fun authenticate(request: Request): LanPeer {
        val value =
            request.headers["authorization"]?.removePrefix("Bearer ")
                ?: request.headers["cookie"]
                    ?.split(';')
                    ?.map { it.trim() }
                    ?.firstOrNull { it.startsWith("pf_session=") }
                    ?.substringAfter('=')
                ?: ""
        return ledger.authenticate(value) ?: throw HttpFailure(401, "请使用手机显示的二维码或配对码连接")
    }

    private fun serve(client: Socket) {
        val output = ResponseStream(client.getOutputStream())
        try {
            val input = client.getInputStream().buffered()
            val first = line(input).split(' ')
            if (first.size != 3) throw HttpFailure(400, "请求格式不正确")
            val fields = linkedMapOf<String, String>()
            while (true) {
                val next = line(input)
                if (next.isEmpty()) break
                if (fields.size >= 64) throw HttpFailure(431, "请求头过多")
                fields[next.substringBefore(':').lowercase()] = next.substringAfter(':').trim()
            }
            val request = Request(first[0], URI(first[1]), fields, input)
            val path = request.uri.path
            if (
                request.method == "GET" &&
                    path in setOf("/", "/app.js", "/style.css", "/sha256.min.js", "/logo.png")
            ) {
                val bytes =
                    if (path == "/logo.png")
                        context.resources.openRawResource(R.drawable.brand_logo_source).use {
                            it.readBytes()
                        }
                    else
                        context.assets
                            .open("lan/" + if (path == "/") "index.html" else path.substring(1))
                            .use { it.readBytes() }
                val type =
                    when {
                        path.endsWith(".js") -> "application/javascript; charset=utf-8"
                        path.endsWith(".css") -> "text/css; charset=utf-8"
                        path.endsWith(".png") -> "image/png"
                        else -> "text/html; charset=utf-8"
                    }
                headers(
                    output,
                    200,
                    bytes.size.toLong(),
                    type,
                    mapOf(
                        "Content-Security-Policy" to
                            "default-src 'self'; style-src 'self'; script-src 'self'; frame-ancestors 'none'"
                    ),
                )
                output.write(bytes)
                return
            }
            if (path == "/api/pair" && request.method == "POST") {
                val data = request.json()
                synchronized(this) {
                    val current = ticket.value
                    if (System.currentTimeMillis() - pairWindow > 60000) {
                        failedPairs = 0
                        pairWindow = System.currentTimeMillis()
                    }
                    if (failedPairs >= 10) throw HttpFailure(429, "请稍后重试，或刷新手机上的二维码")
                    val code = data.optString("nonce").filterNot { it.isWhitespace() }
                    if (
                        (code != current.nonce && code != current.pin) ||
                            current.expires < System.currentTimeMillis() ||
                            current.nonce.isEmpty()
                    ) {
                        failedPairs++
                        throw HttpFailure(401, "配对码已失效或不正确，请查看手机")
                    }
                    val (peer, token) = ledger.pair(data.optString("name", "浏览器"))
                    rotateTicket()
                    lastActivity = System.currentTimeMillis()
                    json(
                        output,
                        JSONObject().put("paired", true).put("name", peer.name),
                        extra =
                            mapOf(
                                "Set-Cookie" to
                                    "pf_session=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=2592000"
                            ),
                    )
                }
                return
            }
            val peer = authenticate(request)
            connections[client] = peer.id
            lastActivity = System.currentTimeMillis()
            if (path == "/api/state" && request.method == "GET") {
                val rows = ledger.items.value
                json(
                    output,
                    JSONObject()
                        .put("peer", peer.name)
                        .put(
                            "outgoing",
                            JSONArray(
                                rows
                                    .filter {
                                        it.direction == "OUT" &&
                                            (it.peer.isEmpty() || it.peer == peer.id) &&
                                            it.state !in setOf("ADDING", "FAILED", "CANCELLED")
                                    }
                                    .map { it.json() }
                            ),
                        )
                        .put(
                            "incoming",
                            JSONArray(
                                rows
                                    .filter {
                                        it.direction == "IN" &&
                                            it.peer == peer.id &&
                                            it.state != "CANCELLED"
                                    }
                                    .map { it.copy(bytes = ledger.file(it.id).length()).json() }
                            ),
                        ),
                )
                return
            }
            if (path == "/api/uploads" && request.method == "POST") {
                val data = request.json()
                val row =
                    ledger.incoming(
                        data.getString("name"),
                        data.getLong("size"),
                        data.getString("sha256").lowercase(),
                        data.optString("mime"),
                        peer.id,
                    )
                if (row.total == 0L && row.state != "READY") {
                    if (LanLedger.hash(ledger.file(row.id)) != row.sha256)
                        throw HttpFailure(409, "文件校验值不一致")
                    ledger.progress(row.id, 0, "READY", "已接收并核对")
                }
                json(
                    output,
                    (ledger.item(row.id) ?: row).json().put("offset", ledger.file(row.id).length()),
                )
                return
            }
            if (path.startsWith("/api/uploads/") && request.method == "PUT") {
                val id = path.substringAfterLast('/')
                val row = ledger.item(id) ?: throw HttpFailure(404, "传输不存在")
                if (row.direction != "IN" || row.peer != peer.id) throw HttpFailure(404, "传输不存在")
                synchronized(itemLocks.getOrPut(id) { Any() }) {
                    if (ledger.item(id)?.state in setOf("HELD", "CANCELLED"))
                        throw HttpFailure(409, "已在手机暂停，请在手机上继续")
                    val file = ledger.file(id)
                    val offset =
                        request.uri.query
                            ?.substringAfter("offset=", "")
                            ?.substringBefore('&')
                            ?.toLongOrNull() ?: -1
                    if (offset != file.length()) {
                        json(
                            output,
                            JSONObject().put("error", "断点已变化").put("expectedOffset", file.length()),
                            409,
                        )
                        return
                    }
                    if (
                        request.length !in 1..8L * 1024 * 1024 ||
                            request.length > row.total - offset
                    )
                        throw HttpFailure(400, "分块长度不正确")
                    if (
                        android.os.StatFs(ledger.root.path).availableBytes <
                            request.length + 1048576
                    )
                        throw HttpFailure(507, "手机存储空间不足，断点已保留")
                    ledger.progress(id, offset, "RECEIVING", "正在接收")
                    try {
                        FileOutputStream(file, true).use { destination ->
                            var remaining = request.length
                            val buffer = ByteArray(65536)
                            while (remaining > 0) {
                                if (
                                    ledger.item(id)?.state in setOf("HELD", "CANCELLED") ||
                                        ledger.peers.value.none { it.id == peer.id }
                                )
                                    throw IOException("传输已暂停")
                                val n =
                                    input.read(
                                        buffer,
                                        0,
                                        minOf(buffer.size.toLong(), remaining).toInt(),
                                    )
                                if (n < 0) throw EOFException()
                                destination.write(buffer, 0, n)
                                remaining -= n
                                lastActivity = System.currentTimeMillis()
                            }
                            destination.fd.sync()
                        }
                        if (
                            ledger.item(id) == null ||
                                ledger.item(id)?.state in setOf("HELD", "CANCELLED")
                        )
                            throw IOException("传输已暂停")
                        val complete = file.length() == row.total
                        if (complete && LanLedger.hash(file) != row.sha256) {
                            ledger.progress(id, file.length(), "FAILED", "校验未通过，请重新发送")
                            throw HttpFailure(409, "文件校验未通过")
                        }
                        ledger.progress(
                            id,
                            file.length(),
                            if (complete) "READY" else "PAUSED",
                            if (complete) "已接收并核对" else "断点已保存",
                        )
                        json(
                            output,
                            checkNotNull(ledger.item(id)).json().put("offset", file.length()),
                        )
                    } catch (e: IOException) {
                        val current = ledger.item(id)
                        if (
                            current != null &&
                                current.state !in setOf("READY", "FAILED", "CANCELLED", "HELD")
                        )
                            ledger.progress(id, file.length(), "PAUSED", "连接中断，可继续发送")
                        throw e
                    }
                }
                return
            }
            if (path.startsWith("/download/") && request.method == "GET") {
                val id = path.substringAfterLast('/')
                val row = ledger.item(id) ?: throw HttpFailure(404, "文件不存在")
                if (
                    row.direction != "OUT" ||
                        row.state in setOf("ADDING", "FAILED", "CANCELLED", "HELD") ||
                        row.peer.isNotEmpty() && row.peer != peer.id
                )
                    throw HttpFailure(404, "文件不存在或已在手机暂停")
                val file = ledger.file(id)
                if (!file.isFile) throw HttpFailure(404, "文件已移除")
                var start = 0L
                var end = file.length() - 1
                val range = request.headers["range"]
                if (range != null) {
                    val match =
                        Regex("bytes=(\\d*)-(\\d*)").matchEntire(range)
                            ?: throw HttpFailure(416, "不支持的范围")
                    if (match.groupValues[1].isEmpty())
                        start = maxOf(0, file.length() - match.groupValues[2].toLong())
                    else {
                        start = match.groupValues[1].toLong()
                        if (match.groupValues[2].isNotEmpty())
                            end = minOf(end, match.groupValues[2].toLong())
                    }
                    if (start > end || start >= file.length()) throw HttpFailure(416, "范围已超出文件")
                }
                val extra =
                    mutableMapOf(
                        "Accept-Ranges" to "bytes",
                        "X-Content-SHA256" to row.sha256,
                        "Content-Disposition" to
                            "attachment; filename*=UTF-8''${URLEncoder.encode(row.name, "UTF-8").replace("+", "%20")}",
                    )
                if (range != null) extra["Content-Range"] = "bytes $start-$end/${file.length()}"
                headers(
                    output,
                    if (range == null) 200 else 206,
                    (end - start + 1).coerceAtLeast(0),
                    "application/octet-stream",
                    extra,
                )
                ledger.progress(id, start, "SENDING", "正在传输给 ${peer.name}")
                var sent = start
                try {
                    RandomAccessFile(file, "r").use { source ->
                        source.seek(start)
                        val buffer = ByteArray(65536)
                        while (sent <= end) {
                            if (ledger.item(id)?.state in setOf("HELD", "CANCELLED"))
                                throw IOException("传输已暂停")
                            val n =
                                source.read(
                                    buffer,
                                    0,
                                    minOf(buffer.size.toLong(), end - sent + 1).toInt(),
                                )
                            if (n < 0) throw EOFException()
                            output.write(buffer, 0, n)
                            sent += n
                            lastActivity = System.currentTimeMillis()
                        }
                    }
                    output.flush()
                    if (
                        ledger.item(id) == null ||
                            ledger.item(id)?.state in setOf("HELD", "CANCELLED")
                    )
                        throw IOException("传输已暂停")
                    ledger.progress(
                        id,
                        sent,
                        if (sent == file.length()) "SENT" else "PAUSED",
                        if (sent == file.length()) "已传输，可再次领取" else "已传输所选范围",
                    )
                } catch (e: IOException) {
                    if (ledger.item(id)?.state !in setOf(null, "HELD", "CANCELLED"))
                        ledger.progress(id, sent, "PAUSED", "连接中断，可按范围继续领取")
                    throw e
                }
                return
            }
            throw HttpFailure(404, "操作不存在")
        } catch (e: Exception) {
            if (!output.started)
                runCatching {
                    json(
                        output,
                        JSONObject().put("error", e.message ?: "传输未完成"),
                        (e as? HttpFailure)?.status ?: 400,
                    )
                }
        }
    }

    fun pause(id: String) {
        ledger.item(id)?.let {
            ledger.progress(
                id,
                if (it.direction == "IN") ledger.file(id).length() else it.bytes,
                "HELD",
                "已在手机暂停，副本与断点保留",
            )
        }
    }

    fun revoke(peer: String) {
        ledger.revoke(peer)
        connections.filterValues { it == peer }.keys.forEach { runCatching { it.close() } }
    }

    override fun close() {
        if (closed) return
        closed = true
        ticket.value = PairingTicket("", 0)
        socket.close()
        connections.keys.forEach { runCatching { it.close() } }
        pool.shutdownNow()
        ledger.recoverActive()
    }
}
