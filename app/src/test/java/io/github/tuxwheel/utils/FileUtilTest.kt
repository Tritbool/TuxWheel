package io.github.tuxwheel.utils

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import java.io.ByteArrayInputStream

class FileUtilTest {
    @Test fun `trip discovery accepts CSV logs but excludes raw and BMS captures`() {
        assertThat(FileUtil.isTripLogFile("ride.CSV")).isTrue()
        assertThat(FileUtil.isTripLogFile("RAW_ride.csv")).isFalse()
        assertThat(FileUtil.isTripLogFile("ride.raw.csv")).isFalse()
        assertThat(FileUtil.isTripLogFile("ride.bms.csv")).isFalse()
        assertThat(FileUtil.isTripLogFile("notes.txt")).isFalse()
    }

    @Test fun `reading a log closes its input stream and preserves all bytes`() {
        val bytes = ByteArray(20000) { it.toByte() }
        var closed = false
        val input = object : ByteArrayInputStream(bytes) {
            override fun close() {
                closed = true
                super.close()
            }
        }
        val context = mockk<Context>()
        val resolver = mockk<ContentResolver>()
        val uri = mockk<Uri>()
        every { context.contentResolver } returns resolver
        every { resolver.openInputStream(uri) } returns input
        val file = FileUtil(context)
        FileUtil::class.java.getDeclaredField("uri").apply {
            isAccessible = true
            set(file, uri)
        }

        assertThat(file.readBytes()).isEqualTo(bytes)
        assertThat(closed).isTrue()
    }

    @Test fun `reading an unprepared log returns null`() {
        assertThat(FileUtil(mockk()).readBytes()).isNull()
    }
}
