package cn.paperforge.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.*
import org.opencv.core.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun CameraScreen(vm: MainViewModel,onImport: ()->Unit) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current
    var permitted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) }
    val request=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted=it }
    val pages by vm.pages.collectAsState()
    val busy by vm.busy.collectAsState()
    var auto by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var quad by remember { mutableStateOf<Quad?>(null) }
    var frameRatio by remember { mutableFloatStateOf(.75f) }
    var stability by remember { mutableFloatStateOf(0f) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val snapping=remember { AtomicBoolean(false) }
    val gate=remember { AutoCaptureGate() }
    val main=remember { ContextCompat.getMainExecutor(context) }
    val latestAuto by rememberUpdatedState(auto)
    val latestBusy by rememberUpdatedState(busy)
    val takePhoto: ()->Unit = {
        val imageCapture=capture
        val doc=vm.selected.value
        if(imageCapture!=null && doc!=null && !busy && snapping.compareAndSet(false,true)) {
            val file=vm.repo.captureFile(doc.id)
            imageCapture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),main,object: ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) { snapping.set(false); vm.captured(file) }
                override fun onError(exception: ImageCaptureException) { snapping.set(false); file.delete(); cameraError=exception.message }
            })
        }
    }
    val latestTakePhoto by rememberUpdatedState(takePhoto)
    LaunchedEffect(Unit) { if(!permitted) request.launch(Manifest.permission.CAMERA) }
    Column(Modifier.fillMaxSize().background(Color(0xFF121B18)).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={ vm.screen.value=if(pages.isEmpty()) "home" else "editor" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回",tint=Color.White) }
            Text("扫描文档",color=Color.White,modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
            FilterChip(selected=auto,onClick={ auto=!auto; gate.reset() },label={ Text("自动",color=Color.White) })
            IconButton(enabled=camera?.cameraInfo?.hasFlashUnit()==true,onClick={ torch=!torch; camera?.cameraControl?.enableTorch(torch) }) { Icon(if(torch) Icons.Default.FlashOn else Icons.Default.FlashOff,"闪光灯",tint=Color.White) }
        }
        Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
            if(permitted) {
                val preview=remember { PreviewView(context).apply { scaleType=PreviewView.ScaleType.FIT_CENTER; implementationMode=PreviewView.ImplementationMode.COMPATIBLE } }
                AndroidView(factory={ preview },modifier=Modifier.fillMaxSize())
                DisposableEffect(preview,lifecycle) {
                    var disposed=false
                    var provider: ProcessCameraProvider?=null
                    val executor=Executors.newSingleThreadExecutor()
                    val future=ProcessCameraProvider.getInstance(context)
                    future.addListener({
                        if(!disposed) try {
                            provider=future.get()
                            val p=Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build().also { it.surfaceProvider=preview.surfaceProvider }
                            val c=ImageCapture.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                            val a=ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                            var last=0L
                            a.setAnalyzer(executor) { image ->
                                try {
                                    val now=SystemClock.elapsedRealtime()
                                    if(now-last>180) {
                                        last=now
                                        val plane=image.planes[0]; val buffer=plane.buffer
                                        val bytes=ByteArray(image.width*image.height)
                                        for(y in 0 until image.height) for(x in 0 until image.width) bytes[y*image.width+x]=buffer.get(y*plane.rowStride+x*plane.pixelStride)
                                        val mat=Mat(image.height,image.width,CvType.CV_8UC1); val upright=Mat()
                                        try {
                                            mat.put(0,0,bytes)
                                            when(image.imageInfo.rotationDegrees) {
                                                90 -> Core.rotate(mat,upright,Core.ROTATE_90_CLOCKWISE)
                                                180 -> Core.rotate(mat,upright,Core.ROTATE_180)
                                                270 -> Core.rotate(mat,upright,Core.ROTATE_90_COUNTERCLOCKWISE)
                                                else -> mat.copyTo(upright)
                                            }
                                            val detected=ScanProcessor.detectMat(upright)
                                            val ratio=upright.cols().toFloat()/upright.rows()
                                            main.execute {
                                                if(!disposed) {
                                                    quad=detected; frameRatio=ratio
                                                    val fire=if(latestAuto && !latestBusy && !snapping.get()) gate.update(detected,now) else false
                                                    stability=gate.progress
                                                    if(fire) latestTakePhoto()
                                                }
                                            }
                                        } finally { mat.release(); upright.release() }
                                    }
                                } catch(_: Exception) { main.execute { quad=null } }
                                finally { image.close() }
                            }
                            provider?.unbindAll(); camera=provider?.bindToLifecycle(lifecycle,CameraSelector.DEFAULT_BACK_CAMERA,p,c,a); capture=c
                        } catch(e: Exception) { cameraError="相机不可用：${e.message}，可使用相册导入" }
                    },main)
                    onDispose { disposed=true; provider?.unbindAll(); executor.shutdown(); camera?.cameraControl?.enableTorch(false) }
                }
                Canvas(Modifier.fillMaxSize()) {
                    quad?.let { q ->
                        val width=minOf(size.width,size.height*frameRatio); val height=width/frameRatio
                        val ox=(size.width-width)/2; val oy=(size.height-height)/2
                        val path=Path().apply { q.points.forEachIndexed { i,p -> if(i==0) moveTo(ox+p.x*width,oy+p.y*height) else lineTo(ox+p.x*width,oy+p.y*height) }; close() }
                        drawPath(path,Color(0xFF86E5B8),style=Stroke(3.dp.toPx()))
                        q.points.forEach { drawCircle(Color.White,4.dp.toPx(),Offset(ox+it.x*width,oy+it.y*height)) }
                    }
                }
            } else Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.padding(24.dp)) {
                Text("需要相机权限才能拍照。你也可以从相册导入。",color=Color.White)
                Button(onClick={ request.launch(Manifest.permission.CAMERA) }) { Text("允许使用相机") }
                TextButton(onClick=onImport) { Text("导入图片") }
            }
        }
        cameraError?.let { Text(it,color=Color(0xFFFFC4B7),modifier=Modifier.padding(16.dp)) }
        if(auto) LinearProgressIndicator(progress={ stability },modifier=Modifier.fillMaxWidth())
        Text(if(quad==null) "将纸张放入取景框，拍摄后仍可手动调角" else if(auto) "保持稳定，识别到纸张后自动拍摄" else "已找到纸张 · 点击快门拍摄",color=Color.White,modifier=Modifier.align(Alignment.CenterHorizontally).padding(12.dp))
        Row(Modifier.fillMaxWidth().padding(20.dp),horizontalArrangement=Arrangement.SpaceAround,verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick=onImport,enabled=!busy) { Text("相册",color=Color.White) }
            FilledIconButton(onClick=takePhoto,enabled=capture!=null && !busy,modifier=Modifier.size(76.dp),shape=CircleShape,colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color.White)) { Icon(Icons.Default.CameraAlt,"拍照",tint=Color(0xFF176B54),modifier=Modifier.size(32.dp)) }
            TextButton(onClick={ vm.screen.value="editor" },enabled=pages.isNotEmpty() && !busy) { Text("完成 ${pages.size}",color=Color.White) }
        }
    }
}
