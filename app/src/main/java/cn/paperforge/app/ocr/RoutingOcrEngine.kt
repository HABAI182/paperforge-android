package cn.paperforge.app.ocr

import android.content.Context
import android.graphics.Bitmap
import cn.paperforge.app.SettingKeys
import cn.paperforge.app.UserSettings
import cn.paperforge.app.data.*
import cn.paperforge.app.imaging.ScanProcessor
import cn.paperforge.app.model.*
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.NativeScheduler
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import kotlin.math.atan2
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The bundled engine remains default. A snapshot pins a model version for a whole operation. */
class RoutingOcrEngine(private val context: Context, val packs: OcrModelPacks) : OcrEngine {
    private val bundled by lazy { BundledOcrEngine() }

    private class Entry(val pack: OcrModelPack, val sdk: PaddleOCR) {
        var references = 0
        var idle = CompletableDeferred<Unit>().apply { complete(Unit) }
    }

    private val lock = Mutex()
    private val runtimes = mutableMapOf<String, Entry>()
    private val removing = mutableSetOf<String>()

    private fun selected() =
        if (UserSettings[SettingKeys.OcrBackend] == "PADDLE")
            packs.find(UserSettings[SettingKeys.OcrPackId])
        else null

    private fun engineId(pack: OcrModelPack) = "paddle:${pack.id}:${pack.fingerprint}:pf1"

    override val id
        get() = selected()?.let(::engineId) ?: BundledOcrEngine.ID

    override val fontFiles
        get() = selected()?.fontFiles.orEmpty()

    override fun snapshot(requestedId: String?): OcrEngine {
        if (requestedId == BundledOcrEngine.ID) return bundled
        val pack =
            if (requestedId == null) selected()
            else packs.installed.value.firstOrNull { engineId(it) == requestedId }
        if (pack == null) {
            check(requestedId == null) { "所需 OCR 模型包不可用，请重新导入，或按当前设置重试任务" }
            return bundled
        }
        return Fixed(pack)
    }

    override suspend fun layout(page: PageRow, chinese: Boolean, options: OcrOptions) =
        snapshot().layout(page, chinese, options)

    override suspend fun recognizeBitmap(source: Bitmap, chinese: Boolean, options: OcrOptions) =
        snapshot().recognizeBitmap(source, chinese, options)

    private inner class Fixed(private val pack: OcrModelPack) : OcrEngine {
        override val id = engineId(pack)
        override val fontFiles
            get() = pack.fontFiles

        override suspend fun layout(
            page: PageRow,
            chinese: Boolean,
            options: OcrOptions,
        ): OcrLayout {
            currentCoroutineContext().ensureActive()
            val bitmap =
                ScanProcessor.render(
                    page.copy(filter = "ORIGINAL", brightness = 0f, contrast = 1f),
                    options.mode.edge,
                )
            return try {
                recognizeBitmap(bitmap, chinese, options).copy(sourceRevision = page.revision)
            } finally {
                bitmap.recycle()
            }
        }

        override suspend fun recognizeBitmap(
            source: Bitmap,
            chinese: Boolean,
            options: OcrOptions,
        ): OcrLayout =
            useRuntime(pack) { sdk, scheduler, loadMs ->
                val result = sdk.recognize(source, scheduler)
                val lines =
                    result.results
                        .filter { it.text.isNotBlank() }
                        .map { item ->
                            val corners =
                                item.box.points.map {
                                    PointN(it.x / source.width, it.y / source.height)
                                }
                            val a = corners[0]
                            val b = corners[1]
                            OcrLine(
                                item.text,
                                corners,
                                item.confidence.takeIf { it.isFinite() && it in 0f..1f },
                                (atan2(b.y - a.y, b.x - a.x) * 180 / Math.PI).toFloat(),
                                variant = "paddle-original",
                            )
                        }
                val layout =
                    OcrLayout(
                        source.width,
                        source.height,
                        lines,
                        id,
                        "${pack.version}|${pack.fingerprint}|sdk.dab3fe3.pf1",
                        recognitionMode = options.mode.name,
                        attempts = listOf("paddle-original"),
                        requestSignature = signature(chinese, options),
                        engineMetrics =
                            mapOf(
                                "detectionMs" to result.detectionTimeMs.toDouble(),
                                "recognitionMs" to result.recognitionTimeMs.toDouble(),
                                "pipelineMs" to result.totalTimeMs.toDouble(),
                                "modelLoadMs" to loadMs.toDouble(),
                            ),
                    )
                OcrReadingOrder.arrange(layout, options.order)
                    .copy(rawRecognition = layout.encode())
            }
    }

