package io.github.tuxwheel.utils

import android.content.Context
import android.media.AudioManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.media.VolumeProviderCompat
import io.github.tuxwheel.AppConfig
import io.github.tuxwheel.feature.remotebutton.RemoteButtonDispatcher

/**
 * Holder of the media session that keeps Bluetooth HID buttons (volume keys, media keys)
 * flowing to [RemoteButtonDispatcher] while TuxWheel is not the foreground app or the screen
 * is off. The volume provider consumes the volume so the system stream never changes while a
 * remote button is serviced; when no captured remote is connected and the legacy
 * beep-on-volume-up option is off, the press falls through to a normal system volume change.
 */
class VolumeKeyController(
    private val mContext: Context,
    private val remoteButtonDispatcher: RemoteButtonDispatcher,
    private val config: AppConfig,
) {
    private var mMediaSession: MediaSessionCompat? = null
    private var lastKeyConsumed = false

    private fun createMediaSession(): MediaSessionCompat? {
        mMediaSession = MediaSessionCompat(mContext, "log").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            val state = PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f)
                .build()
            setPlaybackState(state)
            setCallback(object : MediaSessionCompat.Callback() {
                @Suppress("DEPRECATION")
                override fun onMediaButtonEvent(mediaButtonIntent: android.content.Intent): Boolean {
                    val keyEvent = mediaButtonIntent.getParcelableExtra<KeyEvent>(
                        android.content.Intent.EXTRA_KEY_EVENT
                    ) ?: return super.onMediaButtonEvent(mediaButtonIntent)
                    if (keyEvent.action == KeyEvent.ACTION_DOWN) {
                        if (remoteButtonDispatcher.handleKey(keyEvent.keyCode, keyEvent.device?.descriptor)) {
                            lastKeyConsumed = true
                            return true
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
            })
            setPlaybackToRemote(volumeProvider)
        }
        return mMediaSession
    }

    private val volumeProvider: VolumeProviderCompat
        get() {
            val audio = mContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val streamType = AudioManager.STREAM_MUSIC
            val currentVolume = audio.getStreamVolume(streamType)
            val maxVolume = audio.getStreamMaxVolume(streamType)
            return object : VolumeProviderCompat(VOLUME_CONTROL_RELATIVE, maxVolume, currentVolume) {
                override fun onAdjustVolume(direction: Int) {
                    when (direction) {
                        AudioManager.ADJUST_RAISE -> {
                            lastKeyConsumed =
                                remoteButtonDispatcher.handleKey(KeyEvent.KEYCODE_VOLUME_UP)
                            if (lastKeyConsumed) return
                        }
                        AudioManager.ADJUST_LOWER -> {
                            lastKeyConsumed =
                                remoteButtonDispatcher.handleKey(KeyEvent.KEYCODE_VOLUME_DOWN)
                            if (lastKeyConsumed) return
                        }
                        else -> {
                            if (!lastKeyConsumed && config.useBeepOnVolumeUp) {
                                SomeUtil.playBeep()
                            }
                            lastKeyConsumed = false
                            return
                        }
                    }
                    lastKeyConsumed = false
                    if (!config.useBeepOnVolumeUp &&
                        !remoteButtonDispatcher.hasConnectedCaptureDevice()
                    ) {
                        audio.adjustStreamVolume(
                            streamType, direction, AudioManager.FLAG_SHOW_UI
                        )
                        setCurrentVolume(audio.getStreamVolume(streamType))
                    }
                }
            }
        }

    fun setActive(active: Boolean) {
        (mMediaSession ?: createMediaSession())?.isActive = active
    }

    fun destroy() {
        mMediaSession?.release()
    }
}
