package io.github.tuxwheel.utils

import android.content.Context
import android.media.AudioManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.media.VolumeProviderCompat
import io.github.tuxwheel.feature.remotebutton.RemoteButtonDispatcher

/**
 * Holder of the media session that keeps Bluetooth HID buttons (volume keys, media keys)
 * flowing to [RemoteButtonDispatcher] while TuxWheel is not the foreground app or the screen
 * is off. The volume provider consumes the volume so the system stream never changes.
 */
class VolumeKeyController(
    private val mContext: Context,
    private val remoteButtonDispatcher: RemoteButtonDispatcher,
) {
    private var mMediaSession: MediaSessionCompat? = null

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
                    if (keyEvent.action == KeyEvent.ACTION_DOWN &&
                        remoteButtonDispatcher.handleKey(keyEvent.keyCode)
                    ) return true
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
                            if (remoteButtonDispatcher.handleKey(KeyEvent.KEYCODE_VOLUME_UP)) return
                        }
                        AudioManager.ADJUST_LOWER -> {
                            if (remoteButtonDispatcher.handleKey(KeyEvent.KEYCODE_VOLUME_DOWN)) return
                        }
                        else -> {}
                    }
                    if (direction == 0) {
                        SomeUtil.playBeep()
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
