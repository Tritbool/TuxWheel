package io.github.tuxwheel.ble

import io.github.tritbool.euc.ble.models.BMSData
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.GZIPOutputStream

internal class BmsLabWriter(output: OutputStream) : java.io.Closeable {
    private val writer = BufferedWriter(OutputStreamWriter(
        GZIPOutputStream(output, 8192, true), Charsets.UTF_8
    ))

    fun write(frame: BmsLabFrame) {
        writer.write(frame.toJson())
        writer.newLine()
    }

    fun flush() = writer.flush()
    override fun close() = writer.close()
}

internal data class BmsLabFrame(
    val sequence: Long,
    val timestampMs: Long,
    val address: String,
    val packs: List<BMSData>,
) {
    fun toJson(): String {
        val entries = JSONArray()
        packs.forEach { pack ->
            entries.put(JSONObject().apply {
                put("bms_index", pack.bmsIndex)
                put("voltage_v", pack.voltage ?: JSONObject.NULL)
                put("current_a", pack.current ?: JSONObject.NULL)
                put("remaining_capacity_mah", pack.remainingCapacity ?: JSONObject.NULL)
                put("factory_capacity_mah", pack.factoryCapacity ?: JSONObject.NULL)
                put("cycles", pack.cycles ?: JSONObject.NULL)
                put("is_charging", pack.isCharging ?: JSONObject.NULL)
                put("temperatures_c", pack.temperatures?.let { JSONArray(it) } ?: JSONObject.NULL)
                put("cell_voltages_v", pack.cellVoltages?.let { JSONArray(it) } ?: JSONObject.NULL)
                put("serial_number", pack.serialNumber ?: JSONObject.NULL)
                put("firmware_version", pack.firmwareVersion ?: JSONObject.NULL)
                put("charge_current_a", pack.chargeCurrent ?: JSONObject.NULL)
                put("discharge_current_a", pack.dischargeCurrent ?: JSONObject.NULL)
            })
        }
        return JSONObject().put("sequence", sequence).put("timestamp_ms", timestampMs)
            .put("packs", entries).toString()
    }
}

internal class BmsLabCapture {
    private var channel: Channel<BmsLabFrame>? = null
    private var sequence = 0L
    private var overflowReported = false
    private var onOverflow: (() -> Unit)? = null

    @Synchronized
    fun start(onOverflow: () -> Unit): Channel<BmsLabFrame> {
        check(channel == null)
        sequence = 0
        overflowReported = false
        this.onOverflow = onOverflow
        return Channel<BmsLabFrame>(4096).also { channel = it }
    }

    @Synchronized
    fun session(): Channel<BmsLabFrame>? = channel

    @Synchronized
    fun record(target: Channel<BmsLabFrame>, timestampMs: Long, address: String, packs: List<BMSData>): Boolean {
        if (target !== channel) return true
        val frame = BmsLabFrame(++sequence, timestampMs, address, packs)
        val success = target.trySend(frame).isSuccess
        if (!success && !overflowReported) {
            overflowReported = true
            onOverflow?.invoke()
        }
        return success
    }

    @Synchronized
    fun stop(expected: Channel<BmsLabFrame>? = null) {
        if (expected != null && expected !== channel) return
        channel?.close()
        channel = null
        onOverflow = null
    }
}
