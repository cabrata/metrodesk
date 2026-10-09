package com.utaloom.android

import android.content.Intent
import androidx.media3.common.C
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Timeline
import androidx.media3.common.Player as Media3Player
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.utaloom.data.Stores
import com.utaloom.playback.AndroidAudioEngine
import com.utaloom.playback.Player
import com.utaloom.playback.RepeatMode
import com.utaloom.playback.toMediaItem
import com.utaloom.together.ListenTogether
import com.utaloom.together.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        check(Player.init()) { "Cannot initialize Android audio" }
        val engine = checkNotNull(AndroidAudioEngine.current)
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) })
        // Route lock-screen/headset actions through the same queue and Listen Together guards as the UI.
        val transport = object : ForwardingPlayer(engine.exo) {
            private val forwarded = mutableMapOf<Media3Player.Listener, Media3Player.Listener>()
            private var publishedState = Player.state.value
            private var publishedCommands: Media3Player.Commands? = null
            private val canControl get() = !Player.remoteControlled && ListenTogether.state.value.role != Role.GUEST
            override fun addListener(listener: Media3Player.Listener) {
                if (listener in forwarded) return
                val owner = this
                // Kotlin `by` skips Java default methods, so a Proxy is needed to forward every other event.
                val wrapped = java.lang.reflect.Proxy.newProxyInstance(listener.javaClass.classLoader, arrayOf(Media3Player.Listener::class.java)) { proxy, method, args ->
                    when (method.name) {
                        "onAvailableCommandsChanged" -> listener.onAvailableCommandsChanged(owner.availableCommands)
                        "onEvents" -> listener.onEvents(owner, args!![1] as Media3Player.Events)
                        "onTimelineChanged" -> listener.onTimelineChanged(owner.currentTimeline, args!![1] as Int)
                        "onMediaItemTransition" -> listener.onMediaItemTransition(owner.currentMediaItem, args!![1] as Int)
                        "onMediaMetadataChanged" -> listener.onMediaMetadataChanged(owner.mediaMetadata)
                        "onPositionDiscontinuity" -> if (args?.size == 3) listener.onPositionDiscontinuity(
                            queuePosition(args[0] as Media3Player.PositionInfo), queuePosition(args[1] as Media3Player.PositionInfo), args[2] as Int,
                        ) else method.invoke(listener, *(args ?: emptyArray()))
                        "equals" -> args?.get(0) === proxy
                        "hashCode" -> System.identityHashCode(proxy)
                        "toString" -> listener.toString()
                        else -> method.invoke(listener, *(args ?: emptyArray()))
                    }
                } as Media3Player.Listener
                forwarded[listener] = wrapped
                super.addListener(wrapped)
            }
            override fun removeListener(listener: Media3Player.Listener) {
                forwarded.remove(listener)?.let { super.removeListener(it) }
            }
            override fun play() = Player.play()
            override fun pause() = Player.pause()
            override fun stop() = Player.stop()
            override fun setPlayWhenReady(playWhenReady: Boolean) { if (playWhenReady) Player.play() else Player.pause() }
            override fun seekTo(positionMs: Long) = Player.seekTo(if (positionMs == C.TIME_UNSET) 0 else positionMs)
            override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
                if (mediaItemIndex !in Player.state.value.queue.indices) return
                if (mediaItemIndex != Player.state.value.index) Player.skipTo(mediaItemIndex)
                seekTo(positionMs)
            }
            override fun seekToNext() = Player.next()
            override fun seekToNextMediaItem() = Player.next()
            override fun seekToPrevious() = Player.previous()
            override fun seekToPreviousMediaItem() = Player.previous()
            override fun getAvailableCommands(): Media3Player.Commands {
                val active = canControl && Player.state.value.current != null
                return Media3Player.Commands.Builder().addAll(
                    Media3Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Media3Player.COMMAND_GET_TIMELINE,
                    Media3Player.COMMAND_GET_METADATA, Media3Player.COMMAND_GET_AUDIO_ATTRIBUTES, Media3Player.COMMAND_GET_VOLUME,
                ).addIf(Media3Player.COMMAND_PLAY_PAUSE, active).addIf(Media3Player.COMMAND_STOP, active)
                    .addIf(Media3Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, active && isCurrentMediaItemSeekable)
                    .addIf(Media3Player.COMMAND_SEEK_TO_PREVIOUS, active)
                    .addIf(Media3Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, active && hasPreviousMediaItem())
                    .addIf(Media3Player.COMMAND_SEEK_TO_NEXT, active && hasNextMediaItem())
                    .addIf(Media3Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, active && hasNextMediaItem()).build()
            }
            override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
            override fun getCurrentMediaItemIndex() = Player.state.value.index.coerceAtLeast(0)
            override fun getCurrentWindowIndex() = currentMediaItemIndex
            override fun getCurrentPeriodIndex() = currentMediaItemIndex
            override fun getMediaItemCount() = Player.state.value.queue.size
            override fun getMediaItemAt(index: Int) = Player.state.value.queue[index].toMediaItem()
            override fun getCurrentMediaItem() = Player.state.value.current?.toMediaItem()
            override fun getMediaMetadata() = currentMediaItem?.mediaMetadata ?: androidx.media3.common.MediaMetadata.EMPTY
            override fun getNextMediaItemIndex(): Int {
                val state = Player.state.value
                return if (state.current == null || !state.hasNext) C.INDEX_UNSET else if (state.index < state.queue.lastIndex) state.index + 1 else 0
            }
            override fun getPreviousMediaItemIndex() = if (Player.state.value.index > 0) Player.state.value.index - 1 else C.INDEX_UNSET
            override fun getNextWindowIndex() = nextMediaItemIndex
            override fun getPreviousWindowIndex() = previousMediaItemIndex
            override fun hasNextMediaItem() = nextMediaItemIndex != C.INDEX_UNSET
            override fun hasPreviousMediaItem() = previousMediaItemIndex != C.INDEX_UNSET
            override fun getRepeatMode() = when (Player.state.value.repeat) {
                RepeatMode.OFF -> Media3Player.REPEAT_MODE_OFF
                RepeatMode.ONE -> Media3Player.REPEAT_MODE_ONE
                RepeatMode.ALL -> Media3Player.REPEAT_MODE_ALL
            }
            override fun getShuffleModeEnabled() = Player.state.value.shuffle
            override fun isCurrentMediaItemSeekable() = engine.exo.currentMediaItem?.mediaId == Player.state.value.current?.id && engine.exo.isCurrentMediaItemSeekable
            override fun getCurrentTimeline(): Timeline {
                // ponytail: read-only projection, not a second queue. Add playback sources only if Player stops owning navigation.
                val state = Player.state.value
                val durationMs = engine.exo.duration.takeIf { engine.exo.currentMediaItem?.mediaId == state.current?.id && it > 0 }
                val seekable = isCurrentMediaItemSeekable
                return object : Timeline() {
                    override fun getWindowCount() = state.queue.size
                    override fun getPeriodCount() = state.queue.size
                    override fun getNextWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean) = super.getNextWindowIndex(
                        windowIndex, if (repeatMode == Media3Player.REPEAT_MODE_ONE) Media3Player.REPEAT_MODE_OFF else repeatMode, false,
                    )
                    override fun getPreviousWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean) = if (windowIndex > 0) windowIndex - 1 else C.INDEX_UNSET
                    private fun durationUs(index: Int): Long = if (index == state.index && durationMs != null) durationMs * 1000
                        else state.queue[index].duration?.takeIf { it > 0 }?.toLong()?.times(1_000_000) ?: C.TIME_UNSET
                    override fun getWindow(index: Int, window: Window, defaultPositionProjectionUs: Long): Window = window.set(
                        index, state.queue[index].toMediaItem(), null, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET,
                        index != state.index || seekable, false, null, 0, durationUs(index), index, index, 0,
                    )
                    override fun getPeriod(index: Int, period: Period, setIds: Boolean): Period = period.set(
                        if (setIds) index else null, if (setIds) index else null, index, durationUs(index), 0,
                    )
                    override fun getIndexOfPeriod(uid: Any) = (uid as? Int)?.takeIf { it in state.queue.indices } ?: C.INDEX_UNSET
                    override fun getUidOfPeriod(index: Int): Any { require(index in state.queue.indices); return index }
                }
            }
            private fun queuePosition(position: Media3Player.PositionInfo): Media3Player.PositionInfo {
                val index = Player.state.value.queue.indexOfFirst { it.id == position.mediaItem?.mediaId }.takeIf { it >= 0 } ?: currentMediaItemIndex
                return Media3Player.PositionInfo(index, index, position.mediaItem, index, index, position.positionMs, position.contentPositionMs, position.adGroupIndex, position.adIndexInAdGroup)
            }
            fun refresh() {
                val state = Player.state.value
                val commands = availableCommands
                val flags = FlagSet.Builder()
                val listeners = forwarded.keys.toList()
                if (publishedState.queue != state.queue || publishedState.index != state.index) {
                    listeners.forEach { it.onTimelineChanged(currentTimeline, Media3Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) }
                    flags.add(Media3Player.EVENT_TIMELINE_CHANGED)
                }
                if (publishedState.current != state.current) {
                    listeners.forEach { it.onMediaItemTransition(currentMediaItem, Media3Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED); it.onMediaMetadataChanged(mediaMetadata) }
                    flags.addAll(Media3Player.EVENT_MEDIA_ITEM_TRANSITION, Media3Player.EVENT_MEDIA_METADATA_CHANGED)
                }
                if (publishedState.repeat != state.repeat) {
                    listeners.forEach { it.onRepeatModeChanged(repeatMode) }
                    flags.add(Media3Player.EVENT_REPEAT_MODE_CHANGED)
                }
                if (publishedState.shuffle != state.shuffle) {
                    listeners.forEach { it.onShuffleModeEnabledChanged(shuffleModeEnabled) }
                    flags.add(Media3Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)
                }
                if (publishedCommands != commands) {
                    listeners.forEach { it.onAvailableCommandsChanged(commands) }
                    flags.add(Media3Player.EVENT_AVAILABLE_COMMANDS_CHANGED)
                }
                publishedState = state
                publishedCommands = commands
                val events = Media3Player.Events(flags.build())
                if (events.size() > 0) listeners.forEach { it.onEvents(this, events) }
            }
        }
        val openApp = android.app.PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, transport).setSessionActivity(openApp).setMediaButtonPreferences(listOf(
            CommandButton.Builder(CommandButton.ICON_PREVIOUS).setPlayerCommand(Media3Player.COMMAND_SEEK_TO_PREVIOUS)
                .setDisplayName(getString(androidx.media3.session.R.string.media3_controls_seek_to_previous_description)).setSlots(CommandButton.SLOT_BACK).build(),
            CommandButton.Builder(CommandButton.ICON_NEXT).setPlayerCommand(Media3Player.COMMAND_SEEK_TO_NEXT)
                .setDisplayName(getString(androidx.media3.session.R.string.media3_controls_seek_to_next_description)).setSlots(CommandButton.SLOT_FORWARD).build(),
        )).build()
        // ponytail: remoteControlled is not a Flow. Poll alongside the engine tick until shared control ownership becomes observable.
        scope.launch { while (isActive) { transport.refresh(); delay(250) } }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!Player.state.value.isPlaying && !Player.state.value.isBuffering) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        ListenTogether.leaveRoom()
        session?.release()
        session = null
        Player.release()
        Stores.flushAll()
        super.onDestroy()
    }
}
