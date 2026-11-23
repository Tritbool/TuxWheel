package io.github.tuxwheel.data

import android.content.Context
import android.net.Uri
import android.os.Build
import io.github.tuxwheel.R
import io.github.tuxwheel.utils.LogHeaderEnum
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

object TripParser: KoinComponent {
    private val dao: TripDao get() = get()
    private var lastErrorValue: String? = null

    val lastError: String
        get() {
            val res = lastErrorValue ?: ""
            lastErrorValue = null
            return res
        }

    fun parseFile(
        context: Context,
        fileName: String,
        path: String,
        uri: Uri,
        persist: Boolean = true,
        existingTrip: TripDataDbEntry? = null,
        checkCancellation: () -> Unit = {}
    ): Pair<List<LogTick>, TripDataDbEntry?> {
        checkCancellation()
        lastErrorValue = null
        val inputStream: InputStream?
        try {
            inputStream = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                // Android 9 or less
                FileInputStream(File(path))
            } else {
                // Android 10+
                context.contentResolver.openInputStream(uri)
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            lastErrorValue = ex.localizedMessage
            Timber.wtf(lastErrorValue)
            return Pair(emptyList(), null)
        }
        return parseFile(context, fileName, inputStream, persist, existingTrip, checkCancellation)
    }

    fun parseFile(
        context: Context,
        fileName: String,
        inputStream: InputStream?,
        persist: Boolean = true,
        existingTrip: TripDataDbEntry? = null,
        checkCancellation: () -> Unit = {}
    ): Pair<List<LogTick>, TripDataDbEntry?> {
        lastErrorValue = null
        if (inputStream == null) {
            lastErrorValue = context.getString(R.string.error_inputstream_null)
            Timber.wtf(lastErrorValue)
            return Pair(emptyList(), null)
        }

        // read header
        val reader = BufferedReader(InputStreamReader(inputStream))
        val header = HashMap<LogHeaderEnum, Int>()
        val sdfTime = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        val sdfFullDate = SimpleDateFormat("dd HH:mm:ss.SSS", Locale.getDefault())
        val resultList = ArrayList<LogTick>()

        try {
            checkCancellation()
            val headerLine = reader.readLine()?.removePrefix("\uFEFF")?.split(",")
                ?: return Pair(emptyList(), null)
            for (i in headerLine.indices) {
                LogHeaderEnum.fromHeaderColumn(headerLine[i])?.let { column ->
                    if (column !in header) header[column] = i
                }
            }
            if (LogHeaderEnum.DATE !in header || LogHeaderEnum.TIME !in header) {
                lastErrorValue = "CSV is missing its date or time column"
                return Pair(emptyList(), null)
            }
            reader.forEachLine { line ->
                checkCancellation()
                if (line.isNotEmpty())
                {
                    val row = line.split(",")
                    fun value(column: LogHeaderEnum): String =
                        header[column]?.takeIf { it in row.indices }?.let { row[it].trim() }.orEmpty()
                    val timeString = value(LogHeaderEnum.TIME)
                    val dateString = value(LogHeaderEnum.DATE)
                    if (timeString.isEmpty() || dateString.isEmpty()) return@forEachLine
                    val time = sdfTime.parse(timeString)?.time
                        ?: throw IllegalArgumentException("Invalid log time: $timeString")
                    val timePlusDay = sdfFullDate.parse(
                        "$dateString $timeString", ParsePosition(8)
                    )?.time ?: time
                    val logTick = LogTick(
                        timeString = timeString,
                        timePlusDayOfWeek = timePlusDay / 100f,
                        time = time / 100f,
                        batteryLevel = value(LogHeaderEnum.BATTERY_LEVEL).toIntOrNull() ?: 0,
                        voltage = value(LogHeaderEnum.VOLTAGE).toDoubleOrNull() ?: 0.0,
                        current = value(LogHeaderEnum.CURRENT).toDoubleOrNull() ?: 0.0,
                        power = value(LogHeaderEnum.POWER).toDoubleOrNull() ?: 0.0,
                        speed = value(LogHeaderEnum.SPEED).toDoubleOrNull() ?: 0.0,
                        temperature = value(LogHeaderEnum.SYSTEM_TEMP).toDoubleOrNull()?.toInt() ?: 0,
                        pwm = value(LogHeaderEnum.PWM).toDoubleOrNull() ?: 0.0,
                        distance = distanceMeters(value(LogHeaderEnum.DISTANCE)),
                        totalDistance = distanceMeters(value(LogHeaderEnum.TOTALDISTANCE))
                    )
                    resultList.add(logTick)
                }
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            lastErrorValue = ex.localizedMessage
            Timber.wtf(lastErrorValue)
            return Pair(resultList, null)
        } finally {
            inputStream.close()
        }

        checkCancellation()
        if (resultList.isEmpty()) return Pair(emptyList(), null)
        val databaseTrip = if (persist) dao.getTripByFileName(fileName) else null
        var trip = existingTrip?.copy(fileName = fileName, id = databaseTrip?.id ?: existingTrip.id) ?: databaseTrip
        if (trip == null) {
            trip = TripDataDbEntry(fileName = fileName)
        }
        if (persist && databaseTrip == null) {
            dao.insert(trip)
            trip = trip.copy(id = dao.getTripByFileName(fileName)?.id ?: trip.id)
        }
        try {
            val first = resultList.first()
            val last = resultList.last()
            trip.apply {
                maxCurrent = 0f
                maxPwm = 0f
                duration = ((last.time - first.time) / 600.0).toInt()
                if (duration < 0) {
                    // +24 hours in minutes
                    duration += 1440
                }
                if (resultList.size > 1 && duration == 0) duration = 1
                var timeToExclude = 0f
                var beforeTime = first.time // 1/10 sec
                var firstTotalDistance = 0

                resultList.forEach {
                    checkCancellation()
                    // If time between ticks more than 1 sec, we need to exclude this time
                    // or if time between ticks more than -1 hour (360 sec) next day ticks will be excluded
                    val timeBetween = it.time - beforeTime
                    if (-3600_0 > timeBetween || timeBetween > 10) {
                        timeToExclude += timeBetween
                    }
                    beforeTime = it.time

                    if (firstTotalDistance == 0 && it.totalDistance > 0) {
                        firstTotalDistance = it.totalDistance
                    }
                    maxCurrent = maxCurrent.coerceAtLeast(it.current.toFloat())
                    maxPwm = maxPwm.coerceAtLeast(it.pwm.toFloat())
                }

                val logDuration = (last.time - first.time - timeToExclude) / 600
                val recentTicks = resultList.subList(0.coerceAtLeast(resultList.size - 10), resultList.size)
                val maxTotalDistance = recentTicks.maxOf { it.totalDistance }
                distance = (maxTotalDistance - firstTotalDistance).coerceAtLeast(0)
                if (distance == 0) {
                    distance = (recentTicks.maxOf { it.distance } - resultList.first().distance).coerceAtLeast(0)
                }
                val powers = resultList.map { it.power }
                maxPower = powers.max().toFloat()
                val speeds = resultList.map { it.speed }
                maxSpeed = speeds.max().toFloat()
                avgSpeed = speeds.average().toFloat()
                consumptionTotal = powers.average().toFloat() * logDuration / 60F
                consumptionByKm = if (distance > 0) {
                    consumptionTotal * 1000F / distance
                } else {
                    0F
                }
            }
            checkCancellation()
            if (persist) dao.update(trip)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            lastErrorValue = ex.localizedMessage
            Timber.wtf(lastErrorValue)
        }

        return Pair(resultList, trip)
    }

    private fun distanceMeters(value: String): Int {
        val distance = value.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return 0
        val meters = if (value.contains('.') || value.contains('e', ignoreCase = true)) {
            distance * 1000.0
        } else {
            distance
        }
        return meters.roundToInt()
    }
}
