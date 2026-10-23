package cn.paperforge.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.data.PageRow
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot

@Composable
fun pageBitmap(page: PageRow,edge: Int=1200,original: Boolean=false): Bitmap? {
    val bitmap by produceState<Bitmap?>(null,page,edge,original) {
        value=withContext(Dispatchers.Default) { runCatching { if(original) ScanProcessor.decode(File(page.original),edge) else ScanProcessor.render(page,edge) }.getOrNull() }
    }
    return bitmap
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: MainViewModel,onImport: ()->Unit) {
    val pages by vm.pages.collectAsState()
    val doc by vm.selected.collectAsState()
    val busy by vm.busy.collectAsState()
    var activeId by remember { mutableStateOf<String?>(null) }
    val active=pages.find { it.id==activeId } ?: pages.firstOrNull()
    var crop by remember { mutableStateOf(false) }
    var name by remember(doc?.id) { mutableStateOf(doc?.title ?: "") }
    var export by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var paper by remember { mutableStateOf("Auto") }
    var resolution by remember { mutableStateOf("STANDARD") }
    var quality by remember { mutableIntStateOf(90) }
    var adjust by remember { mutableStateOf(false) }
    var searchable by remember { mutableStateOf(true) }
    var original by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    Scaffold(topBar={ TopAppBar(title={ Text("整理扫描") },navigationIcon={ IconButton(onClick={ vm.screen.value="home" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回") } },actions={
        IconButton(onClick=vm::undo,enabled=!busy) { Icon(Icons.AutoMirrored.Filled.Undo,"撤销") }
        IconButton(onClick=vm::redo,enabled=!busy) { Icon(Icons.AutoMirrored.Filled.Redo,"重做") }
        IconButton(onClick={ vm.screen.value="pages" },enabled=!busy) { Icon(Icons.Default.GridView,"页面管理") }
    }) },bottomBar={ Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick={ vm.screen.value="capture" },enabled=!busy) { Icon(Icons.Default.Add,"增页"); Text("继续拍") }
        OutlinedButton(onClick=onImport,enabled=!busy) { Text("导图") }
        Button(onClick={ export=true },enabled=pages.isNotEmpty() && !busy,modifier=Modifier.weight(1f)) { Text("导出 PDF") }
    } }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(value=name,onValueChange={ name=it },label={ Text("文档名称") },singleLine=true,trailingIcon={ IconButton(onClick={ doc?.let { vm.rename(it,name) } },enabled=!busy) { Icon(Icons.Default.Check,"保存名称") } },modifier=Modifier.fillMaxWidth())
            if(active==null) {
                Column(Modifier.fillMaxWidth().height(320.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                    Icon(Icons.Default.NoteAdd,null,modifier=Modifier.size(48.dp),tint=MaterialTheme.colorScheme.primary)
                    Text("草稿已保存，添加第一页吧",modifier=Modifier.padding(20.dp))
                }
            } else {
                val bitmap=pageBitmap(active,original=original)
                Box(Modifier.fillMaxWidth().height(340.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp)).pointerInput(active.id) { detectTapGestures(onPress={ original=true; tryAwaitRelease(); original=false }) },contentAlignment=Alignment.Center) {
                    if(bitmap!=null) Image(bitmap.asImageBitmap(),"扫描预览",modifier=Modifier.fillMaxSize().padding(12.dp),contentScale=ContentScale.Fit)
                    else CircularProgressIndicator()
                    Surface(Modifier.align(Alignment.BottomEnd).padding(12.dp),shape=RoundedCornerShape(8.dp),color=MaterialTheme.colorScheme.surface) { Text("${pages.indexOf(active)+1} / ${pages.size}",modifier=Modifier.padding(horizontal=10.dp,vertical=5.dp)) }
                }
                LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(pages,key={ _,it -> it.id }) { i,page ->
                        val thumb=pageBitmap(page,240)
                        Column(Modifier.width(64.dp).clickable { activeId=page.id }.border(if(active.id==page.id) 2.dp else 0.dp,MaterialTheme.colorScheme.primary,RoundedCornerShape(8.dp)).padding(4.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                            thumb?.let { Image(it.asImageBitmap(),"第 ${i+1} 页",modifier=Modifier.height(68.dp)) }; Text("${i+1}")
                        }
                    }
                }
                if(active.detectionConfidence!=null && active.detectionConfidence<.72f) Text("边缘证据不足，请检查四角；原图已保留。",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                    ToolAction(Icons.Default.Crop,"调四角",!busy) { crop=true }
                    ToolAction(Icons.Default.RotateRight,"旋转",!busy) { vm.save(active.copy(rotation=(active.rotation+90)%360)) }
                    ToolAction(Icons.Default.Tune,"调参数",!busy) { adjust=true }
                    ToolAction(Icons.Default.TextFields,"识文字",!busy) { vm.recognize(active,true) }
                    ToolAction(Icons.Default.DeleteOutline,"删除",!busy) { confirmDelete=true }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                    TextButton(onClick={ vm.detectPage(active) },enabled=!busy) { Text("自动裁边") }
                    TextButton(onClick={ vm.applyAll(active) },enabled=!busy) { Text("滤镜应用全部") }
                    TextButton(onClick={ vm.replacement.value=active.id; vm.screen.value="capture" },enabled=!busy) { Text("重拍本页") }
                    TextButton(onClick={ more=true },enabled=!busy) { Text("更多") }
                }
                LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    val names=listOf("原图","自动增强","去阴影","灰度","黑白文档","彩色增强","白板")
                    itemsIndexed(ScanProcessor.filters) { i,filter ->
                        val sample=pageBitmap(active.copy(filter=filter),160)
                        FilterChip(selected=active.filter==filter,onClick={ if(!busy) vm.save(active.copy(filter=filter)) },label={ Text(names[i]) },leadingIcon={ sample?.let { Image(it.asImageBitmap(),null,Modifier.size(32.dp)) } },enabled=!busy)
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton(onClick={ vm.move(active,-1) },enabled=pages.indexOf(active)>0 && !busy) { Text("← 前移一页") }
                    TextButton(onClick={ vm.move(active,1) },enabled=pages.indexOf(active)<pages.lastIndex && !busy) { Text("后移一页 →") }
                }
                Text("按住预览对比原图 · 调整自动保存 · 撤销历史可跨进程恢复",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if(crop && active!=null) CropDialog(active,onDismiss={ crop=false },onSave={ vm.save(active.copy(corners=it.encode(),detectionConfidence=null,detectionMethod="manual")); crop=false })
    if(confirmDelete && active!=null) AlertDialog(onDismissRequest={ confirmDelete=false },title={ Text("删除这一页？") },text={ Text("会删除这页的私有原图和编辑参数。") },confirmButton={ TextButton(onClick={ vm.deletePage(active); confirmDelete=false }) { Text("删除") } },dismissButton={ TextButton(onClick={ confirmDelete=false }) { Text("保留") } })
    if(adjust && active!=null) {
        var brightness by remember { mutableFloatStateOf(active.brightness) }
        var contrast by remember { mutableFloatStateOf(active.contrast) }
        var strength by remember { mutableFloatStateOf(active.enhanceStrength) }
        AlertDialog(onDismissRequest={ adjust=false },title={ Text("增强与明暗") },text={ Column {
            Text("去阴影强度 %.0f%%".format(strength*100));Slider(value=strength,onValueChange={ strength=it },valueRange=0f..1f,enabled=active.filter!="ORIGINAL")
            Text("亮度 %+.0f".format(brightness*100)); Slider(value=brightness,onValueChange={ brightness=it },valueRange=-.5f.. .5f)
            Text("对比度 %.2f".format(contrast)); Slider(value=contrast,onValueChange={ contrast=it },valueRange=.5f..1.8f)
        } },confirmButton={ TextButton(onClick={ vm.save(active.copy(brightness=brightness,contrast=contrast,enhanceStrength=strength)); adjust=false }) { Text("应用") } },dismissButton={ TextButton(onClick={ adjust=false }) { Text("取消") } })
    }
    if(export) AlertDialog(onDismissRequest={ export=false },title={ Text("导出多页 PDF") },text={ Column {
        Text("所有 ${pages.size} 页")
        Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(searchable,{ searchable=it }); Text("离线 OCR · 可搜索文字层") }
        TextButton(onClick={ paper="ID" }) { Text(if(paper=="ID") "✓ 证件双面 A4 合版" else "证件双面 A4 合版") }
        Row(Modifier.horizontalScroll(rememberScrollState())) { listOf("Auto","A4","A5","Letter").forEach { p -> TextButton(onClick={ paper=p }) { Text(if(p=="Auto") "匹配扫描尺寸" else p,color=if(p==paper) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) } } }
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            listOf("DRAFT" to "草稿 150 dpi","STANDARD" to "标准 220 dpi","HIGH" to "高清 300 dpi").forEach { (value,label) -> FilterChip(resolution==value,{ resolution=value },label={ Text(label) }) }
        }
        Text("分辨率与压缩画质分别设置；源照片像素不足时保留实际清晰度，不虚构像素。固定纸张按比例留边。",style=MaterialTheme.typography.bodySmall)
        Text("图像质量 $quality"); Slider(value=quality.toFloat(),onValueChange={ quality=it.toInt() },valueRange=50f..100f,steps=9)
        Text("后台生成后可预览、保存到任意文件夹或分享。",style=MaterialTheme.typography.bodySmall)
    } },confirmButton={ TextButton(onClick={ export=false; vm.export(paper,quality,name,searchable,resolution) },enabled=!busy) { Text("生成 PDF") } },dismissButton={ TextButton(onClick={ export=false }) { Text("取消") } })
    if(more && active!=null) AlertDialog(onDismissRequest={ more=false },title={ Text("扫描页面工具") },text={ Column {
        TextButton(onClick={ more=false; vm.duplicate(active) }) { Text("复制当前页") }
        TextButton(onClick={ more=false; vm.splitBook(active) }) { Text("书本双页从中缝分割") }
        TextButton(onClick={ more=false; vm.recognizeAll() }) { Text("识别全部页面文字") }
        TextButton(onClick={ more=false; vm.images("jpg") }) { Text("全部页面导出 JPG 图片包") }
        TextButton(onClick={ more=false; vm.images("png") }) { Text("全部页面导出 PNG 图片包") }
    } },confirmButton={ TextButton(onClick={ more=false }) { Text("关闭") } })
}

@Composable
fun ToolAction(icon: androidx.compose.ui.graphics.vector.ImageVector,label: String,enabled: Boolean=true,onClick: ()->Unit) {
    Column(Modifier.widthIn(min=56.dp).clickable(enabled=enabled,role=androidx.compose.ui.semantics.Role.Button,onClick=onClick),horizontalAlignment=Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp),contentAlignment=Alignment.Center) { Icon(icon,label,tint=if(enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha=.38f)) }
        Text(label,style=MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CropDialog(page: PageRow,onDismiss: ()->Unit,onSave: (Quad)->Unit) {
    val bitmap=pageBitmap(page,1400,true)
    var points by remember { mutableStateOf(Quad.decode(page.corners).points) }
    var dragged by remember { mutableIntStateOf(-1) }
    var dragPoint by remember { mutableStateOf(Offset.Unspecified) }
    var detecting by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    Dialog(onDismissRequest=onDismiss,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
            Column(Modifier.systemBarsPadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("沿纸边拖动四角",style=MaterialTheme.typography.headlineSmall)
                Text("长按角点移动；放大镜辅助对齐。",style=MaterialTheme.typography.bodyMedium)
                Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
                    bitmap?.let { b ->
                        BoxWithConstraints(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                            val ratio=b.width.toFloat()/b.height
                            val imageModifier=if(maxWidth/maxHeight>ratio) Modifier.fillMaxHeight().aspectRatio(ratio) else Modifier.fillMaxWidth().aspectRatio(ratio)
                            Box(imageModifier.magnifier(sourceCenter={ dragPoint },zoom=2f)) {
                                Image(b.asImageBitmap(),"裁切原图",modifier=Modifier.fillMaxSize())
                                Canvas(Modifier.fillMaxSize().pointerInput(b) {
                                    detectDragGestures(onDragStart={ position ->
                                        dragged=points.indices.minBy { i -> hypot(points[i].x*size.width-position.x,points[i].y*size.height-position.y) }
                                        dragPoint=position
                                    },onDragEnd={ dragged=-1; dragPoint=Offset.Unspecified },onDragCancel={ dragged=-1; dragPoint=Offset.Unspecified }) { change,_ ->
                                        change.consume()
                                        if(dragged>=0) {
                                            val candidate=points.toMutableList()
                                            candidate[dragged]=PointN((change.position.x/size.width).coerceIn(0f,1f),(change.position.y/size.height).coerceIn(0f,1f))
                                            if(Quad(candidate).valid()) points=candidate
                                            dragPoint=change.position
                                        }
                                    }
                                }) {
                                    val path=Path().apply { points.forEachIndexed { i,p -> if(i==0) moveTo(p.x*size.width,p.y*size.height) else lineTo(p.x*size.width,p.y*size.height) }; close() }
                                    drawPath(path,Color(0xFF176B54).copy(alpha=.15f)); drawPath(path,Color(0xFF7DFFBD),style=Stroke(3.dp.toPx()))
                                    points.forEach { p -> drawCircle(Color.White,10.dp.toPx(),Offset(p.x*size.width,p.y*size.height)); drawCircle(Color(0xFF176B54),5.dp.toPx(),Offset(p.x*size.width,p.y*size.height)) }
                                }
                            }
                        }
                    } ?: CircularProgressIndicator()
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton(onClick={ points=Quad.Full.points }) { Text("全图") }
                    TextButton(enabled=bitmap!=null && !detecting,onClick={
                        detecting=true
                        scope.launchIoDetect(bitmap!!) { q -> q?.let { points=it.points }; detecting=false }
                    }) { Text(if(detecting) "检测中…" else "重新检测") }
                    TextButton(onClick=onDismiss) { Text("取消") }
                    Button(onClick={ onSave(Quad(points)) },enabled=Quad(points).valid()) { Text("应用") }
                }
            }
        }
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchIoDetect(bitmap: Bitmap,done: (Quad?)->Unit) = launch { done(withContext(Dispatchers.Default) { runCatching { ScanProcessor.detect(bitmap) }.getOrNull() }) }
