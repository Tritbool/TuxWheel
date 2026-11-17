package io.github.tuxwheel.feature.dashboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import io.github.tuxwheel.AppConfig
import io.github.tuxwheel.DialogHelper.setBlackIcon
import io.github.tuxwheel.R
import io.github.tuxwheel.ble.BleSessionViewModel
import io.github.tuxwheel.feature.speedlimiter.SpeedLimiter
import io.github.tuxwheel.utils.Alarms
import io.github.tuxwheel.utils.SomeUtil
import io.github.tritbool.euc.ble.protocols.CommandType
import io.github.tritbool.euc.ble.protocols.CommandValue

/** Dashboard commands live in gesture callbacks, never in Compose drawing or recomposition. */
class DashboardActions(
    private val context: Context,
    private val session: BleSessionViewModel,
    private val config: AppConfig
) {
    private var dialog: AlertDialog? = null
    private var active = true

    fun start() { active = true }

    fun singleTap() {
        if (!active) return
        if (config.useBeepOnSingleTap) SomeUtil.playBeep()
    }

    fun doubleTap() {
        if (!active) return
        toggleLight(session, config)
    }

    /** Immediate while stationary; asks for confirmation if the wheel is moving. */
    fun toggleLock(state: DashboardUiState) {
        if (!active || !state.lockSupported) return
        val lock = !state.wheelLocked
        if (kotlin.math.abs(state.speed) < STATIONARY_SPEED_KMH) {
            setLocked(lock)
            return
        }
        dialog?.dismiss()
        dialog = AlertDialog.Builder(context, R.style.OriginalTheme_Dialog_Alert)
            .setTitle(if (lock) R.string.lock_wheel else R.string.unlock_wheel)
            .setMessage(if (lock) R.string.lock_wheel_confirmation else R.string.unlock_wheel_confirmation)
            .setPositiveButton(android.R.string.ok) { _, _ -> if (active) setLocked(lock) }
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(true).create()
        dialog?.show()
    }

    @SuppressLint("MissingPermission")
    private fun setLocked(lock: Boolean) {
        config.wheelLocked = lock
        session.setWheelLocked(lock)
    }

    @SuppressLint("MissingPermission")
    fun refreshLockState() {
        if (active) session.requestWheelLockState()
    }

    fun toggleSpeedLimiter() {
        if (!active) return
        if (!SpeedLimiter.toggle(session, config)) {
            Toast.makeText(context, R.string.speed_limiter_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    fun replaceBlock(block: DashboardBlock, state: DashboardUiState) {
        if (!active) return
        val titles = config.viewBlocks.toList()
        if (titles.getOrNull(block.slot) != block.selectionKey) return
        val selected = state.infoBlocks.filter { it.metric != 0 }.map { it.metric }.toSet()
        val items = state.catalogue.filter { it.metric !in selected && it.label !in titles }.map { it.label }
        if (items.isEmpty()) return
        Alarms.vibrate(context, longArrayOf(0, 50, 50))
        dialog?.dismiss()
        dialog = AlertDialog.Builder(context, R.style.OriginalTheme_Dialog_Alert)
            .setIcon(R.drawable.ic_baseline_dashboard_customize_24)
            .setTitle(context.getString(R.string.replace_info_block, block.label))
            .setItems(items.toTypedArray()) { _, which ->
                val latest = config.viewBlocks.toList()
                if (latest.getOrNull(block.slot) == block.selectionKey) {
                    config.viewBlocks = DashboardCatalogue.replace(latest, block.slot, items[which]).toTypedArray()
                }
            }
            .setCancelable(true).create()
        dialog?.show()
        dialog?.setBlackIcon()
    }

    fun dispose() { active = false; dialog?.dismiss(); dialog = null }

    companion object {
        private const val STATIONARY_SPEED_KMH = 1f

        fun toggleLight(session: BleSessionViewModel, config: AppConfig) {
            val on = session.isCommandValueSupported(CommandType.LIGHT_ON.name, CommandValue.None)
            val off = session.isCommandValueSupported(CommandType.LIGHT_OFF.name, CommandValue.None)
            if (!on && !off) return
            val enabled = session.sessionState.value.lightMode?.let { it != 1 } ?: config.lightEnabled
            val command = when {
                enabled && off -> CommandType.LIGHT_OFF
                !enabled && on -> CommandType.LIGHT_ON
                on -> CommandType.LIGHT_ON
                else -> CommandType.LIGHT_OFF
            }
            config.lightEnabled = command == CommandType.LIGHT_ON
            session.sendCommand(command.name)
        }
    }
}

class DashboardGestures(
    context: Context,
    private val state: () -> DashboardUiState,
    private val geometry: () -> DashboardGeometry?,
    private val single: () -> Unit,
    private val double: () -> Unit,
    private val long: (DashboardBlock, DashboardUiState) -> Unit
) {
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            single()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { double(); return true }
        override fun onLongPress(e: MotionEvent) {
            val data = state()
            data.infoBlocks.getOrNull(geometry()?.blockAt(e.x, e.y) ?: -1)?.let { long(it, data) }
        }
    })

    fun touch(event: MotionEvent): Boolean { detector.onTouchEvent(event); return true }

    fun cancel() {
        val now = android.os.SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).also {
            detector.onTouchEvent(it)
            it.recycle()
        }
    }
}
