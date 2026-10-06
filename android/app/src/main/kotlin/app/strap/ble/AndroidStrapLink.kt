package app.strap.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import strap.protocol.Gatt
import strap.protocol.session.Channel
import strap.protocol.session.Notification
import strap.protocol.session.StrapLink
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [StrapLink] over Android's GATT API. Holds no protocol logic: it serialises GATT
 * operations (Android allows one in flight), and funnels every notification into one
 * ordered, unbounded stream. Permission checks are the caller's (BLUETOOTH_CONNECT).
 */
@SuppressLint("MissingPermission")
class AndroidStrapLink private constructor(private val log: (String) -> Unit) : StrapLink {
    private val ops = Mutex()
    @Volatile private var pending: CompletableDeferred<Any?>? = null
    private val connected = CompletableDeferred<Unit>()
    private lateinit var gatt: BluetoothGatt
    private val chars = mutableMapOf<UUID, BluetoothGattCharacteristic>()
    private val flow = MutableSharedFlow<Notification>(extraBufferCapacity = Int.MAX_VALUE)

    override var mtu: Int = 23
        private set
    override val hasFetchChannel: Boolean get() = Gatt.FETCH_CONTROL in chars && Gatt.FETCH_DATA in chars
    override val notifications: SharedFlow<Notification> = flow

