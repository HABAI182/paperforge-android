package cn.paperforge.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.imaging.CameraQualityAnalyzer
import cn.paperforge.app.imaging.DocumentDetector
import cn.paperforge.app.imaging.ScanConfig
import cn.paperforge.app.model.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import org.opencv.core.*

@Composable
fun CameraScreen(vm: MainViewModel, onImport: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var permitted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val request =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            permitted = it
        }
    val pages by vm.pages.collectAsState()
    val busy by vm.busy.collectAsState()
    val prefs = remember { context.getSharedPreferences("camera", 0) }
    var auto by remember { mutableStateOf(prefs.getBoolean("auto", true)) }
    var torch by remember { mutableStateOf(false) }
    var quad by remember { mutableStateOf<Quad?>(null) }
    var frameRatio by remember { mutableFloatStateOf(.75f) }
    var stability by remember { mutableFloatStateOf(0f) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var controls by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var exposure by remember { mutableFloatStateOf(0f) }
    var light by remember { mutableFloatStateOf(128f) }
    val replacement by vm.replacement.collectAsState()
    val preview = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val snapping = remember { AtomicBoolean(false) }
    val gate = remember { AutoCaptureGate(ScanConfig.int("captureStableMs", 1200).toLong()) }
    val outline = remember { OutlineTracker() }
    val qualityTracker = remember { CaptureQualityTracker() }
    var qualityHint by remember { mutableStateOf("") }
    var focusReady by remember { mutableStateOf(false) }
    var focusGeneration by remember { mutableIntStateOf(0) }
    var lastFocusAttempt by remember { mutableLongStateOf(0) }
    var focusAnchor by remember { mutableStateOf<Quad?>(null) }
    val main = remember { ContextCompat.getMainExecutor(context) }
    val requestFocus: (Float, Float) -> Unit = { x, y ->
        camera?.let { cam ->
            val action =
                FocusMeteringAction.Builder(preview.meteringPointFactory.createPoint(x, y))
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()
            lastFocusAttempt = SystemClock.elapsedRealtime()
            focusReady = false
            val generation = ++focusGeneration
            if (!cam.cameraInfo.isFocusMeteringSupported(action)) focusReady = true
            else {
                val future = cam.cameraControl.startFocusAndMetering(action)
                future.addListener(
                    {
                        if (generation == focusGeneration)
                            focusReady =
                                runCatching { future.get().isFocusSuccessful }.getOrDefault(false)
                    },
                    main,
                )
            }
        }
    }
    val latestAuto by rememberUpdatedState(auto)
    val latestBusy by rememberUpdatedState(busy)
    val takePhoto: () -> Unit = {
        val imageCapture = capture
        val doc = vm.selected.value
        if (imageCapture != null && doc != null && !busy && snapping.compareAndSet(false, true)) {
            val file = vm.repo.captureFile(doc.id)
            val snapshot = quad
            imageCapture.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                main,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        snapping.set(false)
                        vm.captured(file, snapshot)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        snapping.set(false)
                        file.delete()
                        cameraError = exception.message
                    }
                },
            )
        }
    }
    val latestTakePhoto by rememberUpdatedState(takePhoto)
    LaunchedEffect(Unit) { if (!permitted) request.launch(Manifest.permission.CAMERA) }
    Column(Modifier.fillMaxSize().background(Color(0xFF121B18)).systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    vm.replacement.value = null
                    vm.screen.value = if (pages.isEmpty()) "home" else "editor"
                }
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
            }
            Text(
                if (replacement == null) "扫描文档" else "重拍本页",
                color = Color.White,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
            )
            FilterChip(
                selected = auto,
                onClick = {
                    auto = !auto
                    prefs.edit().putBoolean("auto", auto).apply()
                    gate.reset()
                },
                label = { Text(if (auto) "自动拍摄" else "手动拍摄", color = Color.White) },
            )
            IconButton(onClick = { controls = !controls }) {
                Icon(Icons.Default.Tune, "变焦与曝光", tint = Color.White)
            }
            IconButton(
                enabled = camera?.cameraInfo?.hasFlashUnit() == true,
                onClick = {
                    torch = !torch
                    camera?.cameraControl?.enableTorch(torch)
                },
            ) {
                Icon(
                    if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    "闪光灯",
                    tint = Color.White,
                )
            }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().pointerInput(camera) {
                detectTapGestures { p -> requestFocus(p.x, p.y) }
            },
            contentAlignment = Alignment.Center,
        ) {
            if (permitted) {
                AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
                DisposableEffect(preview, lifecycle) {
                    var disposed = false
                    var provider: ProcessCameraProvider? = null
                    val executor = Executors.newSingleThreadExecutor()
                    val future = ProcessCameraProvider.getInstance(context)
                    future.addListener(
                        {
                            if (!disposed)
                                try {
                                    provider = future.get()
                                    val p =
                                        Preview.Builder()
                                            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                                            .build()
                                            .also { it.surfaceProvider = preview.surfaceProvider }
                                    val c =
                                        ImageCapture.Builder()
                                            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                                            .setCaptureMode(
                                                ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                                            )
                                            .build()
                                    val selector =
                                        ResolutionSelector.Builder()
                                            .setAspectRatioStrategy(
                                                AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                                            )
                                            .setResolutionStrategy(
                                                ResolutionStrategy(
                                                    android.util.Size(1280, 960),
                                                    ResolutionStrategy
                                                        .FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                                )
                                            )
                                            .build()
                                    val a =
                                        ImageAnalysis.Builder()
                                            .setResolutionSelector(selector)
                                            .setOutputImageFormat(
                                                ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                                            )
                                            .setBackpressureStrategy(
                                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                                            )
                                            .build()
                                    var last = 0L
                                    a.setAnalyzer(executor) { image ->
                                        try {
                                            val now = SystemClock.elapsedRealtime()
                                            if (now - last > 180) {
                                                last = now
                                                val plane = image.planes[0]
                                                val buffer = plane.buffer.duplicate()
                                                val base = buffer.position()
                                                val bytes =
                                                    ByteArray(image.width * image.height * 4)
                                                require(plane.pixelStride == 4) {
                                                    "Unexpected RGBA stride"
                                                }
                                                for (y in 0 until image.height) {
                                                    buffer.position(base + y * plane.rowStride)
                                                    buffer.get(
                                                        bytes,
                                                        y * image.width * 4,
                                                        image.width * 4,
                                                    )
                                                }
                                                val mat =
                                                    Mat(image.height, image.width, CvType.CV_8UC4)
                                                val upright = Mat()
                                                try {
                                                    mat.put(0, 0, bytes)
                                                    when (image.imageInfo.rotationDegrees) {
                                                        90 ->
                                                            Core.rotate(
                                                                mat,
                                                                upright,
                                                                Core.ROTATE_90_CLOCKWISE,
                                                            )
                                                        180 ->
                                                            Core.rotate(
                                                                mat,
                                                                upright,
                                                                Core.ROTATE_180,
                                                            )
                                                        270 ->
                                                            Core.rotate(
                                                                mat,
                                                                upright,
                                                                Core.ROTATE_90_COUNTERCLOCKWISE,
                                                            )
                                                        else -> mat.copyTo(upright)
                                                    }
                                                    val detection =
                                                        DocumentDetector.detect(
                                                            upright,
                                                            preview = true,
                                                        )
                                                    val detected = detection.quad
                                                    val quality =
                                                        CameraQualityAnalyzer.analyze(
                                                            upright,
                                                            detected,
                                                        )
                                                    val ratio =
                                                        upright.cols().toFloat() / upright.rows()
                                                    val viewportEpoch =
                                                        image.width.toLong() * 10000 +
                                                            image.height * 10 +
                                                            image.imageInfo.rotationDegrees
                                                    val luminance =
                                                        (quality.sample?.brightness?.times(255)
                                                                ?: (quality.signature.average() *
                                                                    255))
                                                            .toFloat()
                                                    main.execute {
                                                        if (!disposed) {
                                                            quad =
                                                                outline.update(
                                                                    detected,
                                                                    detection.confidence,
                                                                    quality.signature,
                                                                    now,
                                                                    viewportEpoch,
                                                                )
                                                            frameRatio = ratio
                                                            light = luminance
                                                            if (detected != null) {
                                                                val anchor = focusAnchor
                                                                if (
                                                                    anchor == null ||
                                                                        anchor.points
                                                                            .zip(detected.points)
                                                                            .any { (a, b) ->
                                                                                kotlin.math.hypot(
                                                                                    a.x - b.x,
                                                                                    a.y - b.y,
                                                                                ) > .1f
                                                                            }
                                                                ) {
                                                                    focusReady = false
                                                                    focusAnchor = detected
                                                                }
                                                                if (
                                                                    !focusReady &&
                                                                        now - lastFocusAttempt >
                                                                            2500
                                                                )
                                                                    requestFocus(
                                                                        preview.width / 2f,
                                                                        preview.height / 2f,
                                                                    )
                                                            }
                                                            val acceptable =
                                                                qualityTracker.update(
                                                                    detected,
                                                                    quality.sample,
                                                                    quality.signature,
                                                                    now,
                                                                    focusReady ||
                                                                        (lastFocusAttempt > 0 &&
                                                                            now -
                                                                                lastFocusAttempt >=
                                                                                1200),
                                                                    ScanConfig.float(
                                                                            "captureSharpnessFloor",
                                                                            60f,
                                                                        )
                                                                        .toDouble(),
                                                                ) &&
                                                                    detection.confidence >=
                                                                        ScanConfig.float(
                                                                            "confidenceCapture",
                                                                            .72f,
                                                                        )
                                                            qualityHint =
                                                                when {
                                                                    detected == null -> ""
                                                                    quality.sample != null &&
                                                                        quality.sample.sharpness <
                                                                            ScanConfig.float(
                                                                                "captureSharpnessFloor",
                                                                                60f,
                                                                            ) -> "点击文字对焦"
                                                                    quality.sample != null &&
                                                                        quality.sample
                                                                            .glareFraction in
                                                                            0.03..0.45 ->
                                                                        "调整角度可减少反光"
                                                                    else -> ""
                                                                }
                                                            val fire =
                                                                if (
                                                                    latestAuto &&
                                                                        !latestBusy &&
                                                                        !snapping.get()
                                                                )
                                                                    gate.update(
                                                                        detected,
                                                                        now,
                                                                        acceptable,
                                                                    )
                                                                else false
                                                            stability = gate.progress
                                                            if (fire) latestTakePhoto()
                                                        }
                                                    }
                                                } finally {
                                                    mat.release()
                                                    upright.release()
                                                }
                                            }
                                        } catch (_: Exception) {
                                            main.execute { quad = null }
                                        } finally {
                                            image.close()
                                        }
                                    }
                                    provider?.unbindAll()
                                    camera =
                                        provider?.bindToLifecycle(
                                            lifecycle,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            p,
                                            c,
                                            a,
                                        )
                                    capture = c
                                } catch (e: Exception) {
                                    cameraError = "相机不可用：${e.message}，可使用相册导入"
                                }
                        },
                        main,
                    )
                    onDispose {
                        disposed = true
                        focusGeneration++
                        focusReady = false
                        outline.reset()
                        qualityTracker.reset()
                        provider?.unbindAll()
                        executor.shutdown()
                        camera?.cameraControl?.enableTorch(false)
                    }
                }
                Canvas(Modifier.fillMaxSize()) {
                    quad?.let { q ->
                        val width = minOf(size.width, size.height * frameRatio)
                        val height = width / frameRatio
                        val ox = (size.width - width) / 2
                        val oy = (size.height - height) / 2
                        val path =
                            Path().apply {
                                q.points.forEachIndexed { i, p ->
                                    if (i == 0) moveTo(ox + p.x * width, oy + p.y * height)
                                    else lineTo(ox + p.x * width, oy + p.y * height)
                                }
                                close()
                            }
                        drawPath(path, Color(0xFF86E5B8), style = Stroke(3.dp.toPx()))
                        q.points.forEach {
                            drawCircle(
                                Color.White,
                                4.dp.toPx(),
                                Offset(ox + it.x * width, oy + it.y * height),
                            )
                        }
                    }
                }
            } else
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text("需要相机权限才能拍照。你也可以从相册导入。", color = Color.White)
                    Button(onClick = { request.launch(Manifest.permission.CAMERA) }) {
                        Text("允许使用相机")
                    }
                    TextButton(onClick = onImport) { Text("导入图片") }
                }
        }
        cameraError?.let { Text(it, color = Color(0xFFFFC4B7), modifier = Modifier.padding(16.dp)) }
        if (controls)
            camera?.let { cam ->
                Column(Modifier.padding(horizontal = 24.dp)) {
                    val zoomState = cam.cameraInfo.zoomState.value
                    if (zoomState != null && zoomState.maxZoomRatio > zoomState.minZoomRatio) {
                        Text("变焦 %.1f×".format(zoom), color = Color.White)
                        Slider(
                            zoom,
                            {
                                zoom = it
                                cam.cameraControl.setZoomRatio(it)
                            },
                            valueRange = zoomState.minZoomRatio..minOf(zoomState.maxZoomRatio, 4f),
                        )
                    }
                    val e = cam.cameraInfo.exposureState
                    if (e.isExposureCompensationSupported) {
                        Text("曝光 ${exposure.roundToInt()}", color = Color.White)
                        Slider(
                            exposure,
                            {
                                exposure = it
                                cam.cameraControl.setExposureCompensationIndex(it.roundToInt())
                            },
                            valueRange =
                                e.exposureCompensationRange.lower.toFloat()..e
                                        .exposureCompensationRange
                                        .upper
                                        .toFloat(),
                        )
                    }
                }
            }
        if (auto)
            LinearProgressIndicator(progress = { stability }, modifier = Modifier.fillMaxWidth())
        Text(
            if (qualityHint.isNotBlank()) qualityHint
            else if (light < 65) "光线偏暗，建议补光 · 点击画面对焦"
            else if (quad == null) "对准纸张 · 随时可拍" else if (auto) "保持稳定，自动拍摄" else "拍摄后自动裁边",
            color = Color.White,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(12.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onImport, enabled = !busy) { Text("相册", color = Color.White) }
            FilledIconButton(
                onClick = takePhoto,
                enabled = capture != null && !busy,
                modifier = Modifier.size(76.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White),
            ) {
                Icon(
                    Icons.Default.CameraAlt,
                    "拍照",
                    tint = Color(0xFF176B54),
                    modifier = Modifier.size(32.dp),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                pages.lastOrNull()?.let { page ->
                    val thumb = pageBitmap(page, 140)
                    thumb?.let {
                        Image(
                            it.asImageBitmap(),
                            "最近拍摄页面",
                            Modifier.size(38.dp).clickable {
                                vm.replacement.value = null
                                vm.screen.value = "editor"
                            },
                        )
                    }
                }
                TextButton(
                    onClick = {
                        vm.replacement.value = null
                        vm.screen.value = "editor"
                    },
                    enabled = pages.isNotEmpty() && !busy,
                ) {
                    Text("完成 ${pages.size}", color = Color.White)
                }
            }
        }
    }
}
