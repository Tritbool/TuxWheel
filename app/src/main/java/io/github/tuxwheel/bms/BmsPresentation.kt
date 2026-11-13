package io.github.tuxwheel.bms

import io.github.tuxwheel.R
import io.github.tuxwheel.utils.Constants.WHEEL_TYPE
import io.github.tuxwheel.utils.SmartBms
import java.util.Collections
import java.util.Locale

data class BmsPackSnapshot(
    val number: Int,
    val cellNum: Int,
    val fields: Map<Int, String>,
    val cells: List<Double>,
    val balanceMap: Int,
    val hasMeasurements: Boolean = false
)

data class BmsSnapshot(
    val wheelType: WHEEL_TYPE,
    val model: String,
    val protoVer: String,
    val packs: List<BmsPackSnapshot>,
    val battery: Int,
    val voltage: Double,
    val current: Double,
    val temperature: Double,
    val motorTemperature: Double
) {
    val hasDetails: Boolean get() = packs.any { it.cellNum > 0 || it.hasMeasurements }
}

data class BmsRow(val label: Int, val values: List<String>)
data class BmsPresentation(val rows: List<BmsRow>, val packNumbers: List<Int>, val isGotway: Boolean)

object BmsMapper {
    private fun format(pattern: String, vararg values: Any) = String.format(Locale.US, pattern, *values)
    val cellLabels = listOf(
        R.string.bmsCell1, R.string.bmsCell2, R.string.bmsCell3, R.string.bmsCell4,
        R.string.bmsCell5, R.string.bmsCell6, R.string.bmsCell7, R.string.bmsCell8,
        R.string.bmsCell9, R.string.bmsCell10, R.string.bmsCell11, R.string.bmsCell12,
        R.string.bmsCell13, R.string.bmsCell14, R.string.bmsCell15, R.string.bmsCell16,
        R.string.bmsCell17, R.string.bmsCell18, R.string.bmsCell19, R.string.bmsCell20,
        R.string.bmsCell21, R.string.bmsCell22, R.string.bmsCell23, R.string.bmsCell24,
        R.string.bmsCell25, R.string.bmsCell26, R.string.bmsCell27, R.string.bmsCell28,
        R.string.bmsCell29, R.string.bmsCell30, R.string.bmsCell31, R.string.bmsCell32,
        R.string.bmsCell33, R.string.bmsCell34, R.string.bmsCell35, R.string.bmsCell36,
        R.string.bmsCell37, R.string.bmsCell38, R.string.bmsCell39, R.string.bmsCell40,
        R.string.bmsCell41, R.string.bmsCell42, R.string.bmsCell43, R.string.bmsCell44,
        R.string.bmsCell45, R.string.bmsCell46, R.string.bmsCell47, R.string.bmsCell48,
        R.string.bmsCell49, R.string.bmsCell50
    )
    private val fallback = listOf(R.string.bmsRemPerc, R.string.bmsVoltage, R.string.bmsCurrent, R.string.bmsTemp1, R.string.bmsTemp2)
    private val extrema = listOf(R.string.bmsAvgCell, R.string.bmsMaxCell, R.string.bmsMinCell, R.string.bmsCellDiff)
    private val kingsong = setOf("KS-S20", "KS-S22", "KS-S19", "KS-S16", "KS-S16P", "KS-F22P", "KS-F18P", "KS-14SP")
    private val veteran = setOf("Lynx", "Lynx S", "Sherman L", "Nosfet Apex", "Nosfet Aeon", "Patton S", "Nosfet Aero", "Oryx")

