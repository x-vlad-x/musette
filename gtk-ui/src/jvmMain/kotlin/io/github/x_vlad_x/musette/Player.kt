// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x_vlad_x.musette

import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.NowPlayingTrackState
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.domain.utils.YouTubeLikeStatus
import com.maxrave.domain.utils.connectArtists
import com.maxrave.domain.utils.toListName
import com.maxrave.simpmusic.viewModel.UIEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.gnome.adw.BottomSheet
import org.gnome.adw.Clamp
import org.gnome.gio.SimpleAction
import org.gnome.glib.GLib
import org.gnome.gtk.Adjustment
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.Button
import org.gnome.gtk.ContentFit
import org.gnome.gtk.Orientation
import org.gnome.gtk.Overflow
import org.gnome.gtk.Picture
import org.gnome.gtk.PolicyType
import org.gnome.gtk.ProgressBar
import org.gnome.gtk.Scale
import org.gnome.gtk.ScrolledWindow
import org.gnome.gtk.Stack
import org.gnome.gtk.StackTransitionType
import org.gnome.gtk.ToggleButton
import org.koin.mp.KoinPlatform.getKoin
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.like
import simpmusic.composeapp.generated.resources.live_badge
import simpmusic.composeapp.generated.resources.queue
import simpmusic.composeapp.generated.resources.repeat_all
import simpmusic.composeapp.generated.resources.repeat_off
import simpmusic.composeapp.generated.resources.repeat_one
import simpmusic.composeapp.generated.resources.shuffle

/**
 * Mini player (the bottom sheet's bar) and Now Playing (the sheet), both driven by
 * SharedViewModel — the same state SimpMusic's own player screens read.
 */