    @Volatile
    var disconnected: Boolean = false
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("connection state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnected = true
                connected.completeExceptionally(IOException("disconnected (status $status)"))
                pending?.completeExceptionally(IOException("disconnected (status $status)"))
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = complete(status, mtu)

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = complete(status, null)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = complete(status, null)

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = complete(status, null)

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            complete(status, value)

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = emit(c.uuid, value)

        @Deprecated("API < 33 delivery path")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) @Suppress("DEPRECATION") emit(c.uuid, c.value.copyOf())
        }
    }

    private fun complete(status: Int, value: Any?) {
        val op = pending ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) op.complete(value) else op.completeExceptionally(IOException("GATT status $status"))
    }

    private fun emit(uuid: UUID, value: ByteArray) {
        val channel = when (uuid) {
            Gatt.CHUNKED_NOTIFY -> Channel.CHUNKED
            Gatt.FETCH_CONTROL -> Channel.CONTROL
            Gatt.FETCH_DATA -> Channel.DATA
            else -> return
        }
        flow.tryEmit(Notification(channel, value))
    }

    /** Runs one GATT operation to completion; [start] returns false when Android refuses to queue it. */
    private suspend fun op(name: String, timeout: Duration = OP_TIMEOUT, start: () -> Boolean): Any? = ops.withLock {
        if (disconnected) throw IOException("$name: not connected")
        val done = CompletableDeferred<Any?>()
        pending = done
        try {
            if (!start()) throw IOException("$name: Android refused the operation")
            // Wrapped: most operations complete with a null value, which withTimeoutOrNull
            // would otherwise be indistinguishable from a timeout.
            (withTimeoutOrNull(timeout) { Completed(done.await()) } ?: throw IOException("$name: no answer within $timeout")).value
        } finally {
            pending = null
        }
    }

    private class Completed(val value: Any?)

    private suspend fun write(uuid: UUID, bytes: ByteArray) {
        val c = chars[uuid] ?: throw IOException("characteristic $uuid missing")
        val type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        op("write") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(c, bytes, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = bytes
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(c)
            }
        }
    }

    private suspend fun enableNotifications(uuid: UUID) {
        val c = chars[uuid] ?: throw IOException("characteristic $uuid missing")
        if (!gatt.setCharacteristicNotification(c, true)) throw IOException("notifications refused for $uuid")
        val cccd = c.getDescriptor(Gatt.CCCD) ?: throw IOException("no CCCD on $uuid")
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        op("enable notifications") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, enable) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = enable
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
        }
    }

    override suspend fun openFetchChannel() {
        enableNotifications(Gatt.FETCH_CONTROL)
        enableNotifications(Gatt.FETCH_DATA)
    }

    override suspend fun writeChunk(bytes: ByteArray) = write(Gatt.CHUNKED_WRITE, bytes)

    override suspend fun writeAck(bytes: ByteArray) = write(Gatt.CHUNKED_NOTIFY, bytes)

    override suspend fun writeControl(bytes: ByteArray) = write(Gatt.FETCH_CONTROL, bytes)

    override suspend fun batteryPercent(): Int? {
        val c = chars[Gatt.BATTERY_LEVEL] ?: return null
        return try {
            val value = op("battery read") {
                @Suppress("DEPRECATION")
                gatt.readCharacteristic(c)
            } as? ByteArray
            value?.firstOrNull()?.toInt()?.takeIf { it in 0..100 }
        } catch (e: IOException) {
            log("battery read failed: ${e.message}") // a nicety; never fails a sync
            null
        }
    }

    override suspend fun currentTime(): ByteArray? {
        val c = chars[Gatt.CURRENT_TIME] ?: return null.also { log("no current time characteristic") }
        return try {
            op("current time read") {
                @Suppress("DEPRECATION")
                gatt.readCharacteristic(c)
            } as? ByteArray
        } catch (e: IOException) {
            log("current time read failed: ${e.message}")
            null
        }
    }

    fun close() {
        disconnected = true
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    companion object {
        private val OP_TIMEOUT = 10.seconds
        private val CONNECT_TIMEOUT = 20.seconds
        private const val DISCOVERY_ATTEMPTS = 5
        private val DISCOVERY_RETRY = 1.seconds

        /** Connect → MTU 247 → discover → notifications on `0017`, in that order (spec/01 §2). */
        suspend fun connect(context: Context, mac: String, log: (String) -> Unit): AndroidStrapLink {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: throw IOException("no Bluetooth adapter")
            if (!adapter.isEnabled) throw IOException("Bluetooth is off")
            val device = adapter.getRemoteDevice(mac)
            val link = AndroidStrapLink(log)
            link.gatt = device.connectGatt(context, false, link.callback, BluetoothDevice.TRANSPORT_LE)
            try {
                withTimeout(CONNECT_TIMEOUT) { link.connected.await() }
                link.negotiateMtu()
                link.discover()
                link.enableNotifications(Gatt.CHUNKED_NOTIFY)
            } catch (e: Exception) {
                link.close()
                throw if (e is IOException) e else IOException("connect failed: ${e.message}", e)
            }
            return link
        }
    }

    /** Best-effort: a strap that refuses 247 still works, it just fragments more. */
    private suspend fun negotiateMtu() {
        mtu = try {
            op("request MTU") { gatt.requestMtu(247) } as Int
        } catch (e: IOException) {
            log("MTU request failed, using 23: ${e.message}")
            23
        }
        log("MTU = $mtu")
    }

    /**
     * Reconnecting within ~2 s of the previous connection (a sync right after the Strap tab
     * read, or two syncs in a row), Android reports discovery as successful but with an EMPTY
     * service table while it tears the old link down. Asking again a moment later works.
     */
    private suspend fun discover() {
        for (attempt in 1..DISCOVERY_ATTEMPTS) {
            op("discover services") { gatt.discoverServices() }
            chars.clear()
            for (service in gatt.services) for (c in service.characteristics) chars[c.uuid] = c
            if (Gatt.CHUNKED_WRITE in chars && Gatt.CHUNKED_NOTIFY in chars) break
            log("discovery $attempt found ${gatt.services.size} services, not the strap's; asking again")
            if (attempt == DISCOVERY_ATTEMPTS) throw IOException("chunked transport characteristics (0016/0017) not found")
            delay(DISCOVERY_RETRY)
        }
        log("characteristics: fetch channel ${if (hasFetchChannel) "present" else "MISSING"}")
    }
}