    // Capture on the session's main-thread update boundary, never from a composable.
    fun pack(b: SmartBms, number: Int = 1): BmsPackSnapshot {
        val fields = linkedMapOf(
            R.string.bmsSn to b.serialNumber, R.string.bmsFw to b.versionNumber,
            R.string.bmsFactoryCap to format("%d mAh", b.factoryCap),
            R.string.bmsActualCap to format("%d mAh", b.actualCap),
            R.string.bmsCycles to format("%d", b.fullCycles),
            R.string.bmsChrgCount to format("%d", b.chargeCount),
            R.string.bmsMfgDate to b.mfgDateStr, R.string.bmsStatus to format("%d", b.status),
            R.string.bmsRemCap to format("%d mAh", b.remCap),
            R.string.bmsRemPerc to format("%d %%", b.remPerc),
            R.string.bmsCurrent to format("%.2f A", b.current),
            R.string.bmsVoltage to format("%.2f V", b.voltage),
            R.string.bmsSemiVoltage1 to format("%.2f V", b.semiVoltage1),
            R.string.bmsSemiVoltage2 to format("%.2f V", b.semiVoltage2),
            R.string.bmsTemp1 to format("%.1f°C", b.temp1), R.string.bmsTemp2 to format("%.1f°C", b.temp2),
            R.string.bmsTemp3 to format("%.1f°C", b.temp3), R.string.bmsTemp4 to format("%.1f°C", b.temp4),
            R.string.bmsTemp5 to format("%.1f°C", b.temp5), R.string.bmsTemp6 to format("%.1f°C", b.temp6),
            R.string.bmsTempMos to format("%.1f°C", b.tempMos),
            R.string.bmsTempMosEnv to format("%.1f°C", b.tempMosEnv),
            R.string.bmsTemp1Env to format("%.1f°C", b.temp1Env),
            R.string.bmsTemp2Env to format("%.1f°C", b.temp2Env),
            R.string.bmsHumidity1Env to format("%.1f %%", b.humidity1Env),
            R.string.bmsHumidity2Env to format("%.1f %%", b.humidity2Env),
            R.string.bmsHealth to format("%d %%", b.health),
            R.string.bmsAvgCell to format("%.3f V", b.avgCell),
            R.string.bmsMaxCell to format("%.3f V [%d]", b.maxCell, b.maxCellNum),
            R.string.bmsMinCell to format("%.3f V [%d]", b.minCell, b.minCellNum),
            R.string.bmsCellDiff to format("%.3f V", b.cellDiff)
        )
        return BmsPackSnapshot(number, b.cellNum, Collections.unmodifiableMap(fields),
            Collections.unmodifiableList(b.cells.toList()), b.balanceMap)
    }

