package cn.paperforge.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.data.DocumentRow
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HomeScreen(vm: MainViewModel,onImages: ()->Unit,onPdf: ()->Unit,onMerge: ()->Unit,onAbout: ()->Unit) {
    val docs by vm.documents.collectAsState()
    val busy by vm.busy.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<DocumentRow?>(null) }
    var renaming by remember { mutableStateOf<DocumentRow?>(null) }
    Scaffold(bottomBar={ NavigationBar {
        listOf("首页" to Icons.Default.Home,"文档" to Icons.AutoMirrored.Filled.Article,"工具" to Icons.Default.GridView).forEachIndexed { i,(label,icon) ->
            NavigationBarItem(selected=tab==i,onClick={ tab=i },icon={ Icon(icon,label) },label={ Text(label) })
        }
    } }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(),contentPadding=PaddingValues(22.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("纸匠",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold); Text("PAPERFORGE  /  离线文档工作台",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick=onAbout) { Icon(Icons.Default.Info,"关于与隐私") }
            } }
            if(tab==0) {
                item { Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF176B54)),shape=RoundedCornerShape(26.dp)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("把纸张，\n收进日常。",color=Color.White,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
                        Text("拍下灵感、讲义和每一份重要。",color=Color(0xFFCEEBDE),style=MaterialTheme.typography.bodyMedium)
                        Button(onClick={ vm.newScan() },enabled=!busy,colors=ButtonDefaults.buttonColors(containerColor=Color.White,contentColor=Color(0xFF176B54))) { Icon(Icons.Default.DocumentScanner,null); Spacer(Modifier.width(8.dp)); Text("扫描文档") }
                    }
                } }
                item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    QuickCard("相册导入",Icons.Default.AddPhotoAlternate,Modifier.weight(1f),!busy,onImages)
                    QuickCard("打开 PDF",Icons.Default.PictureAsPdf,Modifier.weight(1f),!busy,onPdf)
                    QuickCard("本地文档",Icons.Default.FolderOpen,Modifier.weight(1f),!busy) { tab=1 }
                } }
                item { Text("常用工具",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold) }
                item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    QuickCard("合并 PDF",Icons.Default.Merge,Modifier.weight(1f),!busy,onMerge)
                    QuickCard("PDF 工具",Icons.Default.Handyman,Modifier.weight(1f),!busy) { tab=2 }
                } }
            }
            if(tab==2) {
                item { Text("小工具，大用处",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold) }
                item { Text("每个入口都有实际流程。页面工具在打开 PDF 后使用。",color=MaterialTheme.colorScheme.onSurfaceVariant) }
                item { ToolCard("合并 PDF","选择两份或更多 PDF，按选择器返回顺序合并",Icons.Default.Merge,!busy,onMerge) }
                item { ToolCard("图片转 PDF","批量导图、调四角、滤镜、排序与纸张尺寸",Icons.Default.Collections,!busy,onImages) }
                item { ToolCard("抽页 / 拆分 / 旋转","打开 PDF → 底部抽页工具 → 输入页码",Icons.Default.ContentCut,!busy,onPdf) }
                item { ToolCard("PDF 图像压缩","打开 PDF → 图像压缩 → 对比真实体积",Icons.Default.Compress,!busy,onPdf) }
                item { ToolCard("提取 PDF 文字","保留可提取的原有文本；扫描图可单独 OCR",Icons.Default.TextSnippet,!busy,onPdf) }
                item { ToolCard("中英文离线 OCR","导入图片 → 整理扫描 → 识文字",Icons.Default.Translate,!busy,onImages) }
            } else {
                item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(if(tab==0) "最近文档" else "我的文档",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f)); Text("${docs.size} 份",style=MaterialTheme.typography.labelMedium)
                } }
                if(tab==1) item { OutlinedTextField(value=search,onValueChange={ search=it },label={ Text("搜索文档名称") },leadingIcon={ Icon(Icons.Default.Search,null) },modifier=Modifier.fillMaxWidth(),singleLine=true) }
                val filtered=docs.filter { it.title.contains(search,true) }.let { if(tab==0) it.take(6) else it }
                if(filtered.isEmpty()) item { Column(Modifier.fillMaxWidth().padding(vertical=34.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Inbox,null,modifier=Modifier.size(44.dp),tint=MaterialTheme.colorScheme.outline)
                    Text(if(search.isEmpty()) "第一份文档，从这里开始" else "没有匹配的文档",modifier=Modifier.padding(10.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(search.isEmpty()) Text("扫描、导入，全程留在设备。",style=MaterialTheme.typography.bodySmall)
                } }
                items(filtered,key={ it.id }) { doc ->
                    Card(onClick={ vm.open(doc) },enabled=!busy,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                            Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.primaryContainer) { Icon(if(doc.kind=="scan") Icons.Default.Description else Icons.Default.PictureAsPdf,null,modifier=Modifier.padding(14.dp),tint=MaterialTheme.colorScheme.primary) }
                            Column(Modifier.weight(1f).padding(horizontal=14.dp)) {
                                Text(doc.title,maxLines=2,style=MaterialTheme.typography.titleMedium)
                                Text((if(doc.kind=="scan") "扫描草稿" else "PDF 私有副本")+" · "+SimpleDateFormat("MM/dd HH:mm",Locale.CHINA).format(Date(doc.modified)),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            var menu by remember { mutableStateOf(false) }
                            Box { IconButton(onClick={ menu=true }) { Icon(Icons.Default.MoreVert,"文档操作") }; DropdownMenu(expanded=menu,onDismissRequest={ menu=false }) {
                                DropdownMenuItem(text={ Text("重命名") },onClick={ menu=false; renaming=doc })
                                DropdownMenuItem(text={ Text("删除本地文档") },onClick={ menu=false; deleting=doc })
                            } }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
    deleting?.let { d -> AlertDialog(onDismissRequest={ deleting=null },title={ Text("删除「${d.title}」？") },text={ Text("删除本应用中的副本及草稿原图。通过系统另存的文件需在文件管理器中删除。") },confirmButton={ TextButton(onClick={ vm.delete(d); deleting=null }) { Text("删除") } },dismissButton={ TextButton(onClick={ deleting=null }) { Text("取消") } }) }
    renaming?.let { d -> var name by remember { mutableStateOf(d.title) }; AlertDialog(onDismissRequest={ renaming=null },title={ Text("重命名文档") },text={ OutlinedTextField(value=name,onValueChange={ name=it },singleLine=true) },confirmButton={ TextButton(onClick={ vm.rename(d,name); renaming=null }) { Text("保存") } },dismissButton={ TextButton(onClick={ renaming=null }) { Text("取消") } }) }
}

@Composable private fun QuickCard(label: String,icon: ImageVector,modifier: Modifier,enabled: Boolean,onClick: ()->Unit) {
    Card(onClick=onClick,enabled=enabled,modifier=modifier,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow),shape=RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical=20.dp,horizontal=8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) { Icon(icon,null,tint=MaterialTheme.colorScheme.primary); Text(label,style=MaterialTheme.typography.labelLarge) }
    }
}
@Composable private fun ToolCard(title: String,description: String,icon: ImageVector,enabled: Boolean,onClick: ()->Unit) {
    Card(onClick=onClick,enabled=enabled,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(18.dp)) {
            Icon(icon,null,tint=MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) { Text(title,style=MaterialTheme.typography.titleMedium); Text(description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(Icons.Default.ChevronRight,null)
        }
    }
}
