package io.github.tuxwheel

import android.app.AlertDialog
import android.app.Application
import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Looper
import androidx.activity.ComponentActivity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class GithubUpdatePromptTest {
    private val release = GithubRelease(
        "v2", "https://github.com/Tritbool/TuxWheel/releases/download/v2/TuxWheel.apk"
    )

    @Test fun `background result waits for resume and Later dismisses until next check`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val result = CompletableDeferred<GithubRelease?>()
        val prompt = GithubUpdatePrompt(activity, { true }, { result.await() })
        prompt.start()
        controller.pause().stop()
        result.complete(release)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(ShadowAlertDialog.getLatestAlertDialog()).isNull()
        controller.start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertThat(dialog.isShowing).isTrue()
        assertThat(shadowOf(dialog).title.toString()).isEqualTo("New version available")
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        prompt.refreshVisibility()
        assertThat(dialog.isShowing).isFalse()
        controller.pause().stop().destroy()
    }

    @Test fun `Download opens only browsable HTTPS URL and popup is cleaned on pause`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val prompt = GithubUpdatePrompt(activity, { true }, { release })
        prompt.start()
        shadowOf(Looper.getMainLooper()).idle()
        val first = ShadowAlertDialog.getLatestAlertDialog()
        controller.pause()
        assertThat(first.isShowing).isFalse()
        controller.resume()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val intent = shadowOf(activity).nextStartedActivity
        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.categories).contains(Intent.CATEGORY_BROWSABLE)
        assertThat(intent.data.toString()).isEqualTo(release.apkUrl)
        controller.pause().stop().destroy()
        assertThat(dialog.isShowing).isFalse()
    }

    @Test fun `disabled channel never starts request and destroy cancels pending check`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        var calls = 0
        GithubUpdatePrompt(activity, { false }, { calls++; release }).start()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(calls).isEqualTo(0)
        val result = CompletableDeferred<GithubRelease?>()
        var cancelled = false
        GithubUpdatePrompt(activity, { true }, {
            try { result.await() } finally { cancelled = true }
        }).start()
        shadowOf(Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(cancelled).isTrue()
        result.complete(release)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(ShadowAlertDialog.getLatestAlertDialog()).isNull()
    }

    @Test fun `PiP hides an existing popup and suppresses it even while resumed`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val prompt = GithubUpdatePrompt(activity, { true }, { release })
        prompt.start()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertThat(dialog.isShowing).isTrue()
        activity.enterPictureInPictureMode(PictureInPictureParams.Builder().build())
        prompt.refreshVisibility()
        assertThat(dialog.isShowing).isFalse()
        shadowOf(Looper.getMainLooper()).idle()
        prompt.refreshVisibility()
        assertThat(ShadowAlertDialog.getLatestAlertDialog()).isSameInstanceAs(dialog)
        assertThat(dialog.isShowing).isFalse()
        controller.pause().stop().destroy()
    }
}
