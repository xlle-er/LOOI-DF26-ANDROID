@file:Suppress("DEPRECATION", "MissingPermission")
package app.looi.android

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

 data class RobotRow(val address: String, val name: String, val rssi: Int, val alias: String = "")
@SuppressLint("MissingPermission")
class RobotClient(private val context: Context, private val scope: CoroutineScope, private val report: (String)->Unit): RobotTransport {
    val wifi = DeviceWifiClient(context, scope)
    val management by lazy { DeviceSession(context,this,scope) }
    init { wifi.onLost = { if(gatt==null || writeChar==null) { ready.value=false; status.value="disconnected"; onDisconnect() } } }
    suspend fun connectWifi(address: String,password: String) {
        if(ready.value) runCatching { send(JSONObject().put("type","stop")) }
        disconnect(); stopScan(); wifi.connect(address,password)
        this.address=wifi.info.value.optString("device_id"); ready.value=true; status.value="ready"
    }
    suspend fun manage(payload: JSONObject,password: String): JSONObject {
        val packet=JSONObject(payload.toString()).put("admin_password",password)
        return if(gatt!=null&&writeChar!=null) sendInternal(packet) else send(packet)
    }
    suspend fun deviceStatus():JSONObject = if(gatt!=null&&writeChar!=null)sendInternal(JSONObject().put("type","device_status")) else send(JSONObject().put("type","device_status"))
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    val robots = MutableStateFlow<List<RobotRow>>(emptyList())
    val status = MutableStateFlow("")
    override val ready = MutableStateFlow(false)
    val scanning = MutableStateFlow(false)
    var onDisconnect: ()->Unit = {}
    private var scanJob: Job? = null
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var connected = CompletableDeferred<Unit>()
    private var discovered = CompletableDeferred<Unit>()
    private var subscribed = CompletableDeferred<Unit>()
    private var mtuReady = CompletableDeferred<Int>()
    private var writeDone: CompletableDeferred<Unit>? = null
    private val acknowledgements = ConcurrentHashMap<String,CompletableDeferred<JSONObject>>()
    private val decoder = NotificationDecoder()
    private val transport = Mutex()
    private val commands = Mutex()
    private var activeCommand: Job? = null
    private val epoch = AtomicLong(0)
    private var payloadSize = 20
    private var scanCallback: ScanCallback? = null
    var address: String? = null; private set
    private val count = java.util.concurrent.atomic.AtomicInteger(0)

