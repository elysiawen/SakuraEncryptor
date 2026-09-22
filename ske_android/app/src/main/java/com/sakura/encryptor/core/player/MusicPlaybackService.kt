package com.sakura.encryptor.core.player

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.sakura.encryptor.SakuraApp

/**
 * Owns the music player.
 *
 * The player used to live inside the music screen, built by `remember { }` —
 * which meant leaving the screen released it, so audio could never outlive the
 * UI. Holding it in a [MediaSessionService] instead buys background playback, a
 * media notification, lock-screen controls and headset buttons, all of which
 * media3 derives from the session.
 *
 * Video deliberately does not get this treatment: pausing it when the user
 * leaves is the correct behaviour, and it keeps this service narrow.
 */
class MusicPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val container = (application as SakuraApp).container

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                ProgressiveMediaSource.Factory(
                    // Cloud entries sit in the queue as placeholders until the
                    // session actually reads them; see [QueueUriResolver].
                    ResolvingDataSource.Factory(
                        container.skeDataSourceFactory,
                        container.queueUriResolver,
                    )
                )
            )
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Tapping the media notification (or the lock-screen widget) opens this.
        // Without it the notification renders fine but the tap does nothing —
        // exactly the "点不动" symptom.
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, com.sakura.encryptor.MainActivity::class.java)
        val sessionActivity = android.app.PendingIntent.getActivity(
            this,
            /* requestCode = */ 0,
            launchIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(object : MediaSession.Callback {
                /**
                 * Items come from this app's own UI, so they are taken as-is.
                 * Without this override the session rejects every setMediaItems
                 * call and the queue can never be built.
                 */
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: List<MediaItem>,
                ): ListenableFuture<List<MediaItem>> = Futures.immediateFuture(mediaItems)
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * Swiping the app away stops playback when nothing is playing, but leaves a
     * running track alone — outliving the UI is the whole point of the service.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player ?: return
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
