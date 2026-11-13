package cn.paperforge.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Debug
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.FloatBuffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Latency and memory of candidate page models, with PageNetDetector's own session options (2 intra-op threads,
 * full optimisation) on a 1x3x320x320 input. Models are pushed as .onnx files into files/model-bench; the production
 * asset is measured too. Writes files/model-bench/results.json. Skipped when no models were pushed.
 */
@RunWith(AndroidJUnit4::class)
class ModelBenchmarkTest {
    private val app = ApplicationProvider.getApplicationContext<PaperForgeApp>()

    @Test
    fun candidateModelsSpeedAndMemory() {
        val folder = File(app.filesDir, "model-bench")
        val models = folder.listFiles { f -> f.name.endsWith(".onnx") }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no candidate models pushed", models.isNotEmpty())
        val production = app.assets.open("pagenet/pagenet.onnx").use { it.readBytes() }
        val environment = OrtEnvironment.getEnvironment()
        val results = JSONArray()
        val input = FloatArray(3 * 320 * 320) { ((it * 2654435761L) % 255).toFloat() / 255f }
        for ((name, bytes) in listOf("production-pagenet.onnx" to production) + models.map { it.name to it.readBytes() }) {
            System.gc(); Thread.sleep(300)
            val before = Debug.getNativeHeapAllocatedSize()
            val pssBefore = Debug.getPss()
            val loadStart = SystemClock.elapsedRealtimeNanos()
            val session = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                options.setInterOpNumThreads(1)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                environment.createSession(bytes, options)
            }
            val loadMs = (SystemClock.elapsedRealtimeNanos() - loadStart) / 1e6
            val times = ArrayList<Double>()
            session.use { s ->
                repeat(35) { i ->
                    OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 3, 320, 320)).use { tensor ->
                        val t = SystemClock.elapsedRealtimeNanos()
                        s.run(mapOf("image" to tensor)).use { }
                        if (i >= 5) times += (SystemClock.elapsedRealtimeNanos() - t) / 1e6
                    }
                }
                val after = Debug.getNativeHeapAllocatedSize()
                val pssAfter = Debug.getPss()
                times.sort()
                results.put(
                    JSONObject()
                        .put("model", name)
                        .put("bytes", bytes.size)
                        .put("loadMs", loadMs)
                        .put("medianMs", times[times.size / 2])
                        .put("p90Ms", times[(times.size * 9) / 10])
                        .put("nativeHeapDeltaMB", (after - before) / 1048576.0)
                        .put("pssDeltaMB", (pssAfter - pssBefore) / 1024.0),
                )
            }
        }
        File(folder, "results.json").writeText(results.toString(1))
        android.util.Log.i("ModelBenchmark", results.toString())
    }
}
