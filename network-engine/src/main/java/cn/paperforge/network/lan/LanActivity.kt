package cn.paperforge.network.lan

import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import cn.paperforge.network.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update

class LanActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) acceptShare(intent)
        networkContent { LanScreen { finish() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptShare(intent)
    }

    @Suppress("DEPRECATION")
    private fun acceptShare(intent: Intent) {
        val uris =
            when (intent.action) {
                Intent.ACTION_SEND ->
                    listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                Intent.ACTION_SEND_MULTIPLE ->
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                else -> emptyList()
            }
        addCopies(this, uris, "")
    }
}

private fun addCopies(context: Context, uris: List<Uri>, peer: String) {
    if (uris.isEmpty()) return
    val app = context.applicationContext
    LanSession.scope.launch {
        LanSession.copying.update { it + uris.size }
        for (uri in uris) {
            try {
                LanLedger.get(app).add(uri, peer)
            } catch (e: Exception) {
                LanSession.message.value = e.message ?: "文件未能加入队列，请重新选择"
            } finally {
                LanSession.copying.update { it - 1 }
            }
        }
    }
}

private fun size(bytes: Long): String =
    if (bytes >= 1048576) "%.1f MB".format(bytes / 1048576.0) else "${bytes / 1024} KB"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LanScreen(back: () -> Unit) {
    val context = LocalContext.current
    val ledger = remember { LanLedger.get(context) }
    val scope = rememberCoroutineScope()
    val rows by ledger.items.collectAsState()
    val peers by ledger.peers.collectAsState()
    val server by LanSession.server.collectAsState()
    val notice by LanSession.message.collectAsState()
    val copying by LanSession.copying.collectAsState()
    val ticket by
        (server?.ticket
                ?: remember { kotlinx.coroutines.flow.MutableStateFlow(PairingTicket("", 0)) })
            .collectAsState()
    val prefs = remember { context.getSharedPreferences("lan-ui", 0) }
    var port by rememberSaveable { mutableStateOf(prefs.getInt("port", 8787).toString()) }
    var idle by rememberSaveable { mutableIntStateOf(prefs.getInt("idle", 30)) }
    var direction by rememberSaveable { mutableStateOf("OUT") }
    var target by rememberSaveable { mutableStateOf("") }
    var configuration by rememberSaveable { mutableStateOf(false) }
    var devices by rememberSaveable { mutableStateOf(false) }
    var pairingExpanded by rememberSaveable { mutableStateOf(false) }
    var addresses by remember { mutableStateOf(LanSession.addresses()) }
    var address by rememberSaveable { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var saving by remember { mutableStateOf<LanItem?>(null) }
    val save =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            val row = saving
            saving = null
            if (uri != null && row != null)
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                ledger.file(row.id).inputStream().use { it.copyTo(out) }
                            } ?: error("无法写入所选位置")
                        }
                        LanSession.message.value = "已保存 ${row.name}"
                    } catch (e: Exception) {
                        LanSession.message.value = e.message ?: "保存未完成"
                    }
                }
        }
    val choose =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris.forEach {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            addCopies(context, uris, target)
        }
    LaunchedEffect(server) {
        while (true) {
            now = System.currentTimeMillis()
            addresses = LanSession.addresses()
            delay(1000)
        }
    }
    val host = address.takeIf { it in addresses } ?: addresses.firstOrNull()
    val base = host?.let { "http://$it:${server?.port ?: port}/" }
    val link =
        if (server != null && base != null && ticket.expires > now) "$base#pair=${ticket.nonce}"
        else null
    val qr by
        produceState<Bitmap?>(null, link) {
            value =
                if (link == null) null
                else
                    withContext(Dispatchers.Default) {
                        val bits = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 640, 640)
                        Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888).apply {
                            setPixels(
                                IntArray(640 * 640) { i ->
                                    if (bits[i % 640, i / 640]) android.graphics.Color.BLACK
                                    else android.graphics.Color.WHITE
                                },
                                0,
                                640,
                                0,
                                0,
                                640,
                                640,
                            )
                        }
                    }
        }
    fun uri(row: LanItem) =
        FileProvider.getUriForFile(
            context,
            NetworkFiles.authority(context),
            ledger.file(row.id),
            row.name,
        )
    fun share(row: LanItem, main: Boolean) {
        try {
            if (main)
                require(
                    context.packageManager.checkSignatures(
                        context.packageName,
                        "cn.paperforge.app",
                    ) == PackageManager.SIGNATURE_MATCH
                ) {
                    "请先安装同一来源的 PaperForge"
                }
            val file = uri(row)
            val intent =
                Intent(Intent.ACTION_SEND)
                    .setType(row.mime)
                    .putExtra(Intent.EXTRA_STREAM, file)
                    .putExtra(Intent.EXTRA_TITLE, row.name)
                    .apply { clipData = ClipData.newRawUri(row.name, file) }
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (main) intent.setPackage("cn.paperforge.app")
            context.startActivity(
                if (main) intent else Intent.createChooser(intent, "分享 ${row.name}")
            )
        } catch (e: Exception) {
            LanSession.message.value = e.message ?: "无法打开文件"
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("局域网传输") },
                navigationIcon = { TextButton(onClick = back) { Text("返回") } },
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text("文件，就在手边。", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "手机、平板和电脑浏览器互传。使用同一个 Wi-Fi 或手机热点。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Card {
                    Column(
                        Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (server == null) "传输已关闭" else "传输已开启",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Switch(
                                modifier = Modifier.semantics { contentDescription = "开启局域网传输" },
                                checked = server != null,
                                onCheckedChange = { enabled ->
                                    if (!enabled) LanSession.stop(context)
                                    else {
                                        val value = port.toIntOrNull()
                                        if (value == null || value !in 1024..65535)
                                            LanSession.message.value = "端口请输入 1024–65535"
                                        else {
                                            prefs
                                                .edit()
                                                .putInt("port", value)
                                                .putInt("idle", idle)
                                                .apply()
                                            try {
                                                LanSession.start(context, value, idle)
                                            } catch (e: Exception) {
                                                LanSession.message.value = e.message ?: "无法开启传输"
                                            }
                                        }
                                    }
                                },
                            )
                        }
                        if (server == null) Text("关闭时可以准备发送队列。重新开启后，配对过的浏览器仍可连接。")
                        else if (host == null) Text("尚无局域网地址。连接 Wi-Fi 或开启热点后，这里会显示二维码；队列保留。")
                        else {
                            if (peers.isEmpty() || pairingExpanded) {
                                if (qr != null)
                                    Image(
                                        qr!!.asImageBitmap(),
                                        "扫描二维码配对",
                                        Modifier.size(224.dp).align(Alignment.CenterHorizontally),
                                    )
                                if (link == null) Text("配对码已过期，刷新后可连接新设备。")
                                else {
                                    Text(
                                        ticket.pin.chunked(4).joinToString(" "),
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.align(Alignment.CenterHorizontally),
                                    )
                                    Text(
                                        "新设备配对码 · ${((ticket.expires-now)/1000).coerceAtLeast(0)} 秒后失效",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            Text(base.orEmpty(), fontFamily = FontFamily.Monospace)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(
                                    onClick = {
                                        (context.getSystemService(Context.CLIPBOARD_SERVICE)
                                                as ClipboardManager)
                                            .setPrimaryClip(
                                                ClipData.newPlainText("PaperForge 连接", link ?: base)
                                            )
                                        Toast.makeText(context, "已复制连接地址", Toast.LENGTH_SHORT)
                                            .show()
                                    }
                                ) {
                                    Text("复制连接")
                                }
                                TextButton(onClick = { server?.rotateTicket() }) { Text("刷新配对码") }
                                if (peers.isNotEmpty())
                                    TextButton(
                                        onClick = {
                                            pairingExpanded = !pairingExpanded
                                            if (pairingExpanded && ticket.expires < now)
                                                server?.rotateTicket()
                                        }
                                    ) {
                                        Text(if (pairingExpanded) "收起配对" else "连接新设备")
                                    }
                            }
                            if (addresses.size > 1)
                                FlowRow {
                                    addresses.forEach { ip ->
                                        FilterChip(
                                            selected = host == ip,
                                            onClick = { address = ip },
                                            label = { Text(ip) },
                                        )
                                    }
                                }
                        }
                        TextButton(onClick = { configuration = !configuration }) {
                            Text(if (configuration) "收起设置" else "连接设置")
                        }
                        if (configuration) {
                            OutlinedTextField(
                                value = port,
                                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                                enabled = server == null,
                                label = { Text("端口") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Text("无连接自动关闭", style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(15, 30, 60, 0).forEach { n ->
                                    FilterChip(
                                        selected = idle == n,
                                        onClick = { idle = n },
                                        enabled = server == null,
                                        label = { Text(if (n == 0) "手动关闭" else "$n 分钟") },
                                    )
                                }
                            }
                            Text(
                                "局域网 HTTP 传输；文件只向配对设备开放。系统结束后台时段后可重新开启，断点保留。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            if (notice.isNotEmpty())
                item { Text(notice, color = MaterialTheme.colorScheme.primary) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = direction == "OUT",
                        onClick = { direction = "OUT" },
                        label = { Text("待发送 · ${rows.count { it.direction=="OUT" }}") },
                    )
                    FilterChip(
                        selected = direction == "IN",
                        onClick = { direction = "IN" },
                        label = { Text("已接收 · ${rows.count { it.direction=="IN" }}") },
                    )
                    TextButton(onClick = { devices = !devices }) { Text("配对设备 · ${peers.size}") }
                }
                if (devices)
                    Card {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            if (peers.isEmpty()) Text("暂无配对设备。对方扫码后会出现在这里。")
                            peers.forEach { peer ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(peer.name, Modifier.weight(1f))
                                    TextButton(
                                        onClick = {
                                            scope.launch(Dispatchers.IO) {
                                                server?.revoke(peer.id) ?: ledger.revoke(peer.id)
                                            }
                                        }
                                    ) {
                                        Text("取消配对")
                                    }
                                }
                            }
                        }
                    }
                if (direction == "OUT") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = target == "",
                            onClick = { target = "" },
                            label = { Text("所有已配对设备") },
                        )
                        peers.forEach { peer ->
                            FilterChip(
                                selected = target == peer.id,
                                onClick = { target = peer.id },
                                label = { Text(peer.name) },
                            )
                        }
                    }
                    Button(onClick = { choose.launch(arrayOf("*/*")) }) {
                        Text(if (copying > 0) "正在准备 $copying 个文件 · 继续添加" else "选择要发送的文件")
                    }
                }
            }
            if (rows.none { it.direction == direction })
                item {
                    Text(
                        if (direction == "OUT") "队列为空。从这里选择文件，或从其他应用分享到 PaperForge 传输。"
                        else "浏览器发送完成后，文件会出现在这里。"
                    )
                }
            items(
                rows.filter { it.direction == direction }.sortedByDescending { it.created },
                key = { it.id },
            ) { row ->
                Card {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(row.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${size(row.bytes)} / ${size(row.total)} · " +
                                when (row.state) {
                                    "ADDING" -> "准备副本"
                                    "WAITING" -> "等待连接"
                                    "RECEIVING" -> "正在接收"
                                    "SENDING" -> "正在发送"
                                    "HELD" -> "已暂停"
                                    "PAUSED" -> "断点已保留"
                                    "READY" -> "接收完成"
                                    "SENT" -> "已传输"
                                    else -> "未完成"
                                }
                        )
                        if (row.state in setOf("RECEIVING", "SENDING", "ADDING"))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        else if (row.total > 0)
                            LinearProgressIndicator(
                                progress = { (row.bytes.toFloat() / row.total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        if (row.detail.isNotBlank())
                            Text(row.detail, style = MaterialTheme.typography.bodySmall)
                        if (row.direction == "OUT" && row.peer.isNotEmpty())
                            Text(
                                "接收设备：${peers.firstOrNull { it.id==row.peer }?.name ?: "已取消配对"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (row.direction == "IN" && row.state == "READY") {
                                if (row.mime == "application/pdf" || row.mime.startsWith("image/"))
                                    TextButton(onClick = { share(row, true) }) {
                                        Text("在 PaperForge 打开")
                                    }
                                TextButton(
                                    onClick = {
                                        saving = row
                                        save.launch(row.name)
                                    }
                                ) {
                                    Text("另存为")
                                }
                                TextButton(onClick = { share(row, false) }) { Text("分享") }
                            }
                            if (row.state == "HELD")
                                TextButton(
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            ledger.progress(
                                                row.id,
                                                row.bytes,
                                                "PAUSED",
                                                "可继续传输，请在浏览器点继续",
                                            )
                                        }
                                    }
                                ) {
                                    Text("继续")
                                }
                            else if (
                                row.state in setOf("WAITING", "PAUSED", "RECEIVING", "SENDING")
                            )
                                TextButton(
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            server?.pause(row.id)
                                                ?: ledger.progress(row.id, row.bytes, "HELD", "已暂停")
                                        }
                                    }
                                ) {
                                    Text("暂停")
                                }
                            if (row.state != "ADDING")
                                TextButton(
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            server?.pause(row.id)
                                            ledger.remove(row.id)
                                        }
                                    }
                                ) {
                                    Text("移除副本")
                                }
                        }
                    }
                }
            }
            item { Text("只传输你选择的文件；移除队列副本不会删除原件。", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
