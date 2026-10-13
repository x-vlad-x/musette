package io.github.x_vlad_x.simpmusicgtk

import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.utils.connectArtists
import com.maxrave.domain.utils.toListName
import com.maxrave.domain.utils.toTrack
import com.maxrave.simpmusic.viewModel.AlbumViewModel
import com.maxrave.simpmusic.viewModel.ArtistScreenState
import com.maxrave.simpmusic.viewModel.ArtistViewModel
import com.maxrave.simpmusic.viewModel.PlaylistUIEvent
import com.maxrave.simpmusic.viewModel.PlaylistUIState
import com.maxrave.simpmusic.viewModel.PlaylistViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.gnome.adw.Clamp
import org.gnome.adw.StatusPage
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.ListBox
import org.gnome.gtk.Orientation
import org.gnome.gtk.PolicyType
import org.gnome.gtk.PositionType
import org.gnome.gtk.ScrolledWindow
import org.gnome.gtk.Stack
import org.gnome.gtk.StackTransitionType
import org.gnome.gtk.Widget
import org.koin.mp.KoinPlatform.getKoin
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.albums
import simpmusic.composeapp.generated.resources.fans_might_also_like_inArtist
import simpmusic.composeapp.generated.resources.featured_inArtist
import simpmusic.composeapp.generated.resources.n_song
import simpmusic.composeapp.generated.resources.popular
import simpmusic.composeapp.generated.resources.radio
import simpmusic.composeapp.generated.resources.shuffle
import simpmusic.composeapp.generated.resources.singles
import simpmusic.composeapp.generated.resources.videos

/** Shared shape of the three pages: spinner → scrolling, clamped column (or an error). */
abstract class DetailPage {
    protected val column =
        Box(Orientation.VERTICAL, 18).apply {
            marginTop = 12
            marginBottom = 24
            marginStart = 12
            marginEnd = 12
        }
    protected val scroller =
        ScrolledWindow().apply {
            setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC)
            vexpand = true
            child =
                Clamp().apply {
                    maximumSize = 720
                    child = column
                }
        }
    private val errorPage = StatusPage().apply { iconName = "dialog-error-symbolic"; title = gtr("page_failed") }
    protected val stack =
        Stack().apply {
            transitionType = StackTransitionType.CROSSFADE
            addNamed(spinner(), "loading")
            addNamed(scroller, "content")
            addNamed(errorPage, "error")
            visibleChildName = "loading"
        }
    val widget: Widget get() = stack

    protected fun showError(message: String?) {
        errorPage.description = message
        stack.visibleChildName = "error"
    }

    protected fun header(
        thumbUrl: String?,
        title: String,
        subtitle: String,
        scope: CoroutineScope,
        round: Boolean = false,
        buttons: List<Widget>,
    ): Widget =
        Box(Orientation.VERTICAL, 10).apply {
            append(
                (if (round) cover(180, 180, thumbUrl, scope, "round") else cover(220, 220, thumbUrl, scope, "large")).apply {
                    halign = Align.CENTER
                },
            )
            append(label(title, "title-2", wrap = true, xalign = 0.5f))
            if (subtitle.isNotBlank()) append(label(subtitle, "dim-label", wrap = true, xalign = 0.5f))
            append(
                Box(Orientation.HORIZONTAL, 12).apply {
                    halign = Align.CENTER
                    marginTop = 6
                    buttons.forEach { append(it) }
                },
            )
        }

    protected fun Track.artistsText(): String = artists?.toListName()?.connectArtists().orEmpty()

    protected fun sectionTitle(text: String): Widget = label(text, "title-4").apply { marginTop = 6 }
}

class AlbumPage(
    app: AppWindow,
    scope: CoroutineScope,
    vms: PageViewModels,
    browseId: String,
    autoplay: Boolean = false,
) : DetailPage() {
    private val vm = vms.add(getKoin().get<AlbumViewModel>())
    private var autoplayPending = autoplay

    init {
        vm.updateBrowseId(browseId)
        val list = boxedList()
        scope.launch {
            vm.uiState.collect { state ->
                if (state.listTrack.isEmpty()) return@collect
                column.clear()
                list.clear()
                val subtitle = listOf(state.artist.name, state.year, tr(Res.plurals.n_song, state.listTrack.size), state.length).filter { it.isNotBlank() }
                column.append(
                    header(
                        state.thumbnail,
                        state.title,
                        subtitle.joinToString(" · "),
                        scope,
                        buttons =
                            listOf(
                                pillButton(gtr("play"), "media-playback-start-symbolic", true) { vm.playTrack(state.listTrack.first()) },
                                pillButton(tr(Res.string.shuffle), "media-playlist-shuffle-symbolic", false) { vm.shuffle() },
                            ),
                    ),
                )
                state.listTrack.forEachIndexed { index, track ->
                    list.append(
                        trackRow(track.title, track.artistsText(), null, scope, track.duration, prefixText = "${index + 1}") { vm.playTrack(track) },
                    )
                }
                column.append(list)
                stack.visibleChildName = "content"
                if (autoplayPending) {
                    autoplayPending = false
                    vm.playTrack(state.listTrack.first())
                }
            }
        }
    }
}

