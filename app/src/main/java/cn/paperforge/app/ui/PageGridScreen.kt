package cn.paperforge.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import kotlin.math.*
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageGridScreen(vm: MainViewModel) {
    val pages by vm.pages.collectAsState(); val busy by vm.busy.collectAsState()
    var order by remember(pages) { mutableStateOf(pages) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var dragged by remember { mutableStateOf<String?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    val grid=rememberLazyGridState()
    val currentOrder by rememberUpdatedState(order)
    val currentSelection by rememberUpdatedState(selected)
    Scaffold(topBar={ TopAppBar(title={ Text("页面管理 · ${pages.size} 页") },navigationIcon={ IconButton(onClick={ vm.screen.value="editor" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回") } }) },bottomBar={
        Row(Modifier.navigationBarsPadding().fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.SpaceEvenly) {
            TextButton(onClick={ selected=if(selected.size==pages.size) emptySet() else pages.map { it.id }.toSet() }) { Text("全选") }
            TextButton(enabled=selected.isNotEmpty() && !busy,onClick={ vm.rotateMany(selected) }) { Text("旋转") }
            TextButton(enabled=selected.isNotEmpty() && !busy,onClick={ vm.deleteMany(selected); selected=emptySet() }) { Text("删除") }
            TextButton(onClick={ vm.screen.value="editor" }) { Text("完成") }
        }
    }) { padding ->
        Column(Modifier.padding(padding)) {
            Text("长按缩略图拖动排序；点击勾选可批量处理",modifier=Modifier.padding(16.dp),style=MaterialTheme.typography.bodyMedium)
            LazyVerticalGrid(columns=GridCells.Adaptive(110.dp),state=grid,contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.fillMaxSize().pointerInput(busy) {
                if(!busy) detectDragGesturesAfterLongPress(onDragStart={ point ->
                    pointer=point
                    val hit=grid.layoutInfo.visibleItemsInfo.firstOrNull { point.x>=it.offset.x && point.x<=it.offset.x+it.size.width && point.y>=it.offset.y && point.y<=it.offset.y+it.size.height }
                    dragged=hit?.key as? String
                },onDragEnd={ if(dragged!=null) vm.reorder(currentOrder); dragged=null },onDragCancel={ dragged=null }) { change,amount ->
                    change.consume(); pointer+=amount
                    val target=grid.layoutInfo.visibleItemsInfo.firstOrNull { pointer.x>=it.offset.x && pointer.x<=it.offset.x+it.size.width && pointer.y>=it.offset.y && pointer.y<=it.offset.y+it.size.height }?.key as? String
                    val from=order.indexOfFirst { it.id==dragged }; val to=order.indexOfFirst { it.id==target }
                    if(from>=0 && to>=0 && from!=to) order=order.toMutableList().apply { add(to,removeAt(from)) }
                }
            }) {
                itemsIndexed(order,key={ _,p -> p.id }) { index,page ->
                    Card(onClick={ selected=if(page.id in currentSelection) currentSelection-page.id else currentSelection+page.id },border=BorderStroke(if(page.id in selected || page.id==dragged) 3.dp else 1.dp,if(page.id in selected || page.id==dragged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                            val bitmap=pageBitmap(page,360)
                            bitmap?.let { Image(it.asImageBitmap(),"页面 $index",Modifier.fillMaxWidth().height(145.dp)) }
                            Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(page.id in selected,{ checked -> selected=if(checked) selected+page.id else selected-page.id }); Text("${index+1}") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeScreen(vm: MainViewModel) {
    val sources by vm.mergeSources.collectAsState()
    Scaffold(topBar={ TopAppBar(title={ Text("合并顺序") },navigationIcon={ IconButton(onClick={ vm.screen.value="home" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回") } }) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("调整源文件顺序，合并后可用抽页工具重新排列单页。")
            sources.forEachIndexed { index,doc -> Card { Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("${index+1}. ${doc.title}",Modifier.weight(1f))
                TextButton(enabled=index>0,onClick={ vm.mergeSources.value=sources.toMutableList().apply { add(index-1,removeAt(index)) } }) { Text("↑") }
                TextButton(enabled=index<sources.lastIndex,onClick={ vm.mergeSources.value=sources.toMutableList().apply { add(index+1,removeAt(index)) } }) { Text("↓") }
                TextButton(onClick={ vm.mergeSources.value=sources.filter { it.id!=doc.id } }) { Text("移除") }
            } } }
            Button(enabled=sources.size>=2,onClick={ vm.queueTool("merge") },modifier=Modifier.fillMaxWidth()) { Text("合并 ${sources.size} 份 PDF") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfPageGridScreen(vm: MainViewModel) {
    val doc by vm.selected.collectAsState(); val busy by vm.busy.collectAsState()
    val file=doc?.pdfPath?.let { File(it) }
    val session by produceState<PdfSession?>(null,file) { value=withContext(Dispatchers.IO) { runCatching { file?.let { PdfSession(it) } }.getOrNull() } }
    val active=session; DisposableEffect(active) { onDispose { active?.close() } }
    var order by remember(active) { mutableStateOf((0 until (active?.count ?: 0)).toList()) }
    var selected by remember { mutableStateOf(emptySet<Int>()) }; var rotations by remember { mutableStateOf(emptyMap<Int,Int>()) }
    var dragged by remember { mutableStateOf<Int?>(null) }; var pointer by remember { mutableStateOf(Offset.Zero) }
    val grid=rememberLazyGridState(); val currentOrder by rememberUpdatedState(order)
    Scaffold(topBar={ TopAppBar(title={ Text("PDF 页面编排") },navigationIcon={ IconButton(onClick={ vm.screen.value="reader" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回") } },actions={
        TextButton(enabled=order.isNotEmpty() && !busy,onClick={ vm.queueTool("organize",JSONObject().put("order",JSONArray(order)).put("rotations",JSONObject(rotations.mapKeys { it.key.toString() }))) }) { Text("保存副本") }
    }) },bottomBar={ Row(Modifier.navigationBarsPadding().fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.SpaceEvenly) {
        TextButton(onClick={ selected=if(selected.size==order.size) emptySet() else order.toSet() }) { Text("全选") }
        TextButton(enabled=selected.isNotEmpty() && !busy,onClick={ rotations=rotations+selected.associateWith { ((rotations[it] ?: 0)+90)%360 } }) { Text("旋转") }
        TextButton(enabled=selected.isNotEmpty() && selected.size<order.size && !busy,onClick={ order=order.filter { it !in selected }; selected=emptySet() }) { Text("删除") }
        TextButton(onClick={ order=(0 until (active?.count ?: 0)).toList(); rotations=emptyMap(); selected=emptySet() }) { Text("重置") }
    } }) { padding -> Column(Modifier.padding(padding)) {
        Text("长按拖动页序，勾选批量旋转或删除；保存为新 PDF。",Modifier.padding(16.dp),style=MaterialTheme.typography.bodyMedium)
        LazyVerticalGrid(GridCells.Adaptive(110.dp),state=grid,contentPadding=PaddingValues(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.fillMaxSize().pointerInput(busy) {
            if(!busy) detectDragGesturesAfterLongPress(onDragStart={ p -> pointer=p; dragged=grid.layoutInfo.visibleItemsInfo.firstOrNull { p.x>=it.offset.x && p.x<=it.offset.x+it.size.width && p.y>=it.offset.y && p.y<=it.offset.y+it.size.height }?.key as? Int },onDragEnd={ dragged=null },onDragCancel={ dragged=null }) { change,delta ->
                change.consume(); pointer+=delta
                val target=grid.layoutInfo.visibleItemsInfo.firstOrNull { pointer.x>=it.offset.x && pointer.x<=it.offset.x+it.size.width && pointer.y>=it.offset.y && pointer.y<=it.offset.y+it.size.height }?.key as? Int
                val from=currentOrder.indexOf(dragged); val to=currentOrder.indexOf(target)
                if(from>=0 && to>=0 && from!=to) order=currentOrder.toMutableList().apply { add(to,removeAt(from)) }
            }
        }) {
            itemsIndexed(order,key={ _,n -> n }) { position,n ->
                val rotation=rotations[n] ?: 0
                val image by produceState<Bitmap?>(null,active,n,rotation) { value=withContext(Dispatchers.IO) { runCatching { active?.render(n)?.let { b -> if(rotation==0) b else Bitmap.createBitmap(b,0,0,b.width,b.height,android.graphics.Matrix().apply { postRotate(rotation.toFloat()) },true) } }.getOrNull() } }
                Card(onClick={ selected=if(n in selected) selected-n else selected+n },border=BorderStroke(if(n in selected || dragged==n) 3.dp else 1.dp,if(n in selected || dragged==n) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally) { image?.let { Image(it.asImageBitmap(),"原第 ${n+1} 页",Modifier.fillMaxWidth().height(145.dp)) }; Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(n in selected,{ checked -> selected=if(checked) selected+n else selected-n }); Text("${position+1}") } }
                }
            }
        }
    } }
}
