@file:Suppress("DEPRECATION", "MissingPermission")
package app.looi.android

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

 data class AudioStatus(val recording: Boolean=false,val pendingMs: Long=0,val playing: Boolean=false,val route: String="",val interrupted: Boolean=false,val recoveries: Int=0)
@SuppressLint("MissingPermission")
class AudioEngine(private val context: Context, private val scope: CoroutineScope, private val report: (String)->Unit) {
    private val manager=context.getSystemService(AudioManager::class.java)
    val status=MutableStateFlow(AudioStatus())
    val failures=java.util.concurrent.atomic.AtomicLong(0)
    val renderedFrames=java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var discardUntilTurn=false
    private val ledger=PcmLedger()
    private val playbackLock=Any()
    private var track: AudioTrack? = null
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var inputJob: Job? = null
    private var outputJob: Job? = null
    private var sink: ((ByteArray)->Unit)? = null
    @Volatile private var interrupted=false
    @Volatile private var flushRequested=false
    @Volatile private var endOfTurn=false
    private var focus: AudioFocusRequest? = null
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focusListener=AudioManager.OnAudioFocusChangeListener { change ->
        scope.launch {
            when(change) {
                AudioManager.AUDIOFOCUS_GAIN -> { interrupted=false; track?.play(); sink?.let { startInput(it) } }
                else -> {
                    interrupted=true; stopInput(); track?.pause()
                    report(t("音频被系统中断，可恢复采集", "System audio interruption; capture can be resumed"))
                }
            }
            status.value=status.value.copy(interrupted=interrupted)
        }
    }
    fun startOutput() {
        if(outputJob?.isActive == true) return
        val result = if(Build.VERSION.SDK_INT >= 26) {
            val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes).setOnAudioFocusChangeListener(focusListener).build()
            focus=request; manager.requestAudioFocus(request)
        } else manager.requestAudioFocus(focusListener,AudioManager.STREAM_VOICE_CALL,AudioManager.AUDIOFOCUS_GAIN)
        check(result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { t("无法获得音频焦点", "Audio focus unavailable") }
        manager.mode=AudioManager.MODE_IN_COMMUNICATION
        if(Build.VERSION.SDK_INT < 31) { if(!manager.isWiredHeadsetOn && !manager.isBluetoothScoOn) manager.isSpeakerphoneOn=true }
        else runCatching {
            val devices=manager.availableCommunicationDevices
            val external=devices.firstOrNull { it.type in listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET,AudioDeviceInfo.TYPE_BLUETOOTH_SCO,AudioDeviceInfo.TYPE_BLE_HEADSET,AudioDeviceInfo.TYPE_USB_HEADSET) }
            (external ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER })?.let(manager::setCommunicationDevice)
        }
        interrupted=false
        outputJob=scope.launch(Dispatchers.IO) {
            var stageRecoveries=0; var lastProgress=android.os.SystemClock.elapsedRealtime(); var started=false
            var headHigh=0L; var previousRaw=0L
            fun rebuild() {
                track?.release(); ledger.recover(); headHigh=0; previousRaw=0; started=false
                val minimum=AudioTrack.getMinBufferSize(24000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0)
                track=AudioTrack.Builder().setAudioAttributes(attributes)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(max(minimum,28800)).setTransferMode(AudioTrack.MODE_STREAM).build()
                check(track?.state == AudioTrack.STATE_INITIALIZED)
                lastProgress=android.os.SystemClock.elapsedRealtime()
            }
            try {
                rebuild()
                while(isActive) {
                    synchronized(playbackLock) { if(flushRequested) { flushRequested=false; track?.pause(); track?.flush(); rebuild() } }
                    if(interrupted) { delay(30); continue }
                    val output=track ?: break
                    val raw=output.playbackHeadPosition.toLong() and 0xffffffffL
                    if(raw < previousRaw && previousRaw-raw > 0x7fffffffL) headHigh += 0x100000000L
                    previousRaw=raw
                    val previousPlayed=ledger.played
                    if(synchronized(playbackLock) { !flushRequested && ledger.progress(ledger.generation,headHigh+raw) }) { renderedFrames.addAndGet(ledger.played-previousPlayed); lastProgress=android.os.SystemClock.elapsedRealtime() }
                    val remaining=ledger.pendingMs
                    if(remaining == 0L) { stageRecoveries=0; endOfTurn=false; lastProgress=android.os.SystemClock.elapsedRealtime() }
                    if(!started && (remaining >= 80 || (endOfTurn && remaining > 0))) { output.play(); started=true; lastProgress=android.os.SystemClock.elapsedRealtime() }
                    if(started) {
                        val framesInTrack=ledger.written-ledger.played
                        if(framesInTrack < 14400) {
                            synchronized(playbackLock) {
                            val bytes=if(flushRequested) ByteArray(0) else ledger.slice(min(9600,((14400-framesInTrack)*2).toInt()))
                            if(bytes.isNotEmpty()) {
                                val wrote=output.write(bytes,0,bytes.size,AudioTrack.WRITE_NON_BLOCKING)
                                if(wrote < 0) { if(stageRecoveries >= 2) throw LooiException("播放器恢复失败", "Playback recovery failed"); stageRecoveries++; rebuild() }
                                else ledger.wrote(wrote)
                            }
                            }
                        }
                        if(remaining > 0 && android.os.SystemClock.elapsedRealtime()-lastProgress > 2000) {
                            if(stageRecoveries < 2) { stageRecoveries++; rebuild() }
                            else { failures.incrementAndGet(); discardUntilTurn=!endOfTurn; ledger.clear(); report(t("播放停滞，本轮音频已清理，可重试", "Playback stalled; this turn was cleared. You can retry.")); rebuild() }
                        }
                    }
                    val route=output.routedDevice?.let { "${it.productName} (${it.type})" } ?: t("系统默认", "System default")
                    status.value=AudioStatus(inputJob?.isActive == true,ledger.pendingMs,ledger.pendingFrames > 0 && started,route,interrupted,stageRecoveries)
                    delay(10)
                }
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { failures.incrementAndGet(); ledger.clear(); report(t("音频输出失败，请恢复采集", "Audio output failed; use Restore capture")) }
            finally { track?.release(); track=null }
        }
    }
    fun enqueue(bytes: ByteArray) = synchronized(playbackLock) {
        if(discardUntilTurn) return@synchronized
        try { ledger.append(bytes) } catch(_: Exception) { failures.incrementAndGet(); discardUntilTurn=!endOfTurn; ledger.clear(); flushRequested=true; report(t("音频缓冲超过上限，本轮已清理", "Audio buffer exceeded its limit; turn cleared")) }
    }
    fun completeTurn() { endOfTurn=true; discardUntilTurn=false }
    fun interrupt() = synchronized(playbackLock) { ledger.clear(); flushRequested=true }
    fun tone() {
        val buffer=ByteBuffer.allocate(24000/3*2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(24000/3) { i -> buffer.putShort((sin(2*PI*660*i/24000)*5000*sin(PI*i/(24000/3))).toInt().toShort()) }
        enqueue(buffer.array()); completeTurn()
    }
    fun startInput(onPcm: (ByteArray)->Unit) {
        sink=onPcm
        if(inputJob?.isActive == true || interrupted) return
        var rate=16000
        fun create(hz: Int): AudioRecord {
            val min=AudioRecord.getMinBufferSize(hz,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0)
            return AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,hz,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,max(min,hz/5*2))
        }
        var device=runCatching { create(rate) }.getOrNull()
        if(device?.state != AudioRecord.STATE_INITIALIZED) { device?.release(); rate=48000; device=create(rate) }
        check(device.state == AudioRecord.STATE_INITIALIZED) { t("麦克风不可用", "Microphone unavailable") }
        record=device
        if(AcousticEchoCanceler.isAvailable()) runCatching { aec=AcousticEchoCanceler.create(device.audioSessionId)?.apply { enabled=true } }
        device.startRecording()
        inputJob=scope.launch(Dispatchers.IO) {
            val buffer=ByteArray(rate/50*2)
            val resampler=Pcm48To16()
            try {
                while(isActive && !interrupted) {
                    val n=device.read(buffer,0,buffer.size,AudioRecord.READ_BLOCKING)
                    if(n < 0) throw LooiException("录音中断", "Recording interrupted")
                    if(n == 0) continue
                    val pcm=if(rate == 16000) buffer.copyOf(n-n%2) else resampler.convert(buffer.copyOf(n-n%2))
                    onPcm(pcm)
                }
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { report(t("录音失败，请恢复采集", "Recording failed; use Restore capture")) }
        }
        status.value=status.value.copy(recording=true)
    }
    private suspend fun stopInput() {
        val job=inputJob; inputJob=null; job?.cancel()
        runCatching { record?.stop() }; job?.join(); aec?.release(); aec=null; record?.release(); record=null
        status.value=status.value.copy(recording=false)
    }
    suspend fun recover() {
        if(outputJob?.isActive != true || interrupted) {
            stopInput(); interrupted=false
            outputJob?.cancelAndJoin(); outputJob=null; startOutput(); sink?.let(::startInput)
        } else if(inputJob?.isActive != true && sink != null) {
            stopInput(); sink?.let(::startInput)
        }
    }
    suspend fun stop() {
        sink=null; stopInput(); outputJob?.cancelAndJoin(); outputJob=null; ledger.clear(); flushRequested=false; endOfTurn=false; discardUntilTurn=false
        if(Build.VERSION.SDK_INT >= 26) focus?.let(manager::abandonAudioFocusRequest) else manager.abandonAudioFocus(focusListener)
        focus=null; manager.mode=AudioManager.MODE_NORMAL
        if(Build.VERSION.SDK_INT >= 31) manager.clearCommunicationDevice()
        status.value=AudioStatus()
    }
    fun pendingMs() = ledger.pendingMs
}
