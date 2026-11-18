package io.github.tuxwheel

import android.view.View
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.tuxwheel.ble.BleSessionViewModel
import io.github.tuxwheel.bms.BmsMapper
import io.github.tuxwheel.compose.SmartBmsScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal class BmsScroll {
    val compose = ScrollState(0)
}

internal class BmsPageRenderer(
    page: View,
    private val session: BleSessionViewModel,
    private val config: AppConfig,
    private val scroll: BmsScroll,
    private val owner: LifecycleOwner
) {
    private val compose = page.findViewById<ComposeView>(R.id.bmsComposeView)
    private val presentation = mutableStateOf(BmsMapper.present(session.bmsDisplay.value))
    private var collecting: Job? = null
    private var disposed = false

    init {
        compose.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        compose.setContent { SmartBmsScreen(presentation.value, scroll.compose) }
    }

    fun refresh() {
        if (!disposed) presentation.value = BmsMapper.present(session.bmsDisplay.value)
    }

    fun start() {
        if (disposed || collecting?.isActive == true) return
        collecting = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.bmsDisplay.collect { snapshot ->
                    presentation.value = BmsMapper.present(snapshot)
                }
            }
        }
    }

    fun stop() {
        collecting?.cancel()
        collecting = null
    }

    fun dispose() {
        if (disposed) return
        stop()
        disposed = true
        compose.disposeComposition()
    }
}