class PlaylistPage(
    private val app: AppWindow,
    scope: CoroutineScope,
    vms: PageViewModels,
    private val playlistId: String,
) : DetailPage() {
    private val vm = vms.add(getKoin().get<PlaylistViewModel>())
    private val list = boxedList()
    private var shownTracks = 0

    init {
        vm.getData(playlistId)
        scope.launch {
            vm.uiState.collect { state ->
                when (state) {
                    is PlaylistUIState.Loading -> stack.visibleChildName = "loading"
                    is PlaylistUIState.Error -> showError(state.message)
                    is PlaylistUIState.Success -> {
                        val data = state.data ?: return@collect
                        column.clear()
                        val subtitle = listOf(data.author.name, data.year, if (data.trackCount > 0) tr(Res.plurals.n_song, data.trackCount) else "")
                        column.append(
                            header(
                                data.thumbnail,
                                data.title,
                                subtitle.filter { it.isNotBlank() }.joinToString(" · "),
                                scope,
                                buttons =
                                    listOf(
                                        pillButton(gtr("play"), "media-playback-start-symbolic", true) { vm.onUIEvent(PlaylistUIEvent.PlayAll) },
                                        pillButton(tr(Res.string.shuffle), "media-playlist-shuffle-symbolic", false) { vm.onUIEvent(PlaylistUIEvent.Shuffle) },
                                    ),
                            ),
                        )
                        column.append(list)
                        stack.visibleChildName = "content"
                    }
                }
            }
        }
        scope.launch {
            vm.tracks.collect { tracks ->
                // Continuations append; anything else (a fresh load) rebuilds.
                if (tracks.size < shownTracks) {
                    list.clear()
                    shownTracks = 0
                }
                tracks.drop(shownTracks).forEach { track ->
                    list.append(
                        trackRow(track.title, track.artistsText(), track.thumbnails.bestUrl(), scope, track.duration) {
                            vm.onUIEvent(PlaylistUIEvent.ItemClick(track.videoId))
                        },
                    )
                }
                shownTracks = tracks.size
            }
        }
        scroller.onEdgeReached { edge ->
            if (edge == PositionType.BOTTOM) vm.continuation.value?.let { vm.getContinuationTrack(playlistId, it) }
        }
    }
}

class ArtistPage(
    private val app: AppWindow,
    scope: CoroutineScope,
    vms: PageViewModels,
    channelId: String,
) : DetailPage() {
    private val vm = vms.add(getKoin().get<ArtistViewModel>())

    init {
        vm.browseArtist(channelId)
        scope.launch {
            vm.artistScreenState.collect { state ->
                when (state) {
                    is ArtistScreenState.Loading -> stack.visibleChildName = "loading"
                    is ArtistScreenState.Error -> showError(state.message)
                    is ArtistScreenState.Success -> render(state, scope)
                }
            }
        }
    }

    private fun render(
        state: ArtistScreenState,
        scope: CoroutineScope,
    ) {
        val data = state.data
        column.clear()
        val buttons = mutableListOf<Widget>()
        data.shuffleParam?.let { endpoint ->
            buttons += pillButton(tr(Res.string.shuffle), "media-playlist-shuffle-symbolic", true) { vm.onShuffleClick(endpoint) }
        }
        data.radioParam?.let { endpoint ->
            buttons += pillButton(tr(Res.string.radio), "audio-x-generic-symbolic", data.shuffleParam == null) { vm.onRadioClick(endpoint) }
        }
        val subtitle = listOfNotNull(data.subscribers, data.playCount).joinToString(" · ")
        column.append(header(data.imageUrl, data.title ?: "", subtitle, scope, round = true, buttons = buttons))

        if (data.popularSongs.isNotEmpty()) {
            column.append(sectionTitle(tr(Res.string.popular)))
            val list = boxedList()
            data.popularSongs.take(10).forEach { track ->
                list.append(
                    trackRow(track.title, track.artistsText(), track.thumbnails.bestUrl(), scope, track.duration) {
                        app.playRadio(track, "${data.title ?: ""} — ${tr(Res.string.popular)}")
                    },
                )
            }
            column.append(list)
        }
        data.albums?.results?.takeIf { it.isNotEmpty() }?.let { albums ->
            column.append(sectionTitle(tr(Res.string.albums)))
            column.append(shelf(albums.map { card(it.title, it.year, it.thumbnails.bestUrl(), scope) { app.openAlbum(it.browseId) } }))
        }
        data.singles?.results?.takeIf { it.isNotEmpty() }?.let { singles ->
            column.append(sectionTitle(tr(Res.string.singles)))
            column.append(shelf(singles.map { card(it.title, it.year, it.thumbnails.bestUrl(), scope) { app.openAlbum(it.browseId) } }))
        }
        data.video?.video?.takeIf { it.isNotEmpty() }?.let { videos ->
            column.append(sectionTitle(tr(Res.string.videos)))
            column.append(
                shelf(
                    videos.map { video ->
                        card(video.title, video.views, video.thumbnails.bestUrl(), scope, wide = true) {
                            app.playRadio(video, "${data.title ?: ""} — ${tr(Res.string.videos)}")
                        }
                    },
                ),
            )
        }
        data.featuredOn.takeIf { it.isNotEmpty() }?.let { playlists ->
            column.append(sectionTitle(tr(Res.string.featured_inArtist)))
            column.append(shelf(playlists.map { card(it.title, it.author, it.thumbnails.bestUrl(), scope) { app.openPlaylist(it.id) } }))
        }
        data.related?.results?.takeIf { it.isNotEmpty() }?.let { related ->
            column.append(sectionTitle(tr(Res.string.fans_might_also_like_inArtist)))
            column.append(
                shelf(related.map { card(it.title, it.subscribers, it.thumbnails.bestUrl(), scope, round = true) { app.openArtist(it.browseId) } }),
            )
        }
        stack.visibleChildName = "content"
    }
}
