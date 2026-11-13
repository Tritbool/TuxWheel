package io.github.tuxwheel.ble

import com.google.common.truth.Truth.assertThat
import io.github.tritbool.euc.ble.models.BMSData
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

@RunWith(RobolectricTestRunner::class)
class BmsLabCaptureTest {
    private val pack = BMSData(
        3, 84.1, -2.0, null, null, null, listOf(20.0), listOf(4.1, 4.2)
    )

    @Test fun `unchanged frames retain separate sequence and callback timestamps`() = runBlocking {
        val capture = BmsLabCapture()
        val frames = capture.start { error("unexpected overflow") }
        capture.record(frames, 1000, "wheel", listOf(pack))
        capture.record(frames, 1050, "wheel", listOf(pack))
        capture.stop()

        val first = frames.receive()
        val second = frames.receive()
        assertThat(first.sequence).isEqualTo(1)
        assertThat(second.sequence).isEqualTo(2)
        assertThat(second.timestampMs).isEqualTo(1050)
        assertThat(frames.receiveCatching().isClosed).isTrue()
        val json = JSONObject(second.toJson())
        val recordedPack = json.getJSONArray("packs").getJSONObject(0)
        assertThat(recordedPack.getInt("bms_index")).isEqualTo(3)
        assertThat(recordedPack.getJSONArray("cell_voltages_v").getDouble(1)).isEqualTo(4.2)
        assertThat(recordedPack.isNull("cycles")).isTrue()
    }

    @Test fun `bounded queue reports overflow and sequence gaps`() = runBlocking {
        val capture = BmsLabCapture()
        var overflows = 0
        val frames = capture.start { overflows++ }
        repeat(4096) { assertThat(capture.record(frames, it.toLong(), "wheel", listOf(pack))).isTrue() }
        assertThat(capture.record(frames, 4096, "wheel", listOf(pack))).isFalse()
        assertThat(capture.record(frames, 4097, "wheel", listOf(pack))).isFalse()
        assertThat(overflows).isEqualTo(1)
        frames.receive()
        assertThat(capture.record(frames, 4098, "wheel", listOf(pack))).isTrue()
        capture.stop()
        var last = 0L
        for (frame in frames) last = frame.sequence
        assertThat(last).isEqualTo(4099)
    }

    @Test fun `writer produces independently readable compressed frames`() {
        val bytes = ByteArrayOutputStream()
        BmsLabWriter(bytes).use { writer ->
            writer.write(BmsLabFrame(1, 1000, "wheel", listOf(pack)))
            writer.write(BmsLabFrame(2, 1050, "wheel", listOf(pack)))
        }
        val lines = GZIPInputStream(ByteArrayInputStream(bytes.toByteArray()))
            .bufferedReader(Charsets.UTF_8).readLines()
        assertThat(lines).hasSize(2)
        assertThat(JSONObject(lines[0]).getLong("timestamp_ms")).isEqualTo(1000)
        assertThat(JSONObject(lines[1]).getLong("sequence")).isEqualTo(2)
    }
}
