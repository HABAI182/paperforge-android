package cn.paperforge.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.pdf.*
import java.io.File
import java.text.DateFormat
import kotlinx.coroutines.*
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PdfCertificatesScreen(vm: MainViewModel) {
    val source by vm.certificateSource.collectAsState()
    val doc = source ?: return
    val input = doc.pdfPath?.let(::File) ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var identities by remember { mutableStateOf<List<CertificateVault.Identity>>(emptyList()) }
    var chosen by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable(doc.id) { mutableStateOf("") }
    var location by rememberSaveable(doc.id) { mutableStateOf("") }
    var field by rememberSaveable(doc.id) { mutableStateOf("") }
    var fields by remember { mutableStateOf<List<PdfForms.Field>>(emptyList()) }
    var report by remember(doc.id) { mutableStateOf<JSONObject?>(null) }
    var message by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("个人签署证书") }
    var years by rememberSaveable { mutableStateOf("5") }
    var password by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CertificateVault.Identity?>(null) }
    val busy by vm.busy.collectAsState()
    val identity = identities.firstOrNull { it.alias == chosen }
    fun refreshKeys(preferred: String? = null) {
        identities = CertificateVault.list()
        chosen =
            preferred
                ?: chosen.takeIf { value -> identities.any { it.alias == value } }
                ?: identities.firstOrNull()?.alias.orEmpty()
    }
    fun run(action: suspend () -> Unit) {
        scope.launch {
            working = true
            message = ""
            try {
                action()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                message = error.message ?: "操作未完成"
            } finally {
                working = false
            }
        }
    }
    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val secret = password.toCharArray()
            password = ""
            importing = false
            if (uri != null)
                run {
                    val imported =
                        withContext(Dispatchers.IO) {
                            try {
                                context.contentResolver.openInputStream(uri)?.use {
                                    CertificateVault.import(it, secret)
                                } ?: error("无法读取证书文件")
                            } finally {
                                secret.fill('\u0000')
                            }
                        }
                    refreshKeys(imported.first().alias)
                    message = "已导入 ${imported.size} 张证书，私钥保存在系统密钥库"
                }
            else secret.fill('\u0000')
        }
    val exporter =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/pkix-cert")
        ) { uri ->
            val bytes = identity?.certificate?.encoded
            if (uri != null && bytes != null)
                run {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                            ?: error("无法保存公钥证书")
                    }
                    message = "已导出公钥证书"
                }
        }
    LaunchedEffect(doc.id) {
        working = true
        try {
            val loaded =
                withContext(Dispatchers.IO) {
                    PdfForms.inspect(input) to PdfCertificates.verify(input)
                }
            fields = loaded.first.fields.filter { it.kind == "signature" && !it.readOnly }
            report = loaded.second
            refreshKeys()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            message = error.message ?: "无法读取证书与签名"
        } finally {
            working = false
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("证书签署与核验") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            vm.selected.value = doc
                            vm.screen.value = "reader"
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回阅读")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(doc.title, style = MaterialTheme.typography.titleLarge)
                Text("证书签名验证文件字节；手写签名在批注工具中。", style = MaterialTheme.typography.bodySmall)
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                if (message.isNotBlank())
                    Text(
                        message,
                        Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
            }
            item {
                Text("已有签名", style = MaterialTheme.typography.titleMedium)
                val signatures = report?.optJSONArray("signatures")
                if (report != null && signatures?.length() == 0) Text("这份文件没有数字证书签名")
                for (i in 0 until (signatures?.length() ?: 0)) {
                    val entry = signatures!!.getJSONObject(i)
                    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "${i + 1}. ${entry.optString("field").ifBlank { "数字签名" }}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                when (entry.optString("integrity")) {
                                    "valid" -> "签名与摘要通过"
                                    "invalid" -> "签名或摘要不匹配"
                                    else -> "未完成核验：${entry.optString("error")}"
                                }
                            )
                            if (entry.has("coversCurrentFile"))
                                Text(
                                    if (entry.optBoolean("coversCurrentFile")) "覆盖当前完整文件"
                                    else "仅覆盖历史修订，签署后有追加内容；未评估追加修改是否获准"
                                )
                            val signers = entry.optJSONArray("signers")
                            for (j in 0 until (signers?.length() ?: 0)) {
                                val signer = signers!!.getJSONObject(j)
                                Text(signer.optString("subject"))
                                Text(
                                    "颁发者：${signer.optString("issuer")}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    when (signer.optString("trust")) {
                                        "system-ca" -> "链至系统信任根（离线）"
                                        "local-private-key" -> "本机持有私钥的个人证书"
                                        else -> "未建立系统信任链"
                                    }
                                )
                                Text(
                                    when (signer.optString("validity")) {
                                        "valid-now" -> "当前在证书有效期内"
                                        "expired" -> "证书当前已过期"
                                        else -> "证书尚未生效"
                                    }
                                )
                                Text(
                                    "SHA-256 ${signer.optString("sha256")}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            entry
                                .optString("reason")
                                .takeIf { it.isNotBlank() }
                                ?.let { Text("原因：$it") }
                        }
                    }
                }
                Text(
                    "离线核验不查询吊销状态，不验证可信时间戳，也不保证后续修订符合 DocMDP / FieldMDP。PDF 中填写的签署时间仅供参考。",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                HorizontalDivider()
                Text(
                    "签署副本",
                    Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                identities.forEach { cert ->
                    FilterChip(
                        selected = chosen == cert.alias,
                        onClick = { chosen = cert.alias },
                        label = { Text(cert.subject + if (cert.validNow) "" else " · 非当前有效") },
                    )
                }
                if (identities.isEmpty()) Text("先创建个人证书，或导入带私钥的 P12 / PFX。")
                identity?.let { cert ->
                    Text(
                        "有效至 ${DateFormat.getDateInstance().format(cert.certificate.notAfter)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("SHA-256 ${cert.fingerprint}", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !working,
                            onClick = { exporter.launch("PaperForge-公钥证书.cer") },
                        ) {
                            Text("导出公钥证书")
                        }
                        TextButton(enabled = !working, onClick = { deleting = cert }) {
                            Text("移除本机私钥")
                        }
                    }
                }
                OutlinedTextField(
                    reason,
                    { reason = it },
                    label = { Text("签署原因（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    location,
                    { location = it },
                    label = { Text("签署地点（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(field.isEmpty(), { field = "" }, label = { Text("新增非可见签名") })
                    fields.forEach { value ->
                        FilterChip(
                            field == value.name,
                            { field = value.name },
                            label = { Text(value.label) },
                        )
                    }
                }
                Button(
                    enabled =
                        identity?.validNow == true &&
                            !working &&
                            !busy &&
                            report != null &&
                            report?.optInt("docMdpPermission") != 1,
                    onClick = {
                        identity?.let { cert ->
                            vm.queueCertificate(
                                JSONObject()
                                    .put("sourceSha256", report!!.getString("sourceSha256"))
                                    .put("alias", cert.alias)
                                    .put("certificateSha256", cert.fingerprint)
                                    .put("reason", reason)
                                    .put("location", location)
                                    .put("field", field)
                            )
                        }
                    },
                ) {
                    Text("签署并生成副本")
                }
            }
            item {
                HorizontalDivider()
                Text(
                    "本机证书",
                    Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text("个人证书姓名 / 名称") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    years,
                    { years = it },
                    label = { Text("有效期（1–30 年）") },
                    singleLine = true,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled =
                            !working && name.isNotBlank() && (years.toIntOrNull() ?: 0) in 1..30,
                        onClick = {
                            run {
                                val cert =
                                    withContext(Dispatchers.IO) {
                                        CertificateVault.create(name, years.toInt())
                                    }
                                refreshKeys(cert.alias)
                                message = "已创建本机个人证书"
                            }
                        },
                    ) {
                        Text("创建个人证书")
                    }
                    OutlinedButton(enabled = !working, onClick = { importing = true }) {
                        Text("导入 P12 / PFX")
                    }
                }
                Text(
                    "私钥留在本机系统密钥库，不进入文档备份。换机可重新导入原始 P12 / PFX；本机创建的私钥不可导出。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (importing)
        AlertDialog(
            onDismissRequest = {
                importing = false
                password = ""
            },
            title = { Text("导入私钥证书") },
            text = {
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text("证书密码（可为空）") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text("选择 P12 / PFX") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        importing = false
                        password = ""
                    }
                ) {
                    Text("取消")
                }
            },
        )
    deleting?.let { cert ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("移除此私钥？") },
            text = { Text("${cert.subject}\n已签文件保留。本机创建的私钥移除后无法恢复；导入证书可用原始 P12 / PFX 重新导入。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        run {
                            withContext(Dispatchers.IO) { CertificateVault.remove(cert.alias) }
                            refreshKeys()
                            deleting = null
                        }
                    }
                ) {
                    Text("移除私钥")
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}
