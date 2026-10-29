package cn.paperforge.network

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.work.*
import kotlinx.coroutines.*

class UpdatesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        networkContent {
            UpdatesScreen(intent.getBooleanExtra("paperforgeOneClickUpdate", false)) { finish() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun UpdatesScreen(oneClickOnOpen: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val integrated = remember(context) { NetworkFiles.integrated(context) }
    val prefs = remember { NetworkPreferences(context) }
    val scope = rememberCoroutineScope()
    val manager = remember { WorkManager.getInstance(context) }
    val work by
        remember { manager.getWorkInfosByTagFlow("paperforge-update") }
            .collectAsState(initial = emptyList())
    var revision by remember { mutableIntStateOf(0) }
    val records = remember(work, revision) { UpdateFiles.records(context) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var repository by remember { mutableStateOf(prefs.repository) }
    var channel by remember { mutableStateOf(prefs.channel) }
    var credentialError by remember { mutableStateOf<String?>(null) }
    var token by remember {
        mutableStateOf(
            runCatching { prefs.token() }
                .getOrElse {
                    credentialError = "访问凭据无法解密，请重新填写"
                    ""
                }
        )
    }
    var message by remember { mutableStateOf<String?>(null) }
    var configuration by remember { mutableStateOf(credentialError != null) }
    var checking by remember { mutableStateOf(false) }
    var checkSerial by remember { mutableIntStateOf(0) }
    var checkJob by remember { mutableStateOf<Job?>(null) }
    var result by remember { mutableStateOf<UpdateCheck?>(null) }
    var startedOnOpen by rememberSaveable { mutableStateOf(false) }
    var autoInstallSha by rememberSaveable { mutableStateOf("") }
    var pendingPermission by rememberSaveable { mutableStateOf("") }
    var installing by remember { mutableStateOf(false) }
    var resumed by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val lifecycle = (context as ComponentActivity).lifecycle
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val installed = remember(revision) { GitHubUpdates.installed(context) }
    val labels = mapOf("stable" to "正式版", "prerelease" to "含预发布", "development" to "含开发版")

    fun startDownload(candidate: UpdateCandidate, restart: Boolean = false) {
        try {
            UpdateFiles.enqueue(context, candidate, restart)
            revision++
            message = "已加入下载队列，可暂停后继续"
        } catch (e: Exception) {
            autoInstallSha = ""
            message = e.message ?: "无法开始下载"
        }
    }

    fun install(candidate: UpdateCandidate) {
        if (installing) return
        installing = true
        scope.launch {
            try {
                val file = UpdateFiles.ready(context, candidate)
                withContext(Dispatchers.IO) { UpdateApkVerifier.verify(context, file, candidate) }
                if (!context.packageManager.canRequestPackageInstalls()) {
                    pendingPermission = candidate.json().toString()
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
                    message = "允许安装应用后返回，将继续打开系统安装器。"
                } else {
                    pendingPermission = ""
                    val uri =
                        FileProvider.getUriForFile(context, NetworkFiles.authority(context), file)
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                    message = "安装包已核对，请在系统安装器中确认。"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pendingPermission = ""
                message = e.message ?: "无法打开系统安装器"
            } finally {
                installing = false
            }
        }
    }

    fun check(download: Boolean) {
        if (checking || installing) return
        if (!enabled) {
            message = "更新已关闭，请先开启下方的手动更新开关。"
            return
        }
        checking = true
        message = null
        result = null
        val serial = ++checkSerial
        checkJob =
            scope.launch {
                try {
                    val checked =
                        withContext(Dispatchers.IO) {
                            prefs.repository = repository
                            prefs.channel = channel
                            prefs.token(token)
                            GitHubUpdates(prefs.token(), integrated = integrated)
                                .check(repository, channel, GitHubUpdates.installed(context))
                        }
                    result = checked
                    val candidate =
                        checked.candidates
                            .filter { it.packageName == "cn.paperforge.app" }
                            .maxByOrNull { it.versionCode }
                    message =
                        when {
                            download && candidate != null -> "已找到 ${candidate.versionName}，正在下载与核对。"
                            checked.candidates.isNotEmpty() ->
                                "找到 ${checked.candidates.size} 个适用安装包"
                            checked.warnings.isNotEmpty() -> "检查未能完整核对，请查看下面的说明"
                            checked.releases == 0 -> "所选渠道尚无发布版本"
                            else -> "当前已是此渠道的最新适配版本"
                        }
                    if (download && candidate != null) {
                        autoInstallSha = candidate.sha256
                        val ready =
                            UpdateFiles.records(context).any {
                                it.optString("state") == "READY" &&
                                    it.getJSONObject("candidate").optString("sha256") ==
                                        candidate.sha256
                            }
                        if (ready) revision++ else startDownload(candidate)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    autoInstallSha = ""
                    val detail = e.message.orEmpty()
                    if (listOf("401", "403", "404").any { it in detail }) {
                        configuration = true
                        message = "无法访问仓库，请核对仓库地址、访问权限或稍后重试。私有仓库需配置只读令牌；公开仓库可不填。"
                    } else
                        message =
                            when (e) {
                                is java.net.UnknownHostException,
                                is java.net.ConnectException -> "暂时无法连接 GitHub，请检查网络后重试。"
                                is java.net.SocketTimeoutException -> "连接 GitHub 超时，请稍后重试。"
                                else -> e.message ?: "检查失败，请稍后重试"
                            }
                } finally {
                    if (serial == checkSerial) checking = false
                }
            }
    }

    LaunchedEffect(oneClickOnOpen, enabled) {
        if (oneClickOnOpen && !startedOnOpen) {
            startedOnOpen = true
            check(download = true)
        }
    }
    LaunchedEffect(records, autoInstallSha, resumed, enabled, installing) {
        if (enabled && resumed && !installing && autoInstallSha.isNotBlank()) {
            records
                .find {
                    it.optString("state") == "READY" &&
                        it.getJSONObject("candidate").optString("sha256") == autoInstallSha
                }
                ?.let {
                    autoInstallSha = ""
                    install(UpdateCandidate.decode(it.getJSONObject("candidate")))
                }
        }
    }
    LaunchedEffect(resumed, pendingPermission, enabled, installing) {
        if (
            enabled &&
                resumed &&
                pendingPermission.isNotBlank() &&
                context.packageManager.canRequestPackageInstalls() &&
                !installing
        ) {
            val candidate = UpdateCandidate.decode(org.json.JSONObject(pendingPermission))
            pendingPermission = ""
            install(candidate)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("应用更新") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                if (integrated) "一键检查新版本、下载安装包并核对，随后在系统安装器确认。"
                else "按需检查与下载；文件校验通过后交给系统安装。PaperForge主应用保持离线。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = { check(download = true) },
                enabled = enabled && !checking && !installing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (checking) "正在检查" else if (installing) "正在核对安装包" else "一键更新")
            }
            if (checking) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(
                    onClick = {
                        checkSerial++
                        checkJob?.cancel()
                        checking = false
                        message = "检查已取消"
                    }
                ) {
                    Text("取消检查")
                }
            } else
                TextButton(
                    onClick = { check(download = false) },
                    enabled = enabled && !installing,
                ) {
                    Text("仅检查版本")
                }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            result?.warnings?.forEach {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Card {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (integrated) "允许手动检查更新" else "启用可选更新",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (enabled) "由你点击发起，下载完成后交给系统确认" else "已关闭联网检查与下载",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        enabled,
                        { value ->
                            enabled = value
                            prefs.enabled = value
                            if (!value) {
                                checkSerial++
                                checkJob?.cancel()
                                checking = false
                                autoInstallSha = ""
                                pendingPermission = ""
                                manager.cancelAllWorkByTag("paperforge-update")
                                message = "已关闭，下载断点保留"
                            }
                            revision++
                        },
                    )
                }
            }
            Text("已安装", style = MaterialTheme.typography.titleMedium)
            installed.forEach { (id, value) ->
                Text(
                    "${GitHubUpdates.packages[id]}  ${value.versionName}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(onClick = { configuration = !configuration }) {
                Text(if (configuration) "收起仓库与访问凭据" else "仓库与访问凭据")
            }
            if (configuration) {
                OutlinedTextField(
                    repository,
                    { repository = it },
                    label = { Text("GitHub 仓库") },
                    placeholder = { Text("用户名/仓库名") },
                    singleLine = true,
                    enabled = !checking,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (configuration)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    labels.forEach { (id, name) ->
                        FilterChip(
                            channel == id,
                            {
                                channel = id
                                prefs.channel = id
                                result = null
                            },
                            label = { Text(name) },
                            enabled = !checking,
                        )
                    }
                }
            if (configuration) {
                OutlinedTextField(
                    token,
                    {
                        token = it
                        credentialError = null
                    },
                    label = { Text("私有仓库只读令牌（可选）") },
                    supportingText = {
                        Text(
                            credentialError
                                ?: "GitHub fine-grained token：此仓库 Contents 只读。凭据加密保存在本机。"
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !checking,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            try {
                                prefs.repository = repository
                                prefs.token(token)
                                message = if (token.isBlank()) "访问凭据已清除" else "访问凭据已保存"
                            } catch (e: Exception) {
                                message = "访问凭据未能保存"
                            }
                        },
                        enabled = !checking,
                    ) {
                        Text("保存配置")
                    }
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/$repository/releases"),
                                )
                            )
                        }
                    ) {
                        Text("项目发布页")
                    }
                }
            }
            result?.candidates?.forEach { candidate ->
                ElevatedCard {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "${candidate.title} ${candidate.versionName}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "${labels[candidate.channel] ?: candidate.channel} · ${candidate.abis.joinToString().ifEmpty { "通用" }} · ${candidate.bytes / 1024 / 1024} MB",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(candidate.fileName, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { startDownload(candidate) }, enabled = enabled) {
                            Text("下载并核对")
                        }
                    }
                }
            }
            if (records.isNotEmpty()) Text("下载与断点", style = MaterialTheme.typography.titleLarge)
            records.forEach { record ->
                val candidate = UpdateCandidate.decode(record.getJSONObject("candidate"))
                val state = record.getString("state")
                Card {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "${candidate.title} ${candidate.versionName}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            record.optString("detail"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (state != "READY")
                            LinearProgressIndicator(
                                progress = {
                                    (record.optLong("bytes").toFloat() /
                                            candidate.bytes.coerceAtLeast(1))
                                        .coerceIn(0f, 1f)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        Text(
                            "${record.optLong("bytes") / 1024} / ${candidate.bytes / 1024} KB",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (state) {
                                "READY" ->
                                    Button(
                                        onClick = { install(candidate) },
                                        enabled = !installing,
                                    ) {
                                        Text("安装")
                                    }
                                "RUNNING",
                                "WAITING",
                                "VERIFYING" ->
                                    OutlinedButton(
                                        onClick = {
                                            if (autoInstallSha == candidate.sha256)
                                                autoInstallSha = ""
                                            pendingPermission = ""
                                            UpdateFiles.pause(context, candidate)
                                            revision++
                                        }
                                    ) {
                                        Text("暂停并保留")
                                    }
                                "FAILED" ->
                                    Button(
                                        onClick = { startDownload(candidate, restart = true) },
                                        enabled = enabled,
                                    ) {
                                        Text("重新下载")
                                    }
                                else ->
                                    Button(
                                        onClick = { startDownload(candidate) },
                                        enabled = enabled,
                                    ) {
                                        Text("继续下载")
                                    }
                            }
                            TextButton(
                                onClick = {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(candidate.releaseUrl))
                                    )
                                }
                            ) {
                                Text("发布说明")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
