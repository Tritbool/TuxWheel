package io.github.tuxwheel.ble

import com.google.common.truth.Truth.assertThat
import io.github.tritbool.euc.ble.core.EucUuid
import io.github.tritbool.euc.ble.core.TrafficDirection
import io.github.tritbool.euc.ble.core.TrafficEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.Locale

class RawTrafficCaptureTest {
    private val address = "AA:BB:CC:DD:EE:FF"
    private val uuid = EucUuid.parse("0000ffe1-0000-1000-8000-00805f9b34fb")

    private fun event(sequence: Long, direction: TrafficDirection = TrafficDirection.RX) =
        TrafficEvent(sequence, 123456789L, direction, uuid, byteArrayOf(0, 0x80.toByte(), 0xFF.toByte()))

    @Test fun `csv preserves capture metadata and distinguishes RX and TX`() {
        val oldLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertThat(RawTrafficRecord.CSV_HEADER)
                .isEqualTo("timestamp_ms,hex,type,sequence,characteristic_uuid")
            for (direction in TrafficDirection.entries) {
                val sample = event(42, direction)
                assertThat(RawTrafficRecord(address, sample).toCsvLine())
                    .isEqualTo("123456789,0080FF,${direction.name},42,$uuid")
                assertThat(sample.bytes).asList().containsExactly(0.toByte(), 0x80.toByte(), 0xFF.toByte()).inOrder()
            }

        } finally {
            Locale.setDefault(oldLocale)
        }
    }

    @Test fun `plaintext KingSong credential frames never enter the diagnostic queue`() = runTest {
        val capture = RawTrafficCapture()
        for (opcode in listOf(0x41, 0x42, 0x44)) {
            val bytes = ByteArray(20).apply {
                this[0] = 0xAA.toByte()
                this[1] = 0x55
                "0012".toByteArray().copyInto(this, 2)
                this[16] = opcode.toByte()
            }
            capture.record(address, TrafficEvent(opcode.toLong(), 123L, TrafficDirection.TX, uuid, bytes), true)
        }
        val next = async(start = CoroutineStart.UNDISPATCHED) { capture.records.first() }
        assertThat(next.isCompleted).isFalse()
        capture.record(address, event(2), true)
        assertThat(next.await().event.sequence).isEqualTo(2L)
    }

    @Test fun `initialization traffic waits for service and retains wheel identity and order`() = runTest {
        val capture = RawTrafficCapture()
        capture.record(address, event(1, TrafficDirection.TX), true)
        capture.record("11:22:33:44:55:66", event(2), true)
        val records = capture.records.take(2).toList()
        assertThat(records.map { it.event.sequence }).containsExactly(1L, 2L).inOrder()
        assertThat(records.map { it.address }).containsExactly(address, "11:22:33:44:55:66").inOrder()
    }

    @Test fun `overflow is bounded and leaves sequence gaps instead of blocking capture`() = runTest {
        val capture = RawTrafficCapture()
        for (sequence in 1L..300L) capture.record(address, event(sequence), true)
        val records = capture.records.take(256).toList()
        assertThat(records.first().event.sequence).isEqualTo(45L)
        assertThat(records.last().event.sequence).isEqualTo(300L)
    }

    @Test fun `disabled capture clears pending events and stop prevents stale service restart data`() = runTest {
        val capture = RawTrafficCapture()
        capture.record(address, event(1), true)
        capture.record(address, event(2), false)
        capture.record(address, event(3), true)
        capture.stop()
        capture.record(address, event(4), true)
        capture.start()
        val next = async(start = CoroutineStart.UNDISPATCHED) { capture.records.first() }
        assertThat(next.isCompleted).isFalse()
        capture.record(address, event(5), true)
        assertThat(next.await().event.sequence).isEqualTo(5L)
    }

    @Test fun `consumed traffic is not replayed to the next service collector`() = runTest {
        val capture = RawTrafficCapture()
        capture.record(address, event(1), true)
        assertThat(capture.records.first().event.sequence).isEqualTo(1L)
        val next = async(start = CoroutineStart.UNDISPATCHED) { capture.records.first() }
        assertThat(next.isCompleted).isFalse()
        capture.record(address, event(2), true)
        assertThat(next.await().event.sequence).isEqualTo(2L)
    }

    @Test fun `disabling diagnostics clears initialization even without another BLE event`() = runTest {
        val capture = RawTrafficCapture()
        capture.record(address, event(1), true)
        capture.discardPending()
        val next = async(start = CoroutineStart.UNDISPATCHED) { capture.records.first() }
        assertThat(next.isCompleted).isFalse()
        capture.record(address, event(2), true)
        assertThat(next.await().event.sequence).isEqualTo(2L)
    }
}
