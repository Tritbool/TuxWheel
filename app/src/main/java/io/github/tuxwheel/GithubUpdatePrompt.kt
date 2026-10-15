package io.github.tuxwheel

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class GithubUpdatePrompt(
    private val activity: ComponentActivity,
    private val allowed: () -> Boolean = { GithubUpdateChannel.allowed(activity) },
    private val check: suspend () -> GithubRelease? = {
        GithubUpdateChecker(
            activity.getSharedPreferences("github_updates", Context.MODE_PRIVATE),
            BuildConfig.VERSION_NAME
        ).check()
    }
) : DefaultLifecycleObserver {
    private var pending: GithubRelease? = null
    private var dialog: AlertDialog? = null
    private var job: Job? = null

    fun start() {
        if (!allowed()) return
        activity.lifecycle.addObserver(this)
        job = activity.lifecycleScope.launch {
            pending = check()
            refreshVisibility()
        }
    }

    override fun onResume(owner: LifecycleOwner) = refreshVisibility()

    override fun onPause(owner: LifecycleOwner) {
        dismiss()
    }

    fun refreshVisibility() {
        val visible = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            !activity.isFinishing && !activity.isDestroyed &&
            !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode)
        if (!visible) {
            dismiss()
            return
        }
        val release = pending ?: return
        if (dialog != null || !GithubReleases.safeApkUrl(release.apkUrl, release.tag)) return
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_available)
            .setMessage(activity.getString(R.string.update_message, release.tag))
            .setPositiveButton(R.string.update_download) { _, _ ->
                pending = null
                try {
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl))
                            .addCategory(Intent.CATEGORY_BROWSABLE)
                    )
                } catch (_: ActivityNotFoundException) {
                    // No browser is installed; updates are optional.
                } catch (_: SecurityException) {
                    // A device policy may prohibit opening external links.
                }
            }
            .setNegativeButton(R.string.update_later) { _, _ -> pending = null }
            .setOnCancelListener { pending = null }
            .create()
            .also { shown ->
                shown.setOnDismissListener { if (dialog === shown) dialog = null }
                shown.show()
            }
    }

    private fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    override fun onDestroy(owner: LifecycleOwner) {
        job?.cancel()
        job = null
        pending = null
        dismiss()
        activity.lifecycle.removeObserver(this)
    }
}
