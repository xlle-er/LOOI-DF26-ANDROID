package app.looi.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraEngine(private val context: Context, private val scope: CoroutineScope, private val report: (String)->Unit) {
    val running=MutableStateFlow(false)
    val detections=MutableStateFlow<List<Detection>>(emptyList())
    val frameAspect=MutableStateFlow(4f/3f)
    val detectorReady=MutableStateFlow(false)
    var onFrame: (ByteArray)->Unit = {}
    var onDetections: (List<Detection>)->Unit = {}
    var onInvalidated: ()->Unit = {}
    var onDetectorUnavailable: ()->Unit = {}
    var front=true; private set
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var rotation=android.view.Surface.ROTATION_0
    private var view: PreviewView? = null
    private val executor=Executors.newSingleThreadExecutor()
    private var detector: ObjectDetector? = null
    @Volatile private var inputGeometry=Letterbox(320f,320f)
    private val busy=AtomicBoolean(false)
    private var lastDetection=0L
    private var lastUpload=0L
    private var ids=0L
    private var generation=0L
    @Volatile private var detectorGeneration=0L
    @Volatile private var latestJpeg: ByteArray? = null
    @Volatile var detectEnabled=false
    fun setRotation(value: Int) { rotation=value; preview?.targetRotation=value; analysisUseCase?.targetRotation=value }
    fun attach(value: PreviewView) {
        view=value; value.scaleType=PreviewView.ScaleType.FIT_CENTER
        value.implementationMode=PreviewView.ImplementationMode.COMPATIBLE
        preview?.setSurfaceProvider(value.surfaceProvider)
    }
    fun detach(value: PreviewView) { if(view === value) { preview?.setSurfaceProvider(null); view=null } }
    fun start() {
        if(running.value) return
        val token=++generation
        val future=ProcessCameraProvider.getInstance(context)
        future.addListener({
            if(token != generation) return@addListener
            try {
                val p=future.get(); provider=p; p.unbindAll()
                val selector=if(front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                check(p.hasCamera(selector)) { t("此镜头不可用", "This camera is unavailable") }
                val pv=Preview.Builder().setTargetRotation(rotation).build(); preview=pv; view?.let { pv.setSurfaceProvider(it.surfaceProvider) }
                val analysis=ImageAnalysis.Builder().setTargetRotation(rotation).setTargetResolution(android.util.Size(640,480)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysisUseCase=analysis
                analysis.setAnalyzer(executor) { image ->
                    try { if(token == generation) analyze(image,token) } catch(_: Exception) { /* A bad frame must not stall subsequent frames. */ }
                    finally { image.close() }
                }
                val bound=p.bindToLifecycle(ProcessLifecycleOwner.get(),selector,pv,analysis)
                bound.cameraInfo.cameraState.observe(ProcessLifecycleOwner.get()) { status ->
                    if(token == generation && status.error != null) {
                        running.value=false; onInvalidated(); report(t("相机中断，可恢复采集", "Camera interrupted; use Restore capture"))
                    }
                }
                running.value=true
            } catch(_: Exception) { running.value=false; onInvalidated(); report(t("相机不可用，请检查权限或其他应用占用", "Camera unavailable; check permission and other apps")) }
        },ContextCompat.getMainExecutor(context))
    }
    private fun initDetector(): ObjectDetector? {
        detector?.let { return it }
        if(unsupportedVectorEmulator()) {
            scope.launch { detectEnabled=false; detectorReady.value=false; onInvalidated(); onDetectorUnavailable(); report(t("此 ARM 模拟器错误报告了 SVE/SME 指令集，本地识别需在兼容真机验证", "This ARM emulator reports unsupported SVE/SME instructions. Validate local detection on a compatible device.")) }
            return null
        }
        try {
            val options=ObjectDetector.ObjectDetectorOptions.builder().setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build())
                .setRunningMode(RunningMode.LIVE_STREAM).setScoreThreshold(.6f).setMaxResults(12)
                .setResultListener { result, input ->
                    try {
                        val geometry=inputGeometry; val token=detectorGeneration
                        val list=result.detections().map { d ->
                            val b=d.boundingBox(); val c=d.categories().maxByOrNull { it.score() }
                            Detection(++ids,c?.categoryName() ?: "object",c?.score() ?: 0f,geometry.normalized(b.left,b.top,b.right,b.bottom),result.timestampMs())
                        }
                        scope.launch { if(token == generation && detectEnabled) { detections.value=list; onDetections(list) } }
                    } finally { input.close(); busy.set(false) }
                }
                .setErrorListener { busy.set(false); scope.launch { onInvalidated(); report(t("本地识别失败，可关闭后重试", "Local detection failed; toggle it to retry")) } }.build()
            detector=ObjectDetector.createFromOptions(context,options); detectorReady.value=true
        } catch(_: Exception) { scope.launch { detectEnabled=false; detectorReady.value=false; onInvalidated(); report(t("本地识别模型无法加载", "Could not load the local detection model")) } }
        return detector
    }
    private fun analyze(image: ImageProxy, token: Long) {
        val now=SystemClock.elapsedRealtime()
        val wantsDetection=detectEnabled && now-lastDetection >= 100 && !busy.get()
        val wantsJpeg=now-lastUpload >= 1000
        if(!wantsDetection && !wantsJpeg) return
        val original=image.toBitmap()
        val rotation=image.imageInfo.rotationDegrees
        val upright=if(rotation == 0) original else Bitmap.createBitmap(original,0,0,original.width,original.height,Matrix().apply { postRotate(rotation.toFloat()) },true).also { original.recycle() }
        frameAspect.value=upright.width.toFloat()/upright.height
        if(wantsJpeg) {
            lastUpload=now
            val jpeg=ByteArrayOutputStream().also { upright.compress(Bitmap.CompressFormat.JPEG,60,it) }.toByteArray()
            latestJpeg=jpeg; scope.launch { if(token == generation && running.value) onFrame(jpeg) }
        }
        if(wantsDetection) {
            val engine=initDetector()
            if(engine != null && busy.compareAndSet(false,true)) {
                lastDetection=now; detectorGeneration=token
                // CPU RGB input avoids native Bitmap swizzle paths and keeps preprocessing explicit.
                val geometry=Letterbox(upright.width.toFloat(),upright.height.toFloat()); inputGeometry=geometry
                val square=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(square).drawBitmap(upright,null,android.graphics.RectF(geometry.offsetX,geometry.offsetY,geometry.offsetX+geometry.scaledWidth,geometry.offsetY+geometry.scaledHeight),android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                val pixels=IntArray(320*320); square.getPixels(pixels,0,320,0,0,320,320)
                val rgb=java.nio.ByteBuffer.allocateDirect(pixels.size*3)
                for(pixel in pixels) { rgb.put((pixel shr 16).toByte()); rgb.put((pixel shr 8).toByte()); rgb.put(pixel.toByte()) }; rgb.rewind()
                square.recycle(); upright.recycle()
                val input=com.google.mediapipe.framework.image.ByteBufferImageBuilder(rgb,320,320,com.google.mediapipe.framework.image.MPImage.IMAGE_FORMAT_RGB).build()
                try { engine.detectAsync(input,now) } catch(_: Exception) { input.close(); busy.set(false) }
                return
            }
        }
        upright.recycle()
    }
    fun snapshot(): ByteArray = latestJpeg?.copyOf() ?: throw LooiException("尚未取得相机画面", "No camera frame available yet")
    fun stop() {
        generation++; provider?.unbindAll(); preview=null; analysisUseCase=null; latestJpeg=null; running.value=false; detections.value=emptyList(); onInvalidated()
    }
    fun flip() { val resume=running.value; stop(); front=!front; if(resume) start() }
}
fun objectName(label: String): String {
    if(!L10n.chinese) return label
    val english="person,bicycle,car,motorcycle,airplane,bus,train,truck,boat,traffic light,fire hydrant,stop sign,parking meter,bench,bird,cat,dog,horse,sheep,cow,elephant,bear,zebra,giraffe,backpack,umbrella,handbag,tie,suitcase,frisbee,skis,snowboard,sports ball,kite,baseball bat,baseball glove,skateboard,surfboard,tennis racket,bottle,wine glass,cup,fork,knife,spoon,bowl,banana,apple,sandwich,orange,broccoli,carrot,hot dog,pizza,donut,cake,chair,couch,potted plant,bed,dining table,toilet,tv,laptop,mouse,remote,keyboard,cell phone,microwave,oven,toaster,sink,refrigerator,book,clock,vase,scissors,teddy bear,hair drier,toothbrush".split(',')
    val chinese="人,自行车,汽车,摩托车,飞机,公交车,火车,卡车,船,红绿灯,消防栓,停止标志,停车计时器,长椅,鸟,猫,狗,马,羊,牛,大象,熊,斑马,长颈鹿,背包,雨伞,手提包,领带,行李箱,飞盘,滑雪板,单板滑雪板,球,风筝,棒球棒,棒球手套,滑板,冲浪板,网球拍,瓶子,酒杯,杯子,叉子,刀,勺子,碗,香蕉,苹果,三明治,橙子,西兰花,胡萝卜,热狗,披萨,甜甜圈,蛋糕,椅子,沙发,盆栽,床,餐桌,马桶,电视,笔记本电脑,鼠标,遥控器,键盘,手机,微波炉,烤箱,烤面包机,水槽,冰箱,书,时钟,花瓶,剪刀,泰迪熊,吹风机,牙刷".split(',')
    val index=english.indexOf(label); return if(index >= 0) chinese[index] else label
}

/** Known invalid HWCAP combinations in Apple Silicon ARM emulators; never disable real devices. */
fun unsupportedVectorEmulator(): Boolean {
    if(android.os.Build.HARDWARE != "ranchu" || "arm64-v8a" !in android.os.Build.SUPPORTED_ABIS) return false
    val cpu=runCatching { java.io.File("/proc/cpuinfo").readText() }.getOrDefault("")
    val features=cpu.lineSequence().firstOrNull { it.startsWith("Features") }?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.toSet() ?: return false
    return ("sve2" in features && "sve" !in features) || ("sme2" in features && "sme" !in features)
}
