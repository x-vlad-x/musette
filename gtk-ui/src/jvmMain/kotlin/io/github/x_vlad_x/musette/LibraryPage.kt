// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x_vlad_x.musette

import com.maxrave.common.Config
import com.maxrave.common.LOCAL_PLAYLIST_ID
import com.maxrave.domain.data.entities.AlbumEntity
import com.maxrave.domain.data.entities.ArtistEntity
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.type.RecentlyType
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.utils.LocalResource
import com.maxrave.domain.utils.toArrayListTrack
import com.maxrave.domain.utils.toTrack
import com.maxrave.simpmusic.ui.screen.library.LibraryDynamicPlaylistType
import com.maxrave.simpmusic.viewModel.LibraryDynamicPlaylistViewModel
import com.maxrave.simpmusic.viewModel.LibraryViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.gnome.adw.ActionRow
import org.gnome.adw.Clamp
import org.gnome.adw.StatusPage
import org.gnome.gtk.Box
import org.gnome.gtk.Image
import org.gnome.gtk.Orientation
import org.gnome.gtk.PolicyType
import org.gnome.gtk.ScrolledWindow
import org.gnome.gtk.Widget
import org.koin.mp.KoinPlatform.getKoin
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.Song_and_artist_name
import simpmusic.composeapp.generated.resources.album_and_artist_name
import simpmusic.composeapp.generated.resources.downloaded
import simpmusic.composeapp.generated.resources.favorite_playlists
import simpmusic.composeapp.generated.resources.followed
import simpmusic.composeapp.generated.resources.library_artist_count
import simpmusic.composeapp.generated.resources.liked_songs
import simpmusic.composeapp.generated.resources.liked_songs_count
import simpmusic.composeapp.generated.resources.mix_for_you
import simpmusic.composeapp.generated.resources.most_played
import simpmusic.composeapp.generated.resources.n_song
import simpmusic.composeapp.generated.resources.playlist
import simpmusic.composeapp.generated.resources.playlist_and_author
import simpmusic.composeapp.generated.resources.recently_added
import simpmusic.composeapp.generated.resources.shuffle
import simpmusic.composeapp.generated.resources.your_library
import simpmusic.composeapp.generated.resources.your_playlists
import simpmusic.composeapp.generated.resources.your_youtube_playlists

/**
 * The Library tab: SimpMusic's "Your library" collections (liked songs, followed artists, most
 * played, downloads), the signed-in account's YouTube playlists and mixes, local playlists, saved
 * albums/playlists and recent items. Database-backed sections follow the database live; the
 * YouTube ones are fetched when the tab is first shown and again after an account change.
 */
