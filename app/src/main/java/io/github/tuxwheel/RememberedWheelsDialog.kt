package io.github.tuxwheel

import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.tritbool.euc.ble.core.BLEConstants
import io.github.tuxwheel.ble.BleSessionState
import io.github.tuxwheel.ble.RememberedWheel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal class RememberedWheelsDialog(
    private val context: Context,
    private val config: AppConfig,
) {
    fun show(
        scope: CoroutineScope,
        session: StateFlow<BleSessionState>,
        onDisconnect: () -> Unit,
        onConnect: (RememberedWheel) -> Unit,
        onScan: () -> Unit,
    ) {
        fun reopen() = show(scope, session, onDisconnect, onConnect, onScan)
        var state = Connection.from(session.value)
        var dragging = false
        val builder = AlertDialog.Builder(context)
        val dialogContext = builder.context
        val content = LinearLayout(dialogContext).apply { orientation = LinearLayout.VERTICAL }
        val hint = TextView(dialogContext).apply {
            setPadding(dp(24), dp(12), dp(24), dp(8))
        }
        val priorityWait = Button(dialogContext, null, android.R.attr.borderlessButtonStyle).apply {
            isAllCaps = false
        }
        fun refreshPriorityWait() {
            priorityWait.text = context.getString(R.string.wheels_priority_wait_value, config.wheelPriorityWaitSeconds)
        }
        priorityWait.setOnClickListener {
            val seconds = 0..AppConfig.AUTO_CONNECT_SCAN_SECONDS
            val choices = seconds.map {
                if (it == 0) context.getString(R.string.wheels_priority_wait_immediate)
                else context.getString(R.string.wheels_priority_wait_seconds, it)
            }.toTypedArray()
            AlertDialog.Builder(context)
                .setTitle(R.string.wheels_priority_wait_title)
                .setSingleChoiceItems(choices, config.wheelPriorityWaitSeconds) { settings, index ->
                    config.wheelPriorityWaitSeconds = index
                    refreshPriorityWait()
                    settings.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        refreshPriorityWait()
        val list = RecyclerView(dialogContext).apply {
            layoutManager = LinearLayoutManager(dialogContext)
        }
        content.addView(priorityWait)
        content.addView(hint)
        content.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        val dialog = builder
            .setTitle(R.string.wheels_priority_title)
            .setView(content)
            .setPositiveButton(R.string.wheels_done, null)
            .setNegativeButton(R.string.wheels_add) { _, _ ->
                if (Connection.from(session.value).busy) showManualAdd(::reopen)
                else showAddChooser(onScan, ::reopen)
            }
            .setNeutralButton(R.string.disconnect_from_wheel, null)
            .create()
        val adapter = WheelAdapter(
            connection = { state },
            onAction = { wheel ->
                val current = Connection.from(session.value)
                if (current.isConnected(wheel)) onDisconnect()
                else if (!current.busy) onConnect(wheel)
            },
            onOptions = { wheel, refresh -> showOptions(wheel, refresh) },
        )
        list.adapter = adapter
        fun refresh() {
            if (dragging || !dialog.isShowing) return
            adapter.reload()
            hint.setText(if (adapter.wheels.isEmpty()) R.string.wheels_empty else R.string.wheels_drag_hint)
            // Connecting/retrying (or a forgotten connected wheel) still needs a way to stop.
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).visibility =
                if (state.busy && adapter.wheels.none(state::isConnected)) View.VISIBLE else View.GONE
        }
        adapter.refresh = ::refresh
        var orderChanged = false
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from !in adapter.wheels.indices || to !in adapter.wheels.indices || from == to) return false
                adapter.wheels.add(to, adapter.wheels.removeAt(from))
                adapter.notifyItemMoved(from, to)
                orderChanged = true
                return true
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    dragging = true
                } else if (dragging && actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
                    dragging = false
                    if (orderChanged) {
                        // Keep any BLE name learned while the row was being dragged.
                        val latest = config.rememberedWheels.associateBy { it.address }.toMutableMap()
                        config.rememberedWheels = adapter.wheels.mapNotNull { latest.remove(it.address) } + latest.values
                        orderChanged = false
                    }
                    list.post { refresh() }
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        }).attachToRecyclerView(list)
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            if (Connection.from(session.value).busy) onDisconnect()
        }
        refresh()
        val observer = scope.launch {
            session.map { Connection.from(it) }.distinctUntilChanged().collect {
                state = it
                refresh()
            }
        }
        dialog.setOnDismissListener { observer.cancel() }
    }

    private data class Connection(val busy: Boolean, val connectedAddress: String) {
        fun isConnected(wheel: RememberedWheel) = wheel.address.equals(connectedAddress, ignoreCase = true)

        companion object {
            fun from(state: BleSessionState) = Connection(
                state.connectionState != BLEConstants.ConnectionState.DISCONNECTED || state.isReconnecting,
                if (state.isConnected) state.deviceAddress else "",
            )
        }
    }

    private inner class WheelAdapter(
        private val connection: () -> Connection,
        private val onAction: (RememberedWheel) -> Unit,
        private val onOptions: (RememberedWheel, () -> Unit) -> Unit,
    ) : RecyclerView.Adapter<WheelRow>() {
        val wheels = mutableListOf<RememberedWheel>()
        lateinit var refresh: () -> Unit
        private var nameCounts = emptyMap<String, Int>()

        fun reload() {
            wheels.clear()
            wheels.addAll(config.rememberedWheels)
            nameCounts = wheels.groupingBy { it.displayName }.eachCount()
            notifyDataSetChanged()
        }

        override fun getItemCount() = wheels.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WheelRow {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPaddingRelative(dp(24), 0, dp(12), 0)
                setBackgroundResource(selectableBackground(context))
            }
            val label = TextView(parent.context).apply {
                setPaddingRelative(0, dp(12), dp(8), dp(12))
            }
            val action = ImageButton(parent.context).apply {
                setBackgroundResource(selectableBackground(context))
                scaleType = android.widget.ImageView.ScaleType.CENTER
            }
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(action, LinearLayout.LayoutParams(dp(48), dp(48)))
            return WheelRow(row, label, action).also { holder ->
                row.setOnClickListener {
                    wheels.getOrNull(holder.bindingAdapterPosition)?.let { onOptions(it, refresh) }
                }
                action.setOnClickListener {
                    wheels.getOrNull(holder.bindingAdapterPosition)?.let(onAction)
                }
            }
        }

        override fun onBindViewHolder(holder: WheelRow, position: Int) {
            val wheel = wheels[position]
            val state = connection()
            val connected = state.isConnected(wheel)
            val name = if ((nameCounts[wheel.displayName] ?: 0) > 1) {
                context.getString(R.string.wheels_name_with_address, wheel.displayName, wheel.address)
            } else wheel.displayName
            holder.label.text = if (connected) context.getString(R.string.wheels_connected_item, name) else name
            holder.action.apply {
                isEnabled = connected || !state.busy
                alpha = if (isEnabled) 1f else 0.4f
                setImageResource(if (connected) R.drawable.ic_wheel_stop else R.drawable.ic_wheel_play)
                imageTintList = ColorStateList.valueOf(Color.parseColor(if (connected) "#F44336" else "#4CAF50"))
                contentDescription = context.getString(
                    if (connected) R.string.wheels_disconnect_named else R.string.wheels_connect_named, name
                )
            }
        }
    }

    private class WheelRow(view: View, val label: TextView, val action: ImageButton) : RecyclerView.ViewHolder(view)

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun selectableBackground(context: Context) = TypedValue().apply {
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, this, true)
    }.resourceId

    private fun showOptions(wheel: RememberedWheel, refresh: () -> Unit) {
        fun update(change: MutableList<RememberedWheel>.(Int) -> Unit) {
            val current = config.rememberedWheels.toMutableList()
            val index = current.indexOfFirst { it.address == wheel.address }
            if (index < 0) return
            current.change(index)
            config.rememberedWheels = current
            refresh()
        }
        val wheels = config.rememberedWheels
        val index = wheels.indexOfFirst { it.address == wheel.address }
        if (index < 0) return
        val actions = buildList<Pair<Int, () -> Unit>> {
            if (index > 0) add(R.string.wheels_move_up to { update { if (it > 0) add(it - 1, removeAt(it)) } })
            if (index < wheels.lastIndex) {
                add(R.string.wheels_move_down to { update { if (it < lastIndex) add(it + 1, removeAt(it)) } })
            }
            add(R.string.wheels_rename to {
                val builder = AlertDialog.Builder(context)
                val input = EditText(builder.context).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    setSingleLine()
                    setText(wheel.displayName)
                    selectAll()
                }
                builder
                    .setTitle(R.string.wheels_rename)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val name = input.text.toString().trim()
                        update { this[it] = this[it].copy(alias = if (name == wheel.name) "" else name) }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            })
            add(R.string.wheels_forget to {
                AlertDialog.Builder(context)
                    .setMessage(context.getString(R.string.wheels_forget_confirm, wheel.displayName))
                    .setPositiveButton(R.string.wheels_forget) { _, _ -> update { removeAt(it) } }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            })
        }
        AlertDialog.Builder(context)
            .setTitle(wheel.displayName)
            .setItems(actions.map { context.getString(it.first) }.toTypedArray()) { _, action ->
                actions[action].second()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddChooser(onScan: () -> Unit, refresh: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(R.string.wheels_add)
            .setItems(
                arrayOf(context.getString(R.string.wheels_add_scan), context.getString(R.string.wheels_add_manual))
            ) { _, which -> if (which == 0) onScan() else showManualAdd(refresh) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> refresh() }
            .setOnCancelListener { refresh() }
            .show()
    }

    private fun showManualAdd(refresh: () -> Unit) {
        val builder = AlertDialog.Builder(context, R.style.OriginalTheme_Dialog_Alert)
        val addressInput = EditText(builder.context).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textDirection = View.TEXT_DIRECTION_LTR
            hint = "AA:BB:CC:DD:EE:FF"
            filters = arrayOf(InputFilter { source, start, end, dest, dstart, dend ->
                val candidate = dest.replaceRange(dstart, dend, source.subSequence(start, end))
                if (RememberedWheel.formatAddressInput(candidate) == null) {
                    error = context.getString(R.string.wheels_invalid_address)
                    dest.subSequence(dstart, dend)
                } else {
                    null
                }
            })
            doAfterTextChanged { text ->
                if (text == null) return@doAfterTextChanged
                val formatted = RememberedWheel.formatAddressInput(text) ?: return@doAfterTextChanged
                if (formatted.contentEquals(text)) return@doAfterTextChanged
                // Preserve the cursor's digit position when separators move.
                val digitsBeforeCursor = text.take(selectionStart.coerceIn(0, text.length))
                    .count { it != ':' && it != '-' }
                val cursor = if (digitsBeforeCursor == 0) 0 else digitsBeforeCursor + (digitsBeforeCursor - 1) / 2
                text.replace(0, text.length, formatted)
                setSelection(cursor.coerceAtMost(formatted.length))
            }
        }
        val nameInput = EditText(builder.context).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHint(R.string.wheels_manual_name_hint)
        }
        val form = LinearLayout(builder.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(addressInput)
            addView(nameInput)
        }
        val dialog = builder
            .setTitle(R.string.wheels_add_manual)
            .setView(form)
            .setPositiveButton(R.string.wheels_add_confirm, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> refresh() }
            .setOnCancelListener { refresh() }
            .show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            when (config.addManualWheel(addressInput.text.toString(), nameInput.text.toString())) {
                AppConfig.ManualWheelResult.ADDED -> {
                    dialog.dismiss()
                    refresh()
                }
                AppConfig.ManualWheelResult.INVALID_ADDRESS -> {
                    addressInput.requestFocus()
                    addressInput.error = context.getString(R.string.wheels_invalid_address)
                }
                AppConfig.ManualWheelResult.ALREADY_SAVED -> {
                    addressInput.requestFocus()
                    addressInput.error = context.getString(R.string.wheels_already_saved)
                }
            }
        }
    }
}
