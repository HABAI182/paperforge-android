package cn.paperforge.network

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.work.*
import kotlinx.coroutines.*

class UpdatesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark =
            when (intent.getStringExtra("paperforgeTheme")) {
                "DARK" -> true
                "LIGHT" -> false
                else ->
                    resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        val bar =
            if (dark) SystemBarStyle.dark(0xff101815.toInt())
            else SystemBarStyle.light(0xfff6f8f2.toInt(), 0xff101815.toInt())
        enableEdgeToEdge(statusBarStyle = bar, navigationBarStyle = bar)
        setContent {
            val colors =
                if (dark)
                    darkColorScheme(
                        primary = Color(0xffa1d5c4),
                        onPrimary = Color(0xff09382d),
                        primaryContainer = Color(0xff204f42),
                        onPrimaryContainer = Color(0xffccecdf),
                        secondary = Color(0xffb7cbbf),
                        onSecondary = Color(0xff22352a),
                        secondaryContainer = Color(0xff344b3f),
                        onSecondaryContainer = Color(0xffd7e9dc),
                        background = Color(0xff101815),
                        onBackground = Color(0xffe5ece5),
                        surface = Color(0xff101815),
                        onSurface = Color(0xffe5ece5),
                        surfaceVariant = Color(0xff2c3a33),
                        onSurfaceVariant = Color(0xffbdc9c0),
                        outline = Color(0xff8a998e),
                        surfaceContainerLowest = Color(0xff0b130f),
                        surfaceContainerLow = Color(0xff15211a),
                        surfaceContainer = Color(0xff1b2a21),
                        surfaceContainerHigh = Color(0xff24342b),
                        surfaceContainerHighest = Color(0xff2c3a33),
                    )
                else
                    lightColorScheme(
                        primary = Color(0xff226c5b),
                        onPrimary = Color.White,
                        primaryContainer = Color(0xffccecdf),
                        onPrimaryContainer = Color(0xff10382a),
                        secondary = Color(0xff476354),
                        onSecondary = Color.White,
                        secondaryContainer = Color(0xffd7e9dc),
                        onSecondaryContainer = Color(0xff23372b),
                        background = Color(0xfff6f8f2),
                        onBackground = Color(0xff17261c),
                        surface = Color(0xfff6f8f2),
                        onSurface = Color(0xff17261c),
                        surfaceVariant = Color(0xffe1eae1),
                        onSurfaceVariant = Color(0xff425249),
                        outline = Color(0xff6f8175),
                        surfaceContainerLowest = Color.White,
                        surfaceContainerLow = Color(0xfff0f5ec),
                        surfaceContainer = Color(0xffeaf0e6),
                        surfaceContainerHigh = Color(0xffe2eadd),
                        surfaceContainerHighest = Color(0xffdbe5d5),
                    )
            val density = LocalDensity.current
            val factor = intent.getFloatExtra("paperforgeFontScale", 1f).coerceIn(.85f, 1.3f)
            CompositionLocalProvider(
                LocalDensity provides
                    Density(
                        density.density,
                        if (density.fontScale >= 2f) density.fontScale
                        else (density.fontScale * factor).coerceAtMost(2f),
                    )
            ) {
                MaterialTheme(colorScheme = colors) { UpdatesScreen { finish() } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun UpdatesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
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
    val installed = remember(revision) { GitHubUpdates.installed(context) }
    val labels = mapOf("stable" to "正式版", "prerelease" to "含预发布", "development" to "含开发版")

    fun startDownload(candidate: UpdateCandidate, restart: Boolean = false) {
        try {
            UpdateFiles.enqueue(context, candidate, restart)
            revision++
            message = "已加入下载队列，可暂停后继续"
        } catch (e: Exception) {
            message = e.message ?: "无法开始下载"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("检查更新") },
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
            Text("PaperForge · 联网工具", style = MaterialTheme.typography.headlineSmall)
            Text(
                "按需检查与下载；文件校验通过后交给系统安装。PaperForge主应用保持离线。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Card {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("启用可选更新", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (enabled) "仅手动检查，不自动安装" else "已关闭联网检查与下载",
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
                    "${GitHubUpdates.packages[id]}  ${value.versionName} · ${value.versionCode}",
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
            Button(
                onClick = {
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
                                        GitHubUpdates(prefs.token())
                                            .check(
                                                repository,
                                                channel,
                                                GitHubUpdates.installed(context),
                                            )
                                    }
                                result = checked
                                message =
                                    when {
                                        checked.candidates.isNotEmpty() ->
                                            "找到 ${checked.candidates.size} 个适用安装包"
                                        checked.warnings.isNotEmpty() -> "检查未能完整核对，请查看下面的说明"
                                        checked.releases == 0 -> "所选渠道尚无发布版本"
                                        else -> "已检查 ${checked.releases} 个发布，未发现高于已安装版本的适配安装包"
                                    }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                message = e.message ?: "检查失败，请稍后重试"
                            } finally {
                                if (serial == checkSerial) checking = false
                            }
                        }
                },
                enabled = enabled && !checking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (checking) "正在检查" else "立即检查")
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
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            result?.warnings?.forEach {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
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
                                        onClick = {
                                            scope.launch {
                                                try {
                                                    val file = UpdateFiles.ready(context, candidate)
                                                    withContext(Dispatchers.IO) {
                                                        UpdateApkVerifier.verify(
                                                            context,
                                                            file,
                                                            candidate,
                                                        )
                                                    }
                                                    if (
                                                        !context.packageManager
                                                            .canRequestPackageInstalls()
                                                    ) {
                                                        context.startActivity(
                                                            Intent(
                                                                Settings
                                                                    .ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                                Uri.parse(
                                                                    "package:${context.packageName}"
                                                                ),
                                                            )
                                                        )
                                                        message = "在系统页允许安装应用后，返回这里再点安装"
                                                    } else {
                                                        val uri =
                                                            FileProvider.getUriForFile(
                                                                context,
                                                                context.packageName + ".files",
                                                                file,
                                                            )
                                                        context.startActivity(
                                                            Intent(Intent.ACTION_VIEW)
                                                                .setDataAndType(
                                                                    uri,
                                                                    "application/vnd.android.package-archive",
                                                                )
                                                                .addFlags(
                                                                    Intent
                                                                        .FLAG_GRANT_READ_URI_PERMISSION
                                                                )
                                                        )
                                                    }
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    message = e.message ?: "无法打开系统安装器"
                                                }
                                            }
                                        }
                                    ) {
                                        Text("安装")
                                    }
                                "RUNNING",
                                "WAITING",
                                "VERIFYING" ->
                                    OutlinedButton(
                                        onClick = {
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