class LibraryPage(
    private val app: AppWindow,
    private val scope: CoroutineScope,
) {
    private val vm: LibraryViewModel = getKoin().get()
    private val column =
        Box(Orientation.VERTICAL, 22).apply {
            marginTop = 12
            marginBottom = 24
            marginStart = 12
            marginEnd = 12
        }
    private var loaded = false
    val widget: Widget =
        ScrolledWindow().apply {
            setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC)
            vexpand = true
            child =
                Clamp().apply {
                    maximumSize = 1100
                    tighteningThreshold = 900
                    child = column
                }
        }

    // Sections in display order; each hides itself while it has nothing to show.
    private val collections = Section(tr(Res.string.your_library))
    private val localPlaylists = Section(tr(Res.string.your_playlists))
    private val youtubePlaylists = Section(tr(Res.string.your_youtube_playlists))
    private val mixes = Section(tr(Res.string.mix_for_you))
    private val saved = Section(tr(Res.string.favorite_playlists))
    private val recent = Section(tr(Res.string.recently_added))

    init {
        listOf(collections, localPlaylists, youtubePlaylists, mixes, saved, recent).forEach { column.append(it.box) }
        app.addAccountListener { if (loaded) loadYouTube() }

        scope.launch {
            vm.libraryOverview.filterNotNull().collect { overview ->
                val list = boxedList()
                val liked = tr(Res.string.liked_songs)
                collectionRow(list, liked, "starred-symbolic", tr(Res.plurals.liked_songs_count, overview.favorite.count)) {
                    openSongs(LibraryDynamicPlaylistType.Favorite, liked)
                }
                collectionRow(list, tr(Res.string.followed), "emoji-people-symbolic", tr(Res.plurals.library_artist_count, overview.followed.count)) {
                    openFollowedArtists()
                }
                val mostPlayed = tr(Res.string.most_played)
                collectionRow(list, mostPlayed, "media-playlist-repeat-symbolic", tr(Res.plurals.n_song, overview.mostPlayed.count)) {
                    openSongs(LibraryDynamicPlaylistType.MostPlayed, mostPlayed)
                }
                if (overview.downloaded.count > 0) {
                    val downloaded = tr(Res.string.downloaded)
                    collectionRow(list, downloaded, "folder-download-symbolic", tr(Res.plurals.n_song, overview.downloaded.count)) {
                        openSongs(LibraryDynamicPlaylistType.Downloaded, downloaded)
                    }
                }
                collections.show(list)
            }
        }
        render(vm.yourLocalPlaylist, localPlaylists) { items: List<LocalPlaylistEntity> ->
            shelf(items.map { p -> card(p.title, tr(Res.plurals.n_song, p.tracks?.size ?: 0), p.thumbnail, scope) { openLocalPlaylist(p.id) } })
        }
        render(vm.youTubePlaylist, youtubePlaylists) { items: List<PlaylistsResult> ->
            shelf(items.map { p -> card(p.title, p.author, p.thumbnails.bestUrl(), scope) { app.openPlaylist(p.browseId) } })
        }
        render(vm.youTubeMixForYou, mixes) { items: List<PlaylistsResult> ->
            shelf(items.map { p -> card(p.title, p.author, p.thumbnails.bestUrl(), scope) { app.openPlaylist(p.browseId) } })
        }
        render(vm.favoritePlaylist, saved) { items ->
            shelf(
                items.reversed().mapNotNull { p ->
                    when (p) {
                        is AlbumEntity -> card(p.title, p.artistName?.joinToString(", "), p.thumbnails, scope) { app.openAlbum(p.browseId) }
                        is PlaylistEntity -> card(p.title, p.author, p.thumbnails, scope) { app.openPlaylist(p.id) }
                        else -> null
                    }
                },
            )
        }
        render(vm.recentlyAdded, recent) { items: List<RecentlyType> ->
            val list = boxedList()
            // "LM" is pinned into this list when signed in; it is already among the YouTube playlists.
            items.filterNot { it is PlaylistEntity && it.id == "LM" }.take(20).forEach { item -> recentRow(item)?.let(list::append) }
            list
        }

        vm.getLocalPlaylist()
        vm.getPlaylistFavorite()
        vm.getRecentlyAdded()
    }

    /** Called when the tab becomes visible: the network sections load on first sight. */
    fun onShown() {
        if (loaded) return
        loaded = true
        loadYouTube()
    }

    private fun loadYouTube() {
        if (vm.getYouTubeLoggedIn()) {
            vm.getYouTubePlaylist()
            vm.getYouTubeMixedForYou()
        } else {
            youtubePlaylists.hide()
            mixes.hide()
        }
        vm.getRecentlyAdded()
    }

    private fun <T> render(
        flow: StateFlow<LocalResource<List<T>>>,
        section: Section,
        build: (List<T>) -> Widget,
    ) {
        scope.launch {
            flow.collect { res ->
                val data = (res as? LocalResource.Success)?.data
                if (data.isNullOrEmpty()) section.hide() else section.show(build(data))
            }
        }
    }

    private fun collectionRow(
        list: org.gnome.gtk.ListBox,
        title: String,
        icon: String,
        count: String,
        onClick: () -> Unit,
    ) {
        list.append(navRow(title, count, icon, onClick))
    }

    private fun navRow(
        title: String,
        subtitle: String,
        icon: String,
        onClick: () -> Unit,
    ): ActionRow =
        ActionRow().apply {
            useMarkup = false
            this.title = title
            this.subtitle = subtitle
            activatable = true
            addPrefix(Image.fromIconName(icon))
            addSuffix(Image.fromIconName("go-next-symbolic"))
            onActivated { onClick() }
        }

    private fun recentRow(item: RecentlyType): ActionRow? =
        when (item) {
            is SongEntity ->
                trackRow(item.title, tr(Res.string.Song_and_artist_name, item.artistName?.joinToString(", ").orEmpty()), item.thumbnails, scope, item.duration) {
                    app.playRadio(item.toTrack())
                }
            is AlbumEntity ->
                trackRow(item.title, tr(Res.string.album_and_artist_name, item.artistName?.joinToString(", ").orEmpty()), item.thumbnails, scope) { app.openAlbum(item.browseId) }
            is PlaylistEntity ->
                trackRow(item.title, tr(Res.string.playlist_and_author, item.author.orEmpty()), item.thumbnails, scope) { app.openPlaylist(item.id) }
            is ArtistEntity -> trackRow(item.name, gtr("artist"), item.thumbnails, scope, round = true) { app.openArtist(item.channelId) }
            else -> null
        }

    fun openSongs(
        type: LibraryDynamicPlaylistType,
        title: String,
    ) = app.push(title) { pageScope, vms ->
        val dynamic = vms.add(getKoin().get<LibraryDynamicPlaylistViewModel>())
        val source =
            when (type) {
                LibraryDynamicPlaylistType.MostPlayed -> dynamic.listMostPlayedSong
                LibraryDynamicPlaylistType.Downloaded -> dynamic.listDownloadedSong
                else -> dynamic.listFavoriteSong
            }
        SongListPage(title, pageScope, source, playAll = { songs, shuffle ->
            val list = if (shuffle) songs.shuffled() else songs
            list.firstOrNull()?.let { playSongs(app, list, it, title) }
        }) { song, _ -> dynamic.playSong(song.videoId, type) }.widget
    }

    private fun openFollowedArtists() =
        app.push(tr(Res.string.followed)) { pageScope, vms ->
            val dynamic = vms.add(getKoin().get<LibraryDynamicPlaylistViewModel>())
            val list = boxedList()
            val empty = StatusPage().apply { iconName = "emoji-people-symbolic"; title = gtr("no_followed_artists") }
            val page = listPage(list)
            val stack = org.gnome.gtk.Stack().apply { addNamed(page, "list"); addNamed(empty, "empty") }
            pageScope.launch {
                dynamic.listFollowedArtist.collect { artists ->
                    list.clear()
                    artists.forEach { a -> list.append(trackRow(a.name, gtr("artist"), a.thumbnails, pageScope, round = true) { app.openArtist(a.channelId) }) }
                    stack.visibleChildName = if (artists.isEmpty()) "empty" else "list"
                }
            }
            stack
        }

    private fun openLocalPlaylist(id: Long) =
        app.push(tr(Res.string.playlist)) { pageScope, _ ->
            val repo: LocalPlaylistRepository = getKoin().get()
            val songs = kotlinx.coroutines.flow.MutableStateFlow<List<SongEntity>>(emptyList())
            var title = tr(Res.string.playlist)
            val page =
                SongListPage(title, pageScope, songs, playAll = { list, shuffle ->
                    val ordered = if (shuffle) list.shuffled() else list
                    ordered.firstOrNull()?.let { playLocal(id, title, ordered, it) }
                }) { song, _ -> playLocal(id, title, songs.value, song) }
            pageScope.launch {
                repo.getLocalPlaylist(id).collect { res ->
                    (res as? LocalResource.Success)?.data?.let { title = it.title; page.setTitle(it.title) }
                }
            }
            pageScope.launch {
                // Re-read whenever the playlist's track list changes.
                repo.listTrackFlow(id).collect { songs.value = withContext(Dispatchers.IO) { repo.getFullPlaylistTracks(id) } }
            }
            page.widget
        }

    private fun playLocal(
        id: Long,
        title: String,
        songs: List<SongEntity>,
        first: SongEntity,
    ) {
        app.shared.setQueueData(
            QueueData.Data(
                listTracks = songs.toArrayListTrack(),
                firstPlayedTrack = first.toTrack(),
                playlistId = LOCAL_PLAYLIST_ID + id,
                playlistName = title,
                playlistType = PlaylistType.LOCAL_PLAYLIST,
                continuation = null,
            ),
        )
        app.shared.loadMediaItem(first.toTrack(), Config.PLAYLIST_CLICK, songs.indexOf(first).coerceAtLeast(0))
    }

    /** A titled block of the Library column. */
    private class Section(
        title: String,
    ) {
        val box = Box(Orientation.VERTICAL, 8).apply { visible = false }
        private val heading = label(title, "title-3").apply { addCssClass("section-header") }
        private var content: Widget? = null

        init {
            box.append(heading)
        }

        fun show(widget: Widget) {
            content?.let { box.remove(it) }
            content = widget
            box.append(widget)
            box.visible = true
        }

        fun hide() {
            box.visible = false
        }
    }
}

