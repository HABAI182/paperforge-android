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
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.paperforge.app.MainViewModel
import cn.paperforge.app.UserSettings
import cn.paperforge.app.imaging.CameraQualityAnalyzer
import cn.paperforge.app.imaging.LearnedDocumentDetector
import cn.paperforge.app.imaging.ScanConfig
import cn.paperforge.app.model.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.opencv.core.*

@androidx.annotation.OptIn(androidx.camera.view.TransformExperimental::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(vm: MainViewModel, onImport: () -> Unit) {
    val context = LocalContext.current
    val settingsRevision by UserSettings.revision.collectAsState()
    val timerSeconds =
        remember(settingsRevision) { UserSettings.number("captureTimer", 0f).roundToInt() }
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
    var auto by remember { mutableStateOf(UserSettings.bool("autoShutter", true)) }
    var torch by remember { mutableStateOf(false) }
    var quad by remember { mutableStateOf<Quad?>(null) }
    var rawQuad by remember { mutableStateOf<Quad?>(null) }
    var rawAt by remember { mutableLongStateOf(0L) }
    var reference by remember { mutableStateOf<CaptureReference?>(null) }
    var overlay by remember { mutableStateOf<List<PointN>?>(null) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
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
    val gate = remember {
        AutoCaptureGate(
            UserSettings.number("stableMs", 600f).toLong(),
            UserSettings.number("cooldownMs", 1500f).toLong(),
            UserSettings.number("captureArea", 0.08f),
        )
    }
    val outline = remember {
        OutlineTracker(
            UserSettings.number("outlineWeight", 3f),
            UserSettings.number("outlineSwitchFrames", 2f).roundToInt(),
        )
    }
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
    val captureScope = rememberCoroutineScope()
    val latestAuto by rememberUpdatedState(auto)
    val latestBusy by rememberUpdatedState(busy)
    var countdown by remember { mutableIntStateOf(0) }
    var timerJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val captureNow: () -> Unit = {
        val imageCapture = capture
        val doc = vm.selected.value
        if (imageCapture != null && doc != null && !busy && snapping.compareAndSet(false, true)) {
            val snapshot = rawQuad.takeIf { SystemClock.elapsedRealtime() - rawAt < 700 }
            val atShutter = reference?.copy(requestedAt = SystemClock.elapsedRealtime())
            val targetAtShutter = vm.replacement.value
            gate.markCaptured(
                snapshot,
                SystemClock.elapsedRealtime(),
                atShutter?.paper,
                atShutter?.scene,
            )
            captureScope.launch {
                var prepared: cn.paperforge.app.data.LocalTaskRow? = null
                try {
                    val row = vm.prepareCapture(doc.id, atShutter, targetAtShutter)
                    prepared = row
                    imageCapture.takePicture(
                        ImageCapture.OutputFileOptions.Builder(
                                cn.paperforge.app.imaging.CaptureTasks.raw(row)
                            )
                            .build(),
                        main,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                snapping.set(false)
                                vm.captureReady(row)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                snapping.set(false)
                                vm.captureFailed(row, exception.message ?: "拍照失败，请重拍")
                                gate.reset(clearCaptured = true)
                                cameraError = exception.message
                            }
                        },
                    )
                } catch (e: Exception) {
                    prepared?.let { vm.captureFailed(it, e.message ?: "拍照未完成") }
                    snapping.set(false)
                    gate.reset(clearCaptured = true)
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    cameraError = e.message
                }
            }
        }
    }
    val takePhoto: () -> Unit = {
        if (countdown > 0) {
            timerJob?.cancel()
            countdown = 0
        } else if (!busy && !snapping.get()) {
            val seconds = timerSeconds
            if (seconds == 0) captureNow()
            else
                timerJob =
                    captureScope.launch {
                        try {
                            for (remaining in seconds downTo 1) {
                                countdown = remaining
                                delay(1000)
                            }
                            countdown = 0
                            captureNow()
                        } finally {
                            countdown = 0
                        }
                    }
        }
    }
    val latestTakePhoto by rememberUpdatedState(captureNow)
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
                    UserSettings.set("autoShutter", auto)
                    gate.reset(clearCaptured = true)
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
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { vm.openProfiles("capture") }) {
                Text(
                    "档案 · ${cn.paperforge.app.data.SettingsProfiles(context).active()}",
                    color = Color.White,
                )
            }
            TextButton(
                onClick = {
                    val choices = listOf(0f, 3f, 5f, 10f)
                    val value = UserSettings.number("captureTimer", 0f)
                    UserSettings.set(
                        "captureTimer",
                        choices[(choices.indexOf(value) + 1).mod(choices.size)],
                    )
                }
            ) {
                Text(if (timerSeconds == 0) "立即拍摄" else "倒计时 $timerSeconds 秒", color = Color.White)
            }
        }
        Box(
            Modifier.weight(1f)
                .fillMaxWidth()
                .onSizeChanged { previewSize = it }
                .pointerInput(camera) { detectTapGestures { p -> requestFocus(p.x, p.y) } },
            contentAlignment = Alignment.Center,
        ) {
            if (permitted) {
                AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
                if (previewSize.width > 0 && previewSize.height > 0)
                    DisposableEffect(preview, lifecycle) {
                        var disposed = false
                        var provider: ProcessCameraProvider? = null
                        val executor = Executors.newSingleThreadExecutor()
                        val future = ProcessCameraProvider.getInstance(context)
                        val mappingFactory =
                            ImageProxyTransformFactory().apply {
                                isUsingCropRect = true
                                isUsingRotationDegrees = true
                            }
                        future.addListener(
                            {
                                if (!disposed)
                                    try {
                                        provider = future.get()
                                        val p =
                                            Preview.Builder()
                                                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                                                .build()
                                                .also {
                                                    it.surfaceProvider = preview.surfaceProvider
                                                }
                                        val c =
                                            ImageCapture.Builder()
                                                .setJpegQuality(
                                                    UserSettings.number("cameraJpeg", 97f).toInt()
                                                )
                                                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                                                .setCaptureMode(
                                                    ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                                                )
                                                .build()
                                        val selector =
                                            ResolutionSelector.Builder()
                                                .setAspectRatioStrategy(
                                                    AspectRatioStrategy
                                                        .RATIO_4_3_FALLBACK_AUTO_STRATEGY
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
                                                if (
                                                    now - last >
                                                        UserSettings.number("analysisMs", 160f)
                                                            .toLong()
                                                ) {
                                                    last = now
                                                    val plane = image.planes[0]
                                                    val buffer = plane.buffer.duplicate()
                                                    val base = buffer.position()
                                                    val crop = image.cropRect
                                                    val transform =
                                                        mappingFactory.getOutputTransform(image)
                                                    val bytes =
                                                        ByteArray(crop.width() * crop.height() * 4)
                                                    require(plane.pixelStride == 4) {
                                                        "Unexpected RGBA stride"
                                                    }
                                                    for (y in 0 until crop.height()) {
                                                        buffer.position(
                                                            base +
                                                                (crop.top + y) * plane.rowStride +
                                                                crop.left * 4
                                                        )
                                                        buffer.get(
                                                            bytes,
                                                            y * crop.width() * 4,
                                                            crop.width() * 4,
                                                        )
                                                    }
                                                    val mat =
                                                        Mat(
                                                            crop.height(),
                                                            crop.width(),
                                                            CvType.CV_8UC4,
                                                        )
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
                                                            LearnedDocumentDetector.detect(
                                                                upright,
                                                                preview = true,
                                                            )
                                                        val detected = detection.quad
                                                        val quality =
                                                            CameraQualityAnalyzer.analyze(
                                                                upright,
                                                                detected,
                                                            )
                                                        val frameWidth = upright.cols()
                                                        val frameHeight = upright.rows()
                                                        val sensorNs = image.imageInfo.timestamp
                                                        val viewportKey =
                                                            "${image.width}x${image.height}:${crop.left},${crop.top},${crop.right},${crop.bottom}:${image.imageInfo.rotationDegrees}"
                                                        val viewportEpoch =
                                                            image.width.toLong() * 10000 +
                                                                image.height * 10 +
                                                                image.imageInfo.rotationDegrees
                                                        val luminance =
                                                            (quality.sample?.brightness?.times(255)
                                                                    ?: (quality.signature
                                                                        .average() * 255))
                                                                .toFloat()
                                                        main.execute {
                                                            if (
                                                                !disposed &&
                                                                    SystemClock.elapsedRealtime() -
                                                                        now < 500
                                                            ) {
                                                                val previous =
                                                                    reference?.takeIf {
                                                                        it.viewport ==
                                                                            viewportKey &&
                                                                            now - it.analyzedAt <
                                                                                800
                                                                    }
                                                                val motion =
                                                                    if (
                                                                        previous != null &&
                                                                            detected != null
                                                                    )
                                                                        maxOf(
                                                                            CaptureReference
                                                                                .signatureDistance(
                                                                                    previous.scene,
                                                                                    quality
                                                                                        .sceneSignature,
                                                                                ),
                                                                            previous.quad.points
                                                                                .zip(
                                                                                    detected.points
                                                                                )
                                                                                .maxOf { (a, b) ->
                                                                                    hypot(
                                                                                        a.x - b.x,
                                                                                        a.y - b.y,
                                                                                    )
                                                                                },
                                                                        )
                                                                    else 0f
                                                                reference =
                                                                    detected?.let {
                                                                        CaptureReference(
                                                                            it,
                                                                            now,
                                                                            now,
                                                                            frameWidth,
                                                                            frameHeight,
                                                                            viewportKey,
                                                                            sensorNs,
                                                                            detection.confidence,
                                                                            quality.sceneSignature,
                                                                            quality.signature,
                                                                            motion,
                                                                        )
                                                                    }
                                                                rawQuad = detected
                                                                rawAt = now
                                                                quad =
                                                                    outline.update(
                                                                        detected,
                                                                        detection.confidence,
                                                                        quality.signature,
                                                                        now,
                                                                        viewportEpoch,
                                                                    )
                                                                overlay =
                                                                    preview.outputTransform?.let {
                                                                        target ->
                                                                        quad?.let { q ->
                                                                            val points =
                                                                                q.points
                                                                                    .flatMap {
                                                                                        listOf(
                                                                                            it.x *
                                                                                                (frameWidth -
                                                                                                    1),
                                                                                            it.y *
                                                                                                (frameHeight -
                                                                                                    1),
                                                                                        )
                                                                                    }
                                                                                    .toFloatArray()
                                                                            CoordinateTransform(
                                                                                    transform,
                                                                                    target,
                                                                                )
                                                                                .mapPoints(points)
                                                                            points
                                                                                .toList()
                                                                                .chunked(2)
                                                                                .map {
                                                                                    PointN(
                                                                                        it[0],
                                                                                        it[1],
                                                                                    )
                                                                                }
                                                                        }
                                                                    }
                                                                light = luminance
                                                                if (detected != null) {
                                                                    val anchor = focusAnchor
                                                                    if (
                                                                        anchor == null ||
                                                                            anchor.points
                                                                                .zip(
                                                                                    detected.points
                                                                                )
                                                                                .any { (a, b) ->
                                                                                    kotlin.math
                                                                                        .hypot(
                                                                                            a.x -
                                                                                                b.x,
                                                                                            a.y -
                                                                                                b.y,
                                                                                        ) > .1f
                                                                                }
                                                                    ) {
                                                                        focusAnchor = detected
                                                                        val center =
                                                                            previewCenter(
                                                                                detected,
                                                                                frameWidth,
                                                                                frameHeight,
                                                                                transform,
                                                                                preview,
                                                                            )
                                                                        requestFocus(
                                                                            center?.first
                                                                                ?: preview.width /
                                                                                    2f,
                                                                            center?.second
                                                                                ?: preview.height /
                                                                                    2f,
                                                                        )
                                                                    }
                                                                }
                                                                val acceptable =
                                                                    (!UserSettings.bool(
                                                                        "strictQuality",
                                                                        false,
                                                                    ) ||
                                                                        qualityTracker.update(
                                                                            detected,
                                                                            quality.sample,
                                                                            quality.signature,
                                                                            now,
                                                                            focusReady ||
                                                                                (lastFocusAttempt >
                                                                                    0 &&
                                                                                    now -
                                                                                        lastFocusAttempt >=
                                                                                        1200),
                                                                            ScanConfig.float(
                                                                                    "captureSharpnessFloor",
                                                                                    60f,
                                                                                )
                                                                                .toDouble(),
                                                                        )) &&
                                                                        detection.confidence >=
                                                                            UserSettings.number(
                                                                                "captureConfidence",
                                                                                .60f,
                                                                            )
                                                                qualityHint =
                                                                    when {
                                                                        detected == null -> ""
                                                                        quality.sample != null &&
                                                                            quality.sample
                                                                                .sharpness <
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
                                                                            countdown == 0 &&
                                                                            !snapping.get()
                                                                    )
                                                                        gate.update(
                                                                            detected,
                                                                            now,
                                                                            acceptable,
                                                                            quality.signature,
                                                                            quality.sceneSignature,
                                                                            UserSettings.bool(
                                                                                "turnRearm",
                                                                                true,
                                                                            ),
                                                                            UserSettings.number(
                                                                                "turnThreshold",
                                                                                .06f,
                                                                            ),
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
                                                main.execute {
                                                    quad = null
                                                    overlay = null
                                                    rawQuad = null
                                                    reference = null
                                                }
                                            } finally {
                                                image.close()
                                            }
                                        }
                                        provider?.unbindAll()
                                        val group =
                                            UseCaseGroup.Builder()
                                                .addUseCase(p)
                                                .addUseCase(c)
                                                .addUseCase(a)
                                                .setViewPort(requireNotNull(preview.viewPort))
                                                .build()
                                        camera =
                                            provider?.bindToLifecycle(
                                                lifecycle,
                                                CameraSelector.DEFAULT_BACK_CAMERA,
                                                group,
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
                            overlay = null
                            rawQuad = null
                            reference = null
                            qualityTracker.reset()
                            provider?.unbindAll()
                            executor.shutdown()
                            camera?.cameraControl?.enableTorch(false)
                        }
                    }
                if (UserSettings.bool("showOutline", true))
                    Canvas(Modifier.fillMaxSize()) {
                        overlay?.let { points ->
                            val path =
                                Path().apply {
                                    points.forEachIndexed { i, p ->
                                        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                                    }
                                    close()
                                }
                            drawPath(path, Color(0xFF86E5B8), style = Stroke(3.dp.toPx()))
                            points.forEach {
                                drawCircle(Color.White, 4.dp.toPx(), Offset(it.x, it.y))
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
            if (countdown > 0)
                Text(
                    "$countdown",
                    color = Color.White,
                    style = MaterialTheme.typography.displayLarge,
                    modifier = Modifier.align(Alignment.Center),
                )
        }
        cameraError?.let { Text(it, color = Color(0xFFFFC4B7), modifier = Modifier.padding(16.dp)) }
        if (controls)
            camera?.let { cam ->
                ModalBottomSheet(
                    onDismissRequest = { controls = false },
                    containerColor = Color(0xFF1C2924),
                    contentColor = Color.White,
                ) {
                    Column(Modifier.padding(horizontal = 24.dp)) {
                        Text(
                            "相机调节",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                        val zoomState = cam.cameraInfo.zoomState.value
                        if (zoomState != null && zoomState.maxZoomRatio > zoomState.minZoomRatio) {
                            Text("变焦 %.1f×".format(zoom), color = Color.White)
                            Slider(
                                zoom,
                                {
                                    zoom = it
                                    cam.cameraControl.setZoomRatio(it)
                                },
                                valueRange =
                                    zoomState.minZoomRatio..minOf(zoomState.maxZoomRatio, 4f),
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
                        TextButton(
                            onClick = { controls = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("完成调节", color = Color.White)
                        }
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
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(12.dp),
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
                    if (countdown > 0) "取消倒计时" else "拍照",
                    tint = Color(0xFF176B54),
                    modifier = Modifier.size(32.dp),
                )
            }
            Column(Modifier.width(88.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
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

@androidx.annotation.OptIn(androidx.camera.view.TransformExperimental::class)
private fun previewCenter(
    quad: Quad,
    width: Int,
    height: Int,
    source: androidx.camera.view.transform.OutputTransform,
    preview: PreviewView,
): Pair<Float, Float>? {
    val target = preview.outputTransform ?: return null
    val point =
        floatArrayOf(
            quad.points.map { it.x }.average().toFloat() * (width - 1),
            quad.points.map { it.y }.average().toFloat() * (height - 1),
        )
    CoordinateTransform(source, target).mapPoints(point)
    return point[0] to point[1]
}