    private suspend fun <T> useRuntime(
        pack: OcrModelPack,
        action: suspend (PaddleOCR, NativeScheduler, Long) -> T,
    ): T {
        var acquired: Entry? = null
        var loaded = 0L
        val interactive = currentCoroutineContext()[BackgroundWork.Key] == null
        try {
            lock.withLock {
                check(pack.id !in removing) { "模型正在移除，请使用内置识别或稍后重试" }
                var entry = runtimes[pack.id]
                if (entry == null)
                    RuntimeBudget.ocr.use(interactive) {
                        currentCoroutineContext().ensureActive()
                        withContext(Dispatchers.IO + NonCancellable) {
                            val sdk =
                                PaddleOCR.create(
                                    context,
                                    PaddleOCRConfig(
                                        detLimitSideLen = 1280,
                                        detLimitType = "max",
                                        detMaxSideLimit = 1600,
                                        detMaxCandidates = 1000,
                                        recBatchSize = 1,
                                    ),
                                    EngineConfig(2),
                                    pack.file("det.onnx").path,
                                    pack.file("rec.onnx").path,
                                    pack.file("rec.yml").path,
                                )
                            loaded = sdk.coldLoadTimeMs
                            val created = Entry(pack, sdk)
                            created.references = 1
                            created.idle = CompletableDeferred()
                            runtimes[pack.id] = created
                            entry = created
                            acquired = created
                        }
                    }
                else {
                    val existing = checkNotNull(entry)
                    if (existing.references == 0) existing.idle = CompletableDeferred()
                    existing.references++
                    acquired = existing
                }
            }
            currentCoroutineContext().ensureActive()
            val scheduler =
                object : NativeScheduler {
                    override suspend fun <R> run(action: () -> R): R =
                        RuntimeBudget.ocr.use(interactive) {
                            currentCoroutineContext().ensureActive()
                            withContext(Dispatchers.IO + NonCancellable) { action() }
                        }
                }
            return action(checkNotNull(acquired).sdk, scheduler, loaded)
        } finally {
            withContext(NonCancellable) {
                acquired?.let { entry ->
                    lock.withLock {
                        entry.references--
                        if (entry.references == 0) entry.idle.complete(Unit)
                    }
                }
                trimUnused()
            }
        }
    }

    suspend fun trimUnused() {
        val keep = selected()?.id
        val unused =
            lock.withLock {
                runtimes.values
                    .filter { it.references == 0 && it.pack.id != keep && it.pack.id !in removing }
                    .also { list -> list.forEach { runtimes.remove(it.pack.id) } }
            }
        unused.forEach { it.sdk.release() }
    }

    suspend fun remove(pack: OcrModelPack) =
        withContext(Dispatchers.IO) {
            if (UserSettings[SettingKeys.OcrPackId] == pack.id)
                UserSettings.set(SettingKeys.OcrBackend, "MLKIT")
            val wait =
                lock.withLock {
                    removing += pack.id
                    runtimes[pack.id]?.idle
                }
            try {
                wait?.await()
                val entry = lock.withLock { runtimes.remove(pack.id) }
                withContext(NonCancellable) {
                    entry?.sdk?.release()
                    packs.removeFiles(pack)
                }
            } finally {
                withContext(NonCancellable) { lock.withLock { removing -= pack.id } }
            }
        }
}
