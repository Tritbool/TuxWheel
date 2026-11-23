package io.github.tuxwheel.ble

/**
 * Names and MIME types for wheel diagnostic-log exports, derived from the wheel-side
 * [io.github.tritbool.euc.ble.models.DiagnosticLogTransfer.filePath] reported by the library.
 *
 * The InMotion transfer carries the opaque `2:/log.bin` file, but the LeaperKim event log
 * arrives as records on the telemetry stream and the library renders them as text, so it
 * must be saved with a `.txt` name and not as a binary blob.
 */
object DiagnosticLogNaming {
    internal const val LEAPERKIM_EVENT_LOG_PATH = "leaperkim://event-log"
    internal const val INMOTION_LOG_PATH = "2:/log.bin"

    /** MIME type for the save dialog; text for the rendered LeaperKim event log. */
    fun mimeType(filePath: String?): String =
        if (isLeaperkimEventLog(filePath)) "text/plain" else "application/octet-stream"

    /** Default file name offered by the save dialog. */
    fun suggestedFileName(filePath: String?): String = when {
        isLeaperkimEventLog(filePath) -> "leaperkim-event-log.txt"
        filePath == INMOTION_LOG_PATH -> "inmotion-diagnostic.bin"
        !filePath.isNullOrBlank() ->
            filePath.substringAfterLast('/').takeIf { it.isNotBlank() }
                ?: "wheel-diagnostic.bin"
        else -> "wheel-diagnostic.bin"
    }

    private fun isLeaperkimEventLog(filePath: String?): Boolean =
        filePath != null && filePath.startsWith("leaperkim://")
}
