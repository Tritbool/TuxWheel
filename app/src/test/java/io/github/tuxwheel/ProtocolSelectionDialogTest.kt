package io.github.tuxwheel

import android.app.AlertDialog
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.common.truth.Truth.assertThat
import io.github.tritbool.euc.ble.protocols.EUCProtocol
import io.github.tuxwheel.ble.BleSessionState
import io.github.tuxwheel.ble.BleSessionViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ProtocolSelectionDialogTest {
    private lateinit var session: BleSessionViewModel
    private lateinit var activity: MainActivity
    private var dialog: AlertDialog? = null
    private val protocols = listOf("KingSong", "Begode").map { manufacturer ->
        mockk<EUCProtocol>(relaxed = true).also {
            every { it.manufacturer } returns manufacturer
        }
    }

    @Before fun setup() {
        session = mockk(relaxed = true)
        every { session.getAvailableProtocols() } returns protocols
        val config = mockk<AppConfig>(relaxed = true)
        every { config.useEng } returns false
        startKoin { modules(module { single { session }; single { config } }) }
        activity = Robolectric.buildActivity(MainActivity::class.java).get()
    }

    @After fun cleanup() {
        dialog?.dismiss()
        stopKoin()
    }

    private fun show(candidates: List<EUCProtocol> = protocols): AlertDialog {
        MainActivity::class.java.getDeclaredMethod(
            "showProtocolSelectionDialog", BleSessionState::class.java
        ).apply { isAccessible = true }.invoke(
            activity, BleSessionState.EMPTY.copy(protocolCandidates = candidates)
        )
        return (MainActivity::class.java.getDeclaredField("protocolSelectionDialog")
            .apply { isAccessible = true }.get(activity) as AlertDialog).also { dialog = it }
    }

    private fun texts(view: View): List<TextView> =
        if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else listOfNotNull(view as? TextView)

    @Test fun `explanation and selectable protocols are visible together`() {
        val shown = show()
        val visibleText = texts(shown.window!!.decorView).filter { it.isShown }.map { it.text.toString() }
        assertThat(visibleText).contains(activity.getString(R.string.protocol_select_title))
        assertThat(visibleText).contains(activity.getString(R.string.protocol_auto_detection_failed))
        assertThat(shown.listView.isShown).isTrue()
        assertThat(shown.listView.adapter.count).isEqualTo(protocols.size)
        val selected = shown.listView.adapter.getView(1, null, shown.listView)
        shown.listView.performItemClick(selected, 1, 1L)
        verify(exactly = 1) { session.selectProtocol(protocols[1].javaClass.simpleName) }
        assertThat(shown.isShowing).isFalse()
    }

    @Test fun `missing candidates fall back to available protocols`() {
        val shown = show(emptyList())
        verify(exactly = 1) { session.getAvailableProtocols() }
        assertThat(shown.listView.isShown).isTrue()
        assertThat(shown.listView.adapter.count).isEqualTo(protocols.size)
    }

    @Test fun `cancel dismisses protocol selection without selecting a protocol`() {
        val shown = show()
        shown.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        verify(exactly = 1) { session.dismissProtocolSelection() }
        verify(exactly = 0) { session.selectProtocol(any()) }
        assertThat(shown.isShowing).isFalse()
    }
}