class Player(
    private val app: AppWindow,
    private val scope: CoroutineScope,
) {
    private val shared = app.shared
    private val handler: MediaPlayerHandler = getKoin().get()
    private val youtubeSignedIn: StateFlow<Boolean?> =
        shared.getYouTubeLoggedIn()
            .map { it == DataStoreManager.TRUE }
            .stateIn(scope, SharingStarted.Eagerly, null)
    val sheet = BottomSheet()

    // Mini player
    private val miniCover = picture()
    private val miniTitle = label("", "heading")
    private val miniArtist = label("", "caption", "dim-label")
    private val miniPlay = iconButton("media-playback-start-symbolic", gtr("play"), "flat", "circular") { shared.onUIEvent(UIEvent.PlayPause) }
    private val miniProgress = ProgressBar().apply { addCssClass("mini-progress"); addCssClass("osd") }

    // Now Playing
    private val repeatOff = tr(Res.string.repeat_off)
    private val repeatAll = tr(Res.string.repeat_all)
    private val repeatOne = tr(Res.string.repeat_one)
    private val bigCover = picture().apply { addCssClass("large") }
    private val title = label("", "now-playing-title", wrap = true, xalign = 0.5f)
    private val artist = label("", "dim-label", wrap = true, xalign = 0.5f)
    private val seek = Scale(Orientation.HORIZONTAL, Adjustment(0.0, 0.0, 100.0, 1.0, 5.0, 0.0)).apply { drawValue = false; hexpand = true }
    private val elapsed = label("0:00", "caption", "numeric", "dim-label")
    private val length = label("0:00", "caption", "numeric", "dim-label", xalign = 1f).apply { hexpand = true }
    private val playButton = iconButton("media-playback-start-symbolic", gtr("play"), "circular", "suggested-action", "big-play") { shared.onUIEvent(UIEvent.PlayPause) }
    private val prevButton = iconButton("media-skip-backward-symbolic", gtr("previous"), "flat", "circular") { shared.onUIEvent(UIEvent.Previous) }
    private val nextButton = iconButton("media-skip-forward-symbolic", gtr("next"), "flat", "circular") { shared.onUIEvent(UIEvent.Next) }
    private val shuffle = ToggleButton().apply { iconName = "media-playlist-shuffle-symbolic"; tooltipText = tr(Res.string.shuffle); addCssClass("flat"); addCssClass("circular"); valign = Align.CENTER }
    private val repeat = ToggleButton().apply { iconName = "media-playlist-repeat-symbolic"; tooltipText = repeatOff; addCssClass("flat"); addCssClass("circular"); valign = Align.CENTER }
    private val likeAction =
        SimpleAction("toggle-like", null).apply {
            enabled = false
            onActivate {
                when (youtubeSignedIn.value) {
                    true -> shared.addToYouTubeLiked()
                    false -> shared.onUIEvent(UIEvent.ToggleLike)
                    null -> Unit
                }
            }
        }
    private val like =
        Button().apply {
            iconName = "starred-symbolic"
            actionName = "app.toggle-like"
            addCssClass("flat")
            addCssClass("circular")
        }
    private val queueList = boxedList()
    private val sheetStack = Stack().apply { transitionType = StackTransitionType.SLIDE_LEFT_RIGHT }

    private var syncingControls = false
    private var seeking = false
    private var seekJob: Job? = null
    private var lastQueueKey: Any? = null

    init {
        requireNotNull(app.window.application).addAction(likeAction)
        app.addAccountListener {
            if (youtubeSignedIn.value == true) shared.refreshYouTubeLikeStatus()
        }
        sheet.bottomBar = miniPlayer()
        sheet.sheet = sheetStack
        sheet.revealBottomBar = false
        sheet.showDragHandle = true
        sheetStack.addNamed(nowPlaying(), "player")
        sheetStack.addNamed(queuePage(), "queue")
        sheetStack.visibleChildName = "player"
        sheet.onNotify("open") { if (!sheet.open) sheetStack.visibleChildName = "player" }

        shuffle.onToggled { if (!syncingControls) shared.onUIEvent(UIEvent.Shuffle) }
        repeat.onToggled { if (!syncingControls) shared.onUIEvent(UIEvent.Repeat) }
        seek.onChangeValue { _, value ->
            // Seek once the finger/pointer settles, not on every step of the drag.
            seeking = true
            seekJob?.cancel()
            seekJob =
                scope.launch {
                    delay(250)
                    shared.onUIEvent(UIEvent.UpdateProgress(value.toFloat().coerceIn(0f, 100f)))
                    delay(400)
                    seeking = false
                }
            false
        }

        scope.launch { shared.nowPlayingState.collect { showTrack(it) } }
        scope.launch {
            shared.controllerState.collect { state ->
                val icon = if (state.isPlaying) "media-playback-pause-symbolic" else "media-playback-start-symbolic"
                miniPlay.iconName = icon
                playButton.iconName = icon
                nextButton.sensitive = state.isNextAvailable
                prevButton.sensitive = state.isPreviousAvailable
                syncingControls = true
                shuffle.active = state.isShuffle
                repeat.active = state.repeatState != RepeatState.None
                repeat.iconName =
                    if (state.repeatState == RepeatState.One) "media-playlist-repeat-song-symbolic" else "media-playlist-repeat-symbolic"
                repeat.tooltipText =
                    when (state.repeatState) {
                        RepeatState.None -> repeatOff
                        RepeatState.All -> repeatAll
                        RepeatState.One -> repeatOne
                    }
                syncingControls = false
            }
        }
        scope.launch {
            combine(youtubeSignedIn, shared.likeStatus, shared.controllerState) { signedIn, youtubeLike, local ->
                val liked = if (signedIn == true) youtubeLike == YouTubeLikeStatus.LIKE else local.isLiked
                signedIn to liked
            }.distinctUntilChanged().collect { (signedIn, liked) ->
                if (liked) like.addCssClass("liked") else like.removeCssClass("liked")
                like.tooltipText =
                    when {
                        signedIn == true && liked -> gtr("remove_youtube_like")
                        signedIn == true -> gtr("like_on_youtube")
                        liked -> gtr("remove_local_like")
                        else -> tr(Res.string.like)
                    }
                updateLikeAction()
            }
        }
        scope.launch {
            shared.timeline.collect { t ->
                val fraction = if (t.total > 0) t.current.toDouble() / t.total else 0.0
                miniProgress.fraction = fraction.coerceIn(0.0, 1.0)
                if (!seeking) seek.value = fraction * 100
                elapsed.label = formatMs(t.current)
                length.label = if (t.isLive) tr(Res.string.live_badge) else formatMs(t.total)
                seek.sensitive = !t.isLive && t.total > 0
            }
        }
        scope.launch { shared.getQueueDataState().collect { renderQueue() } }
    }

    /** Opens Now Playing (or the queue) as soon as there is a track (debug hook). */
    fun openWhenReady(queue: Boolean) {
        GLib.timeoutAdd(GLib.PRIORITY_DEFAULT, 500) {
            if (!sheet.revealBottomBar) return@timeoutAdd true
            sheet.open = true
            if (queue) sheetStack.visibleChildName = "queue"
            false
        }
    }

    private fun picture() =
        Picture().apply {
            canShrink = true
            contentFit = ContentFit.COVER
            overflow = Overflow.HIDDEN
            addCssClass("cover")
        }

    private fun miniPlayer(): Box {
        miniCover.setSizeRequest(44, 44)
        val texts =
            Box(Orientation.VERTICAL, 2).apply {
                hexpand = true
                valign = Align.CENTER
                append(miniTitle)
                append(miniArtist)
            }
        val row =
            Box(Orientation.HORIZONTAL, 10).apply {
                append(Clamp().apply { maximumSize = 44; tighteningThreshold = 44; child = miniCover })
                append(texts)
                append(miniPlay)
                append(iconButton("media-skip-forward-symbolic", gtr("next"), "flat", "circular") { shared.onUIEvent(UIEvent.Next) })
            }
        return Box(Orientation.VERTICAL, 6).apply {
            addCssClass("mini-player")
            append(miniProgress)
            append(row)
        }
    }

    private fun nowPlaying(): ScrolledWindow {
        bigCover.setSizeRequest(300, 300)
        val times =
            Box(Orientation.HORIZONTAL, 0).apply {
                append(elapsed)
                append(length)
            }
        val controls =
            Box(Orientation.HORIZONTAL, 14).apply {
                halign = Align.CENTER
                append(shuffle)
                append(prevButton)
                append(playButton)
                append(nextButton)
                append(repeat)
            }
        val secondary =
            Box(Orientation.HORIZONTAL, 24).apply {
                halign = Align.CENTER
                append(like)
                append(iconButton("view-list-bullet-symbolic", tr(Res.string.queue), "flat", "circular") { sheetStack.visibleChildName = "queue" })
            }
        val column =
            Box(Orientation.VERTICAL, 14).apply {
                marginTop = 12
                marginBottom = 24
                marginStart = 18
                marginEnd = 18
                append(Clamp().apply { maximumSize = 360; child = bigCover; halign = Align.CENTER })
                append(title)
                append(artist)
                append(seek)
                append(times)
                append(controls)
                append(secondary)
            }
        return ScrolledWindow().apply {
            setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC)
            propagateNaturalHeight = true
            child = Clamp().apply { maximumSize = 480; child = column }
        }
    }

    private fun queuePage(): Box {
        val headerRow =
            Box(Orientation.HORIZONTAL, 6).apply {
                marginStart = 12
                marginEnd = 12
                marginTop = 6
                append(iconButton("go-previous-symbolic", gtr("back"), "flat", "circular") { sheetStack.visibleChildName = "player" })
                append(label(tr(Res.string.queue), "title-4").apply { valign = Align.CENTER })
            }
        return Box(Orientation.VERTICAL, 6).apply {
            append(headerRow)
            append(
                ScrolledWindow().apply {
                    setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC)
                    vexpand = true
                    setSizeRequest(-1, 420)
                    child =
                        Clamp().apply {
                            maximumSize = 600
                            child = Box(Orientation.VERTICAL, 0).apply { marginStart = 12; marginEnd = 12; marginBottom = 18; append(queueList) }
                        }
                },
            )
        }
    }

    private fun Track.artistsText(): String = artists?.toListName()?.connectArtists().orEmpty()

    private fun showTrack(state: NowPlayingTrackState?) {
        val track = state?.track
        val song = state?.songEntity
        val hasTrack = state != null && state.isNotEmpty() && (track != null || song != null)
        sheet.revealBottomBar = hasTrack
        updateLikeAction()
        if (!hasTrack) {
            sheet.open = false
            return
        }
        val titleText = track?.title ?: song?.title ?: ""
        val artistText = track?.artistsText()?.takeIf { it.isNotBlank() } ?: song?.artistName?.connectArtists().orEmpty()
        val thumb = track?.thumbnails.bestUrl() ?: song?.thumbnails
        miniTitle.label = titleText
        miniArtist.label = artistText
        title.label = titleText
        artist.label = artistText
        Images.load(thumb, miniCover, scope)
        Images.load(thumb, bigCover, scope)
        renderQueue()
    }

    private fun updateLikeAction() {
        likeAction.enabled = sheet.revealBottomBar && youtubeSignedIn.value != null
    }

    private fun renderQueue() {
        val queue = shared.getQueueDataState().value?.data?.listTracks ?: emptyList()
        val currentId = shared.nowPlayingState.value?.track?.videoId ?: shared.nowPlayingState.value?.songEntity?.videoId
        val key = queue.map { it.videoId } to currentId
        if (key == lastQueueKey) return
        lastQueueKey = key
        queueList.clear()
        queue.forEachIndexed { index, track ->
            val row =
                trackRow(track.title, track.artistsText(), track.thumbnails.bestUrl(), scope, track.duration) {
                    handler.playMediaItemInMediaSource(index)
                }
            if (track.videoId == currentId) row.addCssClass("queue-current")
            queueList.append(row)
        }
    }
}
