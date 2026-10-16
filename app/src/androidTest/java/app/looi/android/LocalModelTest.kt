package app.looi.android

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.framework.image.BitmapImageBuilder
import org.junit.Test
import org.junit.Assert.*

class LocalModelTest {
    @Test fun bundledModelRunsOnCpuWithoutNetwork() {
        org.junit.Assume.assumeFalse("Blocked: emulator HWCAP advertises unsupported SVE/SME (MediaPipe issue 6293)",unsupportedVectorEmulator())
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val detector=ObjectDetector.createFromOptions(context,ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build()).setRunningMode(RunningMode.IMAGE).setScoreThreshold(.6f).build())
        val bitmap=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888)
        val input=com.google.mediapipe.framework.image.ByteBufferImageBuilder(java.nio.ByteBuffer.allocateDirect(320*320*3),320,320,com.google.mediapipe.framework.image.MPImage.IMAGE_FORMAT_RGB).build()
        try { assertTrue(detector.detect(input).detections().isEmpty()) } finally { input.close();bitmap.recycle();detector.close() }
    }
}
