package io.github.tuxwheel.telemetry

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.preference.PreferenceManager
import io.github.tuxwheel.AppConfig
import io.github.tuxwheel.MainActivity
import io.github.tuxwheel.MainPageAdapter
import io.github.tuxwheel.R
import io.github.tuxwheel.ble.BleSessionViewModel
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class TelemetryRendererSwitchTest {
    @Test
    fun `bound params page ignores renderer preferences and catches up after lifecycle restart`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.setTheme(R.style.OriginalTheme)
        val config = AppConfig(context)
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean("use_compose_telemetry", false).commit()
        val model = mockk<BleSessionViewModel>(relaxed = true)
        val activity = mockk<MainActivity>(relaxed = true)
        val lifecycle = LifecycleRegistry(activity)
        every { activity.lifecycle } returns lifecycle
        startKoin { modules(module {
            single { config }
            single { model }
        }) }
        val recycler = RecyclerView(context)
        val adapter = MainPageAdapter(mutableListOf(R.layout.main_view_params_list), activity)
        try {
            lifecycle.currentState = Lifecycle.State.CREATED
            val holder = adapter.createViewHolder(recycler, R.layout.main_view_params_list)
            adapter.bindViewHolder(holder, 0)
            val page = holder.itemView
            val compose = page.findViewById<ComposeView>(R.id.paramsComposeView)
            assertThat(compose.visibility).isEqualTo(View.VISIBLE)
            assertThat((page as android.view.ViewGroup).childCount).isEqualTo(1)

            adapter.onAttachedToRecyclerView(recycler)
            lifecycle.currentState = Lifecycle.State.STARTED
            shadowOf(Looper.getMainLooper()).idle()
            config.setValue("use_compose_telemetry", true)
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(compose.visibility).isEqualTo(View.VISIBLE)
            assertThat(holder.itemView).isSameInstanceAs(page)
            assertThat(adapter.itemCount).isEqualTo(1)

            lifecycle.currentState = Lifecycle.State.CREATED
            shadowOf(Looper.getMainLooper()).idle()
            config.setValue("use_compose_telemetry", false)
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(compose.visibility).isEqualTo(View.VISIBLE)
            lifecycle.currentState = Lifecycle.State.STARTED
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(compose.visibility).isEqualTo(View.VISIBLE)

            adapter.onDetachedFromRecyclerView(recycler)
            shadowOf(Looper.getMainLooper()).idle()
            adapter.onAttachedToRecyclerView(recycler)
            shadowOf(Looper.getMainLooper()).idle()
            adapter.onViewRecycled(holder)
            assertThat(compose.hasComposition).isFalse()
        } finally {
            adapter.onDetachedFromRecyclerView(recycler)
            lifecycle.currentState = Lifecycle.State.DESTROYED
            shadowOf(Looper.getMainLooper()).idle()
            stopKoin()
        }
    }
}