    fun scan() {
        stopScan()
        val bt = adapter
        if (bt == null || !bt.isEnabled) { report(t("请先开启蓝牙", "Turn on Bluetooth first")); return }
        val scanner = bt.bluetoothLeScanner ?: return
        val callback = object: ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val addr = result.device.address; val label = result.scanRecord?.deviceName ?: result.device.name ?: "LOOI"
                robots.update { rows ->
                    val old = rows.find { it.address == addr }
                    if (old == null) rows + RobotRow(addr,label,result.rssi)
                    else rows.map { if(it.address == addr) it.copy(name=label,rssi=(it.rssi*.75+result.rssi*.25).toInt()) else it }
                }
            }
            override fun onScanFailed(errorCode: Int) { scanning.value=false; report(t("蓝牙扫描失败", "Bluetooth scan failed")+" ($errorCode)") }
        }
        scanCallback = callback
        try {
            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(BodyProtocol.SERVICE))).build()),ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),callback)
            scanning.value=true
            scanJob=scope.launch { delay(15000); stopScan() }
        } catch (_: SecurityException) { report(t("需要附近设备权限", "Nearby devices permission is required")) }
    }
    fun stopScan() {
        scanJob?.cancel(); scanJob=null
        try { scanCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) } } catch (_: SecurityException) { }
        scanCallback=null; scanning.value=false
    }
    private fun failure() = LooiException("机器人连接已断开", "Robot disconnected")
    private val callback = object: BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, code: Int, state: Int) {
            if(g !== gatt) return
            if(code == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) connected.complete(Unit)
            else if(state == BluetoothProfile.STATE_DISCONNECTED || code != BluetoothGatt.GATT_SUCCESS) scope.launch { disconnect() }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, code: Int) {
            if(g !== gatt) return
            if(code == 0) discovered.complete(Unit) else discovered.completeExceptionally(failure())
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, code: Int) { if(g === gatt) mtuReady.complete(if(code == 0) mtu else 23) }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, code: Int) {
            if(g !== gatt) return
            if(code == 0) subscribed.complete(Unit) else subscribed.completeExceptionally(failure())
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, code: Int) {
            if(g !== gatt) return
            if(code == 0) writeDone?.complete(Unit) else writeDone?.completeExceptionally(failure())
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { if(g === gatt) notification(value) }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { if(android.os.Build.VERSION.SDK_INT < 33 && g === gatt) notification(c.value.copyOf()) }
    }
    private fun notification(bytes: ByteArray) {
        scope.launch {
            try { decoder.append(bytes).forEach { p -> acknowledgements.remove(p.optString("id"))?.complete(p) } }
            catch (_: Exception) { report(t("机器人通知异常", "Invalid robot notification")); disconnect() }
        }
    }
    suspend fun connect(row: RobotRow) {
        if(ready.value) runCatching { send(JSONObject().put("type","stop")) }
        disconnect(); stopScan()
        val bt = adapter ?: throw failure()
        connected=CompletableDeferred(); discovered=CompletableDeferred(); subscribed=CompletableDeferred(); mtuReady=CompletableDeferred()
        status.value="connecting"
        try {
            withTimeout(12000) {
                address=row.address
                gatt=bt.getRemoteDevice(row.address).connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE)
                connected.await()
                check(gatt?.discoverServices() == true); discovered.await()
                val service = gatt?.getService(UUID.fromString(BodyProtocol.SERVICE)) ?: throw failure()
                writeChar=service.getCharacteristic(UUID.fromString(BodyProtocol.WRITE)) ?: throw failure()
                val event=service.getCharacteristic(UUID.fromString(BodyProtocol.EVENTS)) ?: throw failure()
                check(gatt?.setCharacteristicNotification(event,true) == true)
                val descriptor=event.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")) ?: throw failure()
                descriptor.value=BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                check(gatt?.writeDescriptor(descriptor) == true); subscribed.await()
                if(gatt?.requestMtu(185) == true) payloadSize=(withTimeoutOrNull(1800) { mtuReady.await() } ?: 23)-3
                ready.value=true; status.value="ready"
                send(JSONObject().put("type","ping"))
            }
        } catch(e: Exception) { disconnect(); throw e }
    }
    fun disconnect() {
        wifi.disconnect()
        epoch.incrementAndGet(); ready.value=false; status.value="disconnected"
        activeCommand?.cancel(); activeCommand=null
        connected.completeExceptionally(failure()); discovered.completeExceptionally(failure()); subscribed.completeExceptionally(failure())
        writeDone?.completeExceptionally(failure()); writeDone=null
        acknowledgements.values.forEach { it.completeExceptionally(failure()) }; acknowledgements.clear()
        val old=gatt; gatt=null; address=null; writeChar=null; payloadSize=20; decoder.reset()
        runCatching { old?.disconnect(); old?.close() }; onDisconnect()
    }
    private suspend fun writePacket(packet: ByteArray) = transport.withLock {
        // A cancelled motion must not leave a write in flight when stop starts.
        withContext(NonCancellable) {
            for(chunk in packet.asList().chunked(payloadSize.coerceAtLeast(20))) {
                val g=gatt ?: throw failure(); val c=writeChar ?: throw failure()
                val completion=CompletableDeferred<Unit>(); writeDone=completion
                try {
                    c.writeType=BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; c.value=chunk.toByteArray()
                    check(g.writeCharacteristic(c)) { t("蓝牙写入失败", "BLE write failed") }
                    withTimeout(2000) { completion.await() }
                } catch(e: Exception) { disconnect(); throw e }
                finally { if(writeDone === completion) writeDone=null }
            }
        }
    }
    private var pendingStop: Deferred<JSONObject>? = null
    @Synchronized private fun stopRequest(payload: JSONObject): Deferred<JSONObject> {
        pendingStop?.takeIf { it.isActive }?.let { return it }
        val task=scope.async(start=CoroutineStart.LAZY) { sendInternal(payload) }
        pendingStop=task; task.start(); return task
    }
    override suspend fun send(payload: JSONObject): JSONObject {
        require(payload.optString("type") in BodyProtocol.supportedCommands) { "Unsupported body command" }
        return if(wifi.connected) wifi.command(payload) else if(payload.optString("type") == "stop") stopRequest(payload).await() else sendInternal(payload)
    }
    private suspend fun sendInternal(payload: JSONObject): JSONObject {
        val stopping=payload.optString("type") == "stop"
        val token = if(stopping) { epoch.incrementAndGet(); activeCommand?.cancel(); epoch.get() } else epoch.get()
        if(count.incrementAndGet() > 24 && !stopping) { count.decrementAndGet(); throw LooiException("动作队列已满", "Motion queue is full") }
        try { return commands.withLock {
            if(!stopping && token != epoch.get()) throw CancellationException("Superseded")
            check(ready.value) { t("机器人尚未就绪", "Robot is not ready") }
            coroutineScope {
                activeCommand=coroutineContext[Job]
                val id=UUID.randomUUID().toString(); val ack=CompletableDeferred<JSONObject>(); acknowledgements[id]=ack
                try {
                    val value=withTimeout(5000) { writePacket(BodyProtocol.packet(payload,id)); ack.await() }
                    check(value.optString("type") in (when(payload.optString("type")) { "ping" -> setOf("ack","pong"); "config_get" -> setOf("config"); else -> setOf("ack") }) && value.opt("accepted") != false) { value.optString("message",t("机器人拒绝指令", "Robot rejected the command")) }
                    value
                } catch(e: Exception) {
                    if(e !is CancellationException || e is TimeoutCancellationException) onDisconnect()
                    throw e
                } finally { acknowledgements.remove(id); activeCommand=null }
            }
        } } finally { count.decrementAndGet() }
    }
}
enum class MotionSource(val priority: Int) { IDLE(0), FOLLOW(1), SCENARIO(2), TEST(3), REMOTE(4) }
interface RobotTransport { val ready: MutableStateFlow<Boolean>; suspend fun send(payload: JSONObject): JSONObject }
class MotionArbiter(private val robot: RobotTransport) {
    var allowed = false; private set
    var owner: MotionSource? = null; private set
    private val lock=Mutex()
    private var generation=0L
    fun arm(value: Boolean) { allowed=value }
    suspend fun acquire(source: MotionSource): Long = lock.withLock {
        check(allowed) { t("身体动作未开启", "Body motion is disabled") }
        if(owner != source) {
            check(owner == null || source.priority >= owner!!.priority) { t("其他动作正在执行", "Another action is running") }
            if(owner != null && robot.ready.value) robot.send(JSONObject().put("type","stop"))
            generation++; owner=source
        }
        generation
    }
    suspend fun send(payload: JSONObject, source: MotionSource, lease: Long): JSONObject {
        val validated=BodyProtocol.validate(payload,allowed && owner == source && lease == generation)
        return robot.send(validated)
    }
    fun release(source: MotionSource, lease: Long) { if(owner == source && lease == generation) { owner=null; generation++ } }
    fun revoke() { allowed=false; owner=null; generation++ }
    suspend fun stop(revoke: Boolean = false) {
        owner=null; generation++; if(revoke) allowed=false
        if(robot.ready.value) robot.send(JSONObject().put("type","stop"))
    }
}
