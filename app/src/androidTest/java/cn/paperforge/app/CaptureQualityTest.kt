package cn.paperforge.app

import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.graphics.ImageFormat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The photo the camera screen takes, as configured there: the largest 4:3 size the back camera offers, maximum-
 * quality mode, JPEG quality from the setting (97 by default), saved straight to the file with no re-encoding.
 * Also asks CameraX Extensions what the phone offers. Writes files/phone-out/capture-quality/capture.json.
 */
@RunWith(AndroidJUnit4::class)
class CaptureQualityTest {
    private val context = ApplicationProvider.getApplicationContext<PaperForgeApp>()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun theCaptureIsTheLargestFourByThreeJpeg() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // The camera screen asks for this; a test has to grant it.
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.CAMERA)
        val provider = ProcessCameraProvider.getInstance(context).get(10, TimeUnit.SECONDS)
        val owner = Owner()
        val quality = UserSettings[SettingKeys.CameraJpeg].toInt()
        val capture =
            ImageCapture.Builder()
                .setJpegQuality(quality)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .build()
                )
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
        val extensions = ExtensionsManager.getInstanceAsync(context, provider).get(10, TimeUnit.SECONDS)
        val modes =
            mapOf("AUTO" to ExtensionMode.AUTO, "HDR" to ExtensionMode.HDR, "NIGHT" to ExtensionMode.NIGHT)
                .filterValues { extensions.isExtensionAvailable(CameraSelector.DEFAULT_BACK_CAMERA, it) }
                .keys
        instrumentation.runOnMainSync {
            owner.registry.currentState = Lifecycle.State.RESUMED
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, capture)
        }
        val root = File(context.filesDir, "phone-out/capture-quality").apply { mkdirs() }
        val file = File(root, "capture.jpg")
        val done = CountDownLatch(1)
        var error: ImageCaptureException? = null
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            java.util.concurrent.Executors.newSingleThreadExecutor(),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) = done.countDown()

                override fun onError(exception: ImageCaptureException) {
                    error = exception
                    done.countDown()
                }
            },
        )
        assertTrue("capture timed out", done.await(30, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { provider.unbindAll() }
        error?.let { throw it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        // The largest JPEG size the back camera reports, in 4:3 where it has one.
        val manager = context.getSystemService(CameraManager::class.java)
        val back =
            manager.cameraIdList.first {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        val sizes =
            manager.getCameraCharacteristics(back).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                .getOutputSizes(ImageFormat.JPEG)
        val fourThree = sizes.filter { it.width * 3 == it.height * 4 || it.height * 3 == it.width * 4 }
        val largest = (fourThree.ifEmpty { sizes.toList() }).maxBy { it.width.toLong() * it.height }
        File(root, "capture.json").writeText(
            JSONObject()
                .put("width", bounds.outWidth)
                .put("height", bounds.outHeight)
                .put("bytes", file.length())
                .put("mime", bounds.outMimeType)
                .put("jpegQuality", quality)
                .put("largest43", "${largest.width}x${largest.height}")
                .put("extensions", modes.joinToString(","))
                .toString(1)
        )
        assertEquals("image/jpeg", bounds.outMimeType)
        assertEquals("the 94 an old migration stored is cleared; captures save at 97", 97, quality)
        assertEquals(
            "the largest 4:3 size the camera offers",
            largest.width.toLong() * largest.height,
            bounds.outWidth.toLong() * bounds.outHeight,
        )
    }
}