    fun fields(s: BmsSnapshot): List<Int> {
        if (!s.hasDetails) return fallback
        val model = s.model
        val fields = mutableListOf<Int>()
        var count: Int
        when (s.wheelType) {
            WHEEL_TYPE.KINGSONG -> {
                if (model !in kingsong) return fallback
                fields += listOf(R.string.bmsSn, R.string.bmsFw, R.string.bmsFactoryCap, R.string.bmsCycles,
                    R.string.bmsRemCap, R.string.bmsRemPerc, R.string.bmsCurrent, R.string.bmsVoltage,
                    R.string.bmsTemp1, R.string.bmsTemp2)
                if (model != "KS-14SP") {
                    fields += listOf(R.string.bmsTemp3, R.string.bmsTemp4)
                    if (model in setOf("KS-S20", "KS-S22", "KS-F22P", "KS-F18P")) fields += R.string.bmsTemp5
                    if (model in setOf("KS-S20", "KS-S22", "KS-F22P")) fields += R.string.bmsTemp6
                }
                fields += R.string.bmsTempMos
                if (model != "KS-F18P") fields += R.string.bmsTempMosEnv
                fields += listOf(R.string.bmsTemp1Env, R.string.bmsHumidity1Env)
                if (model == "KS-F18P") fields += listOf(R.string.bmsTemp2Env, R.string.bmsHumidity2Env)
                count = when (model) {
                    "KS-F22P" -> 42
                    "KS-F18P" -> 36
                    "KS-S20", "KS-S22" -> 30
                    "KS-S19" -> 24
                    "KS-S16", "KS-S16P" -> 20
                    else -> 16
                }
            }
            WHEEL_TYPE.VETERAN -> {
                if (model !in veteran) return fallback
                fields += listOf(R.string.bmsCurrent, R.string.bmsVoltage, R.string.bmsTemp1,
                    R.string.bmsTemp2, R.string.bmsTemp3, R.string.bmsTemp4, R.string.bmsTemp5, R.string.bmsTemp6)
                count = when (model) { "Oryx" -> 42; "Patton S", "Nosfet Aero" -> 30; else -> 36 }
            }
            WHEEL_TYPE.GOTWAY -> {
                fields += listOf(R.string.bmsCurrent, R.string.bmsVoltage, R.string.bmsTemp1, R.string.bmsTemp2)
                    .filter { field -> s.packs.any { field in it.fields } }
                count = s.packs.maxOfOrNull { it.cellNum } ?: 0
                return fields + (if (count > 0) extrema else emptyList()) + cellLabels.take(count)
            }
            WHEEL_TYPE.NINEBOT_Z -> {
                if (s.protoVer.isNotEmpty()) return fallback
                fields += listOf(R.string.bmsSn, R.string.bmsFw, R.string.bmsFactoryCap, R.string.bmsActualCap,
                    R.string.bmsCycles, R.string.bmsChrgCount, R.string.bmsMfgDate, R.string.bmsStatus,
                    R.string.bmsRemCap, R.string.bmsRemPerc, R.string.bmsCurrent, R.string.bmsVoltage,
                    R.string.bmsTemp1, R.string.bmsTemp2, R.string.bmsHealth)
                count = (s.packs.maxOfOrNull { it.cellNum } ?: 0).coerceIn(14, 16)
            }
            else -> return fallback
        }
        return fields + extrema + cellLabels.take(count)
    }

    fun present(s: BmsSnapshot): BmsPresentation {
        val selected = fields(s)
        val fallbackValues = mapOf(
            R.string.bmsRemPerc to format("%d %%", s.battery),
            R.string.bmsVoltage to format("%.2f V", s.voltage),
            R.string.bmsCurrent to format("%.2f A", s.current),
            R.string.bmsTemp1 to format("%.1f°C", s.temperature),
            R.string.bmsTemp2 to format("%.1f°C", s.motorTemperature)
        )
        fun value(pack: BmsPackSnapshot, field: Int): String {
            val index = cellLabels.indexOf(field)
            if (index < 0) {
                if (s.wheelType == WHEEL_TYPE.GOTWAY && field in extrema && pack.cellNum == 0) return ""
                return pack.fields[field].orEmpty()
            }
            if (s.wheelType == WHEEL_TYPE.GOTWAY &&
                (index >= pack.cellNum || (pack.cells.getOrNull(index) ?: 0.0) <= 0.0)) return ""
            // Read-only: balancing status is unavailable for cells 33+ because the
            // authoritative mask is an Int. Never infer [B] by wrapping a shift.
            val balance = if (index < Int.SIZE_BITS && (pack.balanceMap ushr index and 1) == 1) "[B]" else ""
            return format("%.3f V %s", pack.cells.getOrElse(index) { 0.0 }, balance)
        }
        val shownPacks = if (s.hasDetails) {
            s.packs.filter { it.cellNum > 0 || it.hasMeasurements }
        } else emptyList()
        val numbers = shownPacks.map { it.number }.ifEmpty { listOf(1) }
        return BmsPresentation(Collections.unmodifiableList(selected.map { field ->
            val values = if (!s.hasDetails) listOf(fallbackValues.getValue(field))
            else shownPacks.map { value(it, field) }
            BmsRow(field, Collections.unmodifiableList(values))
        }), Collections.unmodifiableList(numbers), s.wheelType == WHEEL_TYPE.GOTWAY && s.hasDetails)
    }
}
