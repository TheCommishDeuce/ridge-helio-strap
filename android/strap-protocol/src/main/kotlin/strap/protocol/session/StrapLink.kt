package strap.protocol.session

import kotlinx.coroutines.flow.Flow

/**
 * The radio, as the protocol needs it — implemented by the app over Android BLE and by a
 * fake strap in tests. The app opens the connection (connect → MTU 247 → discover →
 * notifications on `0017`) before handing the link over, and closes it afterwards. The
 * fetch pair's notifications are enabled only after authentication ([openFetchChannel]),
 * which is the order proven on the strap.
 *
 * Contract:
 *  - every write suspends until the stack confirms it, writes are never interleaved, and a
 *    failed write throws [java.io.IOException] (the session names it; it never crashes);
 *  - [notifications] is hot, carries all three notify characteristics in ONE stream in
 *    arrival order (a fetch round's closing control reply must come after its data), and
 *    never drops a value — a collector that suspends to write must not lose notifications.
 */
public interface StrapLink {
    /** The negotiated ATT MTU; chunk sizes are computed from it. */
    public val mtu: Int

    /** Whether the activity-fetch pair (`0004`/`0005`) was found. Without it: totals only. */
    public val hasFetchChannel: Boolean

    public val notifications: Flow<Notification>

    /** Enables notifications on `0004`/`0005`. Called once, after the handshake. */
    public suspend fun openFetchChannel()

    /** One chunk to char `0016`. */
    public suspend fun writeChunk(bytes: ByteArray)

    /** One ACK to char `0017`. */
    public suspend fun writeAck(bytes: ByteArray)

    /** One command to char `0004`. */
    public suspend fun writeControl(bytes: ByteArray)

    /** Battery level 0–100, or null when unreadable — never a reason to fail a sync. */
    public suspend fun batteryPercent(): Int?

    /** The raw Current Time reading (`2a2b`), or null when the strap has none or it failed. */
    public suspend fun currentTime(): ByteArray? = null
}

public enum class Channel { CHUNKED, CONTROL, DATA }

public class Notification(public val channel: Channel, public val bytes: ByteArray)
