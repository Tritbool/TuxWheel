package io.github.tuxwheel

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.github.tuxwheel.compose.ScanScreen
import io.github.tuxwheel.databinding.ActivityScanBinding
import io.github.tuxwheel.scan.ScanUiState
import io.github.tuxwheel.ui.theme.AppTheme

internal class ScanPageRenderer(
    private val binding: ActivityScanBinding,
    initial: ScanUiState,
    private val onSelect: (String) -> Unit,
    private val onForceProtocol: (String) -> Unit,
    private val onAddressChanged: (String) -> Unit,
    private val onManualSelect: () -> Unit
) {
    private val state = mutableStateOf(initial)
    private val scroll = LazyListState()
    private var disposed = false
    private var running = true

    init {
        binding.root.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        render(initial)
    }

    fun render(data: ScanUiState) {
        if (disposed) return
        state.value = data
        if (running && !binding.root.hasComposition) {
            binding.root.setContent {
                AppTheme {
                    ScanScreen(state.value, scroll, onSelect, onForceProtocol, onAddressChanged, onManualSelect)
                }
            }
        }
    }

    fun stop() {
        running = false
        binding.root.disposeComposition()
    }

    fun start(data: ScanUiState) {
        if (disposed) return
        running = true
        render(data)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        stop()
    }
}