/** Plays an arbitrary slice of a dynamic collection (Play all / Shuffle on its page). */
private fun playSongs(
    app: AppWindow,
    songs: List<SongEntity>,
    first: SongEntity,
    name: String,
) {
    app.shared.setQueueData(
        QueueData.Data(
            listTracks = songs.toArrayListTrack(),
            firstPlayedTrack = first.toTrack(),
            playlistId = null,
            playlistName = name,
            playlistType = PlaylistType.RADIO,
            continuation = null,
        ),
    )
    app.shared.loadMediaItem(first.toTrack(), Config.PLAYLIST_CLICK, 0)
}

private fun listPage(list: org.gnome.gtk.ListBox): Widget =
    ScrolledWindow().apply {
        setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC)
        vexpand = true
        child =
            Clamp().apply {
                maximumSize = 720
                child =
                    Box(Orientation.VERTICAL, 0).apply {
                        marginTop = 12
                        marginBottom = 24
                        marginStart = 12
                        marginEnd = 12
                        append(list)
                    }
            }
    }

/** A song collection page: title, count, Play / Shuffle, then the songs. */
class SongListPage(
    title: String,
    private val scope: CoroutineScope,
    source: Flow<List<SongEntity>>,
    playAll: (List<SongEntity>, shuffle: Boolean) -> Unit,
    onSong: (SongEntity, Int) -> Unit,
) {
    private val titleLabel = label(title, "title-2", wrap = true, xalign = 0.5f)
    private val countLabel = label("", "dim-label", xalign = 0.5f)
    private val list = boxedList()
    private var current: List<SongEntity> = emptyList()
    private val stack = org.gnome.gtk.Stack()
    val widget: Widget get() = stack

    init {
        val column =
            Box(Orientation.VERTICAL, 12).apply {
                marginTop = 12
                marginBottom = 24
                marginStart = 12
                marginEnd = 12
                append(titleLabel)
                append(countLabel)
                append(
                    Box(Orientation.HORIZONTAL, 12).apply {
                        halign = org.gnome.gtk.Align.CENTER
                        append(pillButton(gtr("play"), "media-playback-start-symbolic", true) { playAll(current, false) })
                        append(pillButton(tr(Res.string.shuffle), "media-playlist-shuffle-symbolic", false) { playAll(current, true) })
                    },
                )
                append(list)
            }
        stack.addNamed(spinner(), "loading")
        stack.addNamed(
            ScrolledWindow().apply {
                setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC)
                vexpand = true
                child = Clamp().apply { maximumSize = 720; child = column }
            },
            "content",
        )
        stack.addNamed(StatusPage().apply { iconName = "audio-x-generic-symbolic"; this.title = gtr("nothing_here") }, "empty")
        scope.launch {
            var first = true
            source.collect { songs ->
                current = songs
                list.clear()
                songs.forEachIndexed { index, song ->
                    list.append(
                        trackRow(song.title, song.artistName?.joinToString(", "), song.thumbnails, scope, song.duration) { onSong(song, index) },
                    )
                }
                countLabel.label = tr(Res.plurals.n_song, songs.size)
                // The first emission of a StateFlow is its empty initial value: keep the spinner.
                if (first && songs.isEmpty()) {
                    first = false
                    scope.launch {
                        kotlinx.coroutines.delay(1500)
                        if (current.isEmpty()) stack.visibleChildName = "empty"
                    }
                } else {
                    stack.visibleChildName = if (songs.isEmpty()) "empty" else "content"
                }
                first = false
            }
        }
    }

    fun setTitle(title: String) {
        titleLabel.label = title
    }
}
