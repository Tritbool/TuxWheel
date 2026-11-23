package io.github.tuxwheel.ble

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DiagnosticLogNamingTest {
    @Test fun `leaperkim event log saves as text`() {
        assertThat(DiagnosticLogNaming.mimeType("leaperkim://event-log"))
            .isEqualTo("text/plain")
        assertThat(DiagnosticLogNaming.suggestedFileName("leaperkim://event-log"))
            .isEqualTo("leaperkim-event-log.txt")
    }

    @Test fun `inmotion log keeps its binary name`() {
        assertThat(DiagnosticLogNaming.mimeType("2:/log.bin"))
            .isEqualTo("application/octet-stream")
        assertThat(DiagnosticLogNaming.suggestedFileName("2:/log.bin"))
            .isEqualTo("inmotion-diagnostic.bin")
    }

    @Test fun `unknown and missing paths fall back to a generic name`() {
        assertThat(DiagnosticLogNaming.mimeType(null)).isEqualTo("application/octet-stream")
        assertThat(DiagnosticLogNaming.suggestedFileName(null)).isEqualTo("wheel-diagnostic.bin")
        assertThat(DiagnosticLogNaming.suggestedFileName("")).isEqualTo("wheel-diagnostic.bin")
        assertThat(DiagnosticLogNaming.suggestedFileName("0:/events.txt"))
            .isEqualTo("events.txt")
    }
}
