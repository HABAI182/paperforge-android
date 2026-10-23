package cn.paperforge.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.paperforge.app.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity: ComponentActivity() {
    private var incoming by mutableStateOf<Intent?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(); incoming=if(savedInstanceState==null) intent else null
        setContent {
            val dark=isSystemInDarkTheme()
            val scheme=if(dark) darkColorScheme(primary=Color(0xFF88D6B7),secondary=Color(0xFFB5CDBE)) else lightColorScheme(primary=Color(0xFF176B54),primaryContainer=Color(0xFFD5EEE1),secondary=Color(0xFF5E7569),background=Color(0xFFFAF9F5),surface=Color(0xFFFAF9F5),surfaceContainerLow=Color(0xFFF0F1EA))
            MaterialTheme(colorScheme=scheme) {
                val vm: MainViewModel=viewModel()
                val screen by vm.screen.collectAsState()
                val busy by vm.busy.collectAsState()
                val progress by vm.progress.collectAsState()
                val output by vm.output.collectAsState()
                val ocr by vm.ocr.collectAsState()
                val canCorrectOcr by vm.canCorrectOcr.collectAsState()
                val locked by vm.outputLocked.collectAsState()
                val passwordRequest by vm.passwordRequest.collectAsState()
                val formFields by vm.formFields.collectAsState()
                val snackbar=remember { SnackbarHostState() }
                val scope=rememberCoroutineScope()
                var about by remember { mutableStateOf(false) }
                var pendingText by remember { mutableStateOf("") }
                val clipboard=LocalClipboardManager.current
                val images=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importImages(it) }
                val pdf=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> vm.importPdf(uri) } }
                val merge=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if(it.size>=2) vm.merge(it) else if(it.isNotEmpty()) scope.launch { snackbar.showSnackbar("请选择至少两份 PDF") } }
                val save=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> if(result.resultCode==RESULT_OK) result.data?.data?.let(vm::saveOutput) }
                val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::restoreBackup) }
                val saveText=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
                    uri?.let { scope.launch { runCatching { withContext(Dispatchers.IO) { contentResolver.openOutputStream(it,"wt")?.use { stream -> stream.write(pendingText.toByteArray(Charsets.UTF_8)) } ?: error("无法写入文本") } }.onSuccess { snackbar.showSnackbar("文本已保存") }.onFailure { snackbar.showSnackbar(it.message ?: "保存失败") } } }
                }
                val share: (File)->Unit = { file ->
                    scope.launch {
                        runCatching {
                            val copy=withContext(Dispatchers.IO) { File(cacheDir,"share").apply { mkdirs() }.let { File(it,file.name) }.also { file.copyTo(it,overwrite=true) } }
                            val uri=FileProvider.getUriForFile(this@MainActivity,"$packageName.files",copy)
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime(file)).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"分享文件"))
                        }.onFailure { snackbar.showSnackbar(it.message ?: "无法启动分享") }
                    }
                }
                LaunchedEffect(Unit) { for(message in vm.messages) snackbar.showSnackbar(message) }
                LaunchedEffect(incoming) {
                    incoming?.let { source ->
                        @Suppress("DEPRECATION")
                        when(source.action) {
                            Intent.ACTION_SEND -> source.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { if(source.type=="application/pdf") vm.importPdf(it) else vm.importImages(listOf(it)) }
                            Intent.ACTION_SEND_MULTIPLE -> source.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { vm.importImages(it) }
                            Intent.ACTION_VIEW -> source.data?.let { vm.importPdf(it) }
                        }
                        incoming=null
                    }
                }
                BackHandler(screen!="home" && !busy) { vm.replacement.value=null; vm.screen.value=when { screen in listOf("annotations","pdf-pages") -> "reader"; screen=="pages" || screen=="capture" && vm.pages.value.isNotEmpty() -> "editor"; else -> "home" } }
                Box(Modifier.fillMaxSize()) {
                    when(screen) {
                        "capture" -> CameraScreen(vm) { images.launch(arrayOf("image/*")) }
                        "editor" -> EditorScreen(vm) { images.launch(arrayOf("image/*")) }
                        "reader" -> ReaderScreen(vm,share)
                        "pages" -> PageGridScreen(vm)
                        "pdf-pages" -> PdfPageGridScreen(vm)
                        "merge" -> MergeScreen(vm)
                        "annotations" -> AnnotationScreen(vm)
                        else -> HomeScreen(vm,{ images.launch(arrayOf("image/*")) },{ pdf.launch(arrayOf("application/pdf")) },{ merge.launch(arrayOf("application/pdf")) },{ about=true },{ restore.launch(arrayOf("application/zip","application/octet-stream")) })
                    }
                    SnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom=88.dp))
                    if(busy) Surface(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp),shape=MaterialTheme.shapes.large,shadowElevation=4.dp) {
                        Row(Modifier.padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
                            Text(progress,style=MaterialTheme.typography.bodyMedium)
                            TextButton(onClick=vm::cancel) { Text("取消") }
                        }
                    }
                }
                output?.let { file -> AlertDialog(onDismissRequest={ vm.output.value=null },title={ Text(if(file.extension=="pdf") "PDF 已生成" else "文件已生成") },text={ Text("${file.name}\n${file.length()/1024} KB\n已保存在应用中，可另存到系统文件夹。") },confirmButton={ TextButton(onClick={ save.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime(file)).putExtra(Intent.EXTRA_TITLE,file.name)) }) { Text("另存为") } },dismissButton={ Row {
                    TextButton(onClick={ share(file) }) { Text("分享") }
                    if(file.extension=="pdf" && !locked) TextButton(onClick={ vm.previewOutput(file) }) { Text("预览") }
                    TextButton(onClick={ vm.output.value=null }) { Text("关闭") }
                } }) }
                ocr?.let { result ->
                    var text by remember(result) { mutableStateOf(result) }
                    AlertDialog(onDismissRequest={ vm.ocr.value=null },title={ Text("可复制与校正的文字") },text={
                        OutlinedTextField(value=text,onValueChange={ text=it },modifier=Modifier.fillMaxWidth().heightIn(min=160.dp,max=420.dp),label={ Text("识别可能有误，可在此修改") })
                    },confirmButton={ TextButton(onClick={ pendingText=text; saveText.launch("纸匠文字.txt") }) { Text("导出 TXT") } },dismissButton={ Row {
                        TextButton(onClick={ clipboard.setText(AnnotatedString(text)); scope.launch { snackbar.showSnackbar("已复制文字") } }) { Text("复制") }
                        if(canCorrectOcr) TextButton(onClick={ vm.correctOcr(text) }) { Text("保存校正") }
                        TextButton(onClick={ vm.ocr.value=null }) { Text("关闭") }
                    } })
                }
                passwordRequest?.let { uri ->
                    var password by remember(uri) { mutableStateOf("") }
                    AlertDialog(onDismissRequest={ vm.passwordRequest.value=null },title={ Text("输入 PDF 打开密码") },text={ Column { OutlinedTextField(password,{ password=it },visualTransformation=PasswordVisualTransformation(),label={ Text("密码") }); Text("解锁后在应用中保存可编辑副本，密码不保存。",style=MaterialTheme.typography.bodySmall) } },confirmButton={ TextButton(enabled=!busy,onClick={ vm.importPdf(uri,password) }) { Text("打开") } },dismissButton={ TextButton(onClick={ vm.passwordRequest.value=null }) { Text("取消") } })
                }
                formFields?.let { fields ->
                    var values by remember(fields) { mutableStateOf(fields.toMap()) }
                    AlertDialog(onDismissRequest={ vm.formFields.value=null },title={ Text("填写 PDF 表单") },text={ Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) { fields.forEach { (name,_) -> OutlinedTextField(values[name] ?: "",{ values=values+(name to it) },label={ Text(name) }) } } },confirmButton={ TextButton(enabled=!busy,onClick={ vm.fillForms(values) }) { Text("保存副本") } },dismissButton={ TextButton(onClick={ vm.formFields.value=null }) { Text("取消") } })
                }
                if(about) AlertDialog(onDismissRequest={ about=false },title={ Text("纸匠 PaperForge · 0.2.0") },text={ Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("非商用个人项目\n\n无需账户、无广告、无水印。扫描与识别在本机完成。应用没有网络权限，不上传文档。\n\n通过系统文件选择器导入副本；删除应用内文档不会删除外部原件。卸载会删除应用私有草稿，请先导出或备份重要文件。\n\n扫描自动裁边、去阴影、中英文 OCR、可搜索 PDF；阅读与搜索、页面整理、批注签名、安全脱敏、密码、表单、图片和文字导出、备份恢复。高保真 Office 转换、曲面书页展平和原生文字原位编辑尚未提供。\n\n开源与模型声明：",style=MaterialTheme.typography.bodyMedium)
                    Text(remember { runCatching { assets.open("LICENSE_NOTICES.txt").bufferedReader().use { it.readText() } }.getOrDefault("见源码 LICENSE_NOTICES.md") },style=MaterialTheme.typography.bodySmall)
                } },confirmButton={ TextButton(onClick={ about=false }) { Text("知道了") } })
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); incoming=intent }
}

private fun mime(file: File)=when(file.extension.lowercase()) { "pdf" -> "application/pdf"; "zip" -> "application/zip"; "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; "html" -> "text/html"; "md" -> "text/markdown"; else -> "text/plain" }
