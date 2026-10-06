package strap.protocol

import java.util.UUID

/** The strap's GATT characteristics and chunked-transport endpoints (spec/01 §2–§3). */
public object Gatt {
    private const val HUAMI_SUFFIX = "-0000-3512-2118-0009af100700"

    /** Chunked transport, app → strap. */
    public val CHUNKED_WRITE: UUID = UUID.fromString("00000016$HUAMI_SUFFIX")

    /** Chunked transport, strap → app; ACKs are also written here. */
    public val CHUNKED_NOTIFY: UUID = UUID.fromString("00000017$HUAMI_SUFFIX")

    /** Activity-fetch control (write + notify). */
    public val FETCH_CONTROL: UUID = UUID.fromString("00000004$HUAMI_SUFFIX")

    /** Activity-fetch data (notify). */
    public val FETCH_DATA: UUID = UUID.fromString("00000005$HUAMI_SUFFIX")

    /** Standard Battery Level. */
    public val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    /** Standard Current Time: the strap's own clock, read to learn its offset (alarms, D30). */
    public val CURRENT_TIME: UUID = UUID.fromString("00002a2b-0000-1000-8000-00805f9b34fb")

    /** Client Characteristic Configuration descriptor, to enable notifications. */
    public val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Chunked endpoint: auth handshake (never encrypted). */
    public const val ENDPOINT_AUTH: Int = 0x0082

    /** Chunked endpoint: live since-midnight totals; request is `[0x03]`. */
    public const val ENDPOINT_DAILY_TOTALS: Int = 0x0016

    /** Chunked endpoint: services list reply (diagnostic). */
    public const val ENDPOINT_SERVICES: Int = 0x0000

    /** Chunked endpoint: alarms (unencrypted on the Helio Strap). */
    public const val ENDPOINT_ALARMS: Int = 0x000f
}
