package io.github.x_vlad_x.simpmusicgtk

import com.maxrave.domain.data.model.searchResult.albums.AlbumsResult
import com.maxrave.domain.data.model.searchResult.artists.ArtistsResult
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.model.searchResult.songs.SongsResult
import com.maxrave.domain.data.model.searchResult.videos.VideosResult
import com.maxrave.domain.data.type.SearchResultType
import com.maxrave.domain.utils.connectArtists
import com.maxrave.domain.utils.toListName
import com.maxrave.domain.utils.toTrack
import com.maxrave.simpmusic.viewModel.SearchScreenState
import com.maxrave.simpmusic.viewModel.SearchScreenUIState
import com.maxrave.simpmusic.viewModel.SearchType
import com.maxrave.simpmusic.viewModel.SearchViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.gnome.adw.ActionRow
import org.gnome.adw.Clamp
import org.gnome.adw.StatusPage
import org.gnome.adw.Toggle
import org.gnome.adw.ToggleGroup
import org.gnome.gtk.Box
import org.gnome.gtk.Image
import org.gnome.gtk.ListBox
import org.gnome.gtk.Orientation
import org.gnome.gtk.PolicyType
import org.gnome.gtk.ScrolledWindow
import org.gnome.gtk.SearchEntry
import org.gnome.gtk.Stack
import org.gnome.gtk.StackTransitionType
import org.gnome.gtk.Widget
import org.javagi.base.Out
import org.koin.mp.KoinPlatform.getKoin
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.Song_and_artist_name
import simpmusic.composeapp.generated.resources.albums
import simpmusic.composeapp.generated.resources.all
import simpmusic.composeapp.generated.resources.artists
import simpmusic.composeapp.generated.resources.no_results_found
import simpmusic.composeapp.generated.resources.playlist_and_author
import simpmusic.composeapp.generated.resources.playlists
import simpmusic.composeapp.generated.resources.search_for_songs_artists_albums_playlists_and_more
import simpmusic.composeapp.generated.resources.songs
import simpmusic.composeapp.generated.resources.videos

class SearchPage(
    private val app: AppWindow,
    private val scope: CoroutineScope,
) {
    private val vm: SearchViewModel = getKoin().get()
    private val entry = SearchEntry().apply { placeholderText = tr(Res.string.search_for_songs_artists_albums_playlists_and_more); hexpand = true }
    private val filters = ToggleGroup()
    private val results = boxedList()
    private val suggestions = boxedList()
    private val stack = Stack().apply { transitionType = StackTransitionType.CROSSFADE; vexpand = true }
    private var query = ""
    private var suggestJob: Job? = null
    private var typing = false

    val widget: Widget

    private val types =
        listOf(
            SearchType.ALL to tr(Res.string.all),
            SearchType.SONGS to tr(Res.string.songs),
            SearchType.VIDEOS to tr(Res.string.videos),
            SearchType.ALBUMS to tr(Res.string.albums),
            SearchType.ARTISTS to tr(Res.string.artists),
            SearchType.PLAYLISTS to tr(Res.string.playlists),
        )

    init {
        types.forEach { (type, title) ->
            filters.add(Toggle().apply { name = type.name; label = title })
        }
        filters.activeName = SearchType.ALL.name
        // Wide enough for every (translated) label; the row scrolls on a narrow screen instead of ellipsizing.
        val natural = Out<Int>()
        filters.measure(Orientation.HORIZONTAL, -1, null, natural, null, null)
        filters.setSizeRequest(maxOf(natural.get() ?: 0, 460), -1)
        filters.onNotify("active") { if (query.isNotBlank()) search(query, record = false) }

        val top =
            Box(Orientation.VERTICAL, 8).apply {
                marginTop = 8
                marginStart = 12
                marginEnd = 12
                append(entry)
                append(
                    ScrolledWindow().apply {
                        setPolicy(PolicyType.AUTOMATIC, PolicyType.NEVER)
                        propagateNaturalHeight = true
                        child = filters
                    },
                )
            }

        stack.addNamed(page(suggestions), "suggestions")
        stack.addNamed(spinner(), "loading")
        stack.addNamed(page(results), "results")
        stack.addNamed(
            StatusPage().apply {
                iconName = "system-search-symbolic"
                title = tr(Res.string.no_results_found)
                description = gtr("try_other_words")
            },
            "empty",
        )
        stack.addNamed(
            StatusPage().apply {
                iconName = "network-error-symbolic"
                title = gtr("search_failed")
                description = gtr("check_connection")
            },
            "error",
        )
        stack.visibleChildName = "suggestions"

        widget =
            Box(Orientation.VERTICAL, 8).apply {
                append(
                    Clamp().apply {
                        maximumSize = 800
                        child = top
                    },
                )
                append(stack)
            }

        entry.onSearchChanged {
            val text = entry.text
            // search-changed is delayed (~150 ms), so it can arrive after Enter already searched.
            if (!typing && text.trim() == query && query.isNotEmpty()) return@onSearchChanged
            typing = true
            suggestJob?.cancel()
            if (text.isBlank()) {
                stack.visibleChildName = "suggestions"
                showHistory()
            } else {
                suggestJob =
                    scope.launch {
                        delay(250)
                        vm.suggestQuery(text)
                    }
                stack.visibleChildName = "suggestions"
            }
        }
        entry.onActivate { if (entry.text.isNotBlank()) search(entry.text) }

        scope.launch { vm.searchHistory.collect { if (entry.text.isBlank()) showHistory() } }
        scope.launch {
            vm.searchScreenState.collect { state ->
                if (typing && entry.text.isNotBlank()) showSuggestions(state)
            }
        }
        scope.launch {
            combine(vm.searchScreenUIState, vm.searchScreenState) { ui, state -> ui to state }.collect { (ui, state) ->
                if (typing || query.isBlank()) return@collect
                when (ui) {
                    SearchScreenUIState.Loading -> stack.visibleChildName = "loading"
                    SearchScreenUIState.Error -> stack.visibleChildName = "error"
                    SearchScreenUIState.Success -> showResults(state)
                    SearchScreenUIState.Empty -> stack.visibleChildName = "empty"
                }
            }
        }
    }

    private fun page(list: ListBox): Widget =
        ScrolledWindow().apply {
            setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC)
            vexpand = true
            child =
                Clamp().apply {
                    maximumSize = 800
                    child =
                        Box(Orientation.VERTICAL, 0).apply {
                            marginStart = 12
                            marginEnd = 12
                            marginBottom = 18
                            append(list)
                        }
                }
        }

    private fun search(
        text: String,
        record: Boolean = true,
    ) {
        typing = false
        suggestJob?.cancel()
        query = text.trim()
        if (record) vm.insertSearchHistory(query)
        stack.visibleChildName = "loading"
        when (SearchType.valueOf(filters.activeName ?: SearchType.ALL.name)) {
            SearchType.SONGS -> vm.searchSongs(query)
            SearchType.VIDEOS -> vm.searchVideos(query)
            SearchType.ALBUMS -> vm.searchAlbums(query)
            SearchType.ARTISTS -> vm.searchArtists(query)
            SearchType.PLAYLISTS -> vm.searchPlaylists(query)
            else -> vm.searchAll(query)
        }
    }

    private fun queryRow(
        text: String,
        icon: String,
    ): ActionRow =
        ActionRow().apply {
            useMarkup = false
            title = text
            activatable = true
            addPrefix(Image.fromIconName(icon))
            onActivated {
                entry.text = text
                entry.position = text.length
                search(text)
            }
        }

    private fun showHistory() {
        suggestions.clear()
        vm.searchHistory.value.take(20).forEach { suggestions.append(queryRow(it, "document-open-recent-symbolic")) }
        suggestions.visible = vm.searchHistory.value.isNotEmpty()
    }

    private fun showSuggestions(state: SearchScreenState) {
        suggestions.clear()
        state.suggestQueries.take(8).forEach { suggestions.append(queryRow(it, "system-search-symbolic")) }
        state.suggestYTItems.take(6).forEach { resultRow(it)?.let(suggestions::append) }
        suggestions.visible = state.suggestQueries.isNotEmpty() || state.suggestYTItems.isNotEmpty()
    }

    private fun showResults(state: SearchScreenState) {
        val items: List<SearchResultType> =
            when (SearchType.valueOf(filters.activeName ?: SearchType.ALL.name)) {
                SearchType.SONGS -> state.searchSongsResult
                SearchType.VIDEOS -> state.searchVideosResult
                SearchType.ALBUMS -> state.searchAlbumsResult
                SearchType.ARTISTS -> state.searchArtistsResult
                SearchType.PLAYLISTS -> state.searchPlaylistsResult
                else -> state.searchAllResult
            }
        results.clear()
        items.forEach { resultRow(it)?.let(results::append) }
        stack.visibleChildName = if (items.isEmpty()) "empty" else "results"
    }

    private fun resultRow(item: SearchResultType): ActionRow? =
        when (item) {
            is SongsResult ->
                trackRow(item.title ?: "", tr(Res.string.Song_and_artist_name, item.artists?.toListName()?.connectArtists().orEmpty()), item.thumbnails.bestUrl(), scope, item.duration) {
                    app.playRadio(item.toTrack(), "\"$query\"")
                }
            is VideosResult ->
                trackRow(item.title, gtr("video_by", item.artists?.toListName()?.connectArtists().orEmpty()), item.thumbnails.bestUrl(), scope, item.duration) {
                    app.playRadio(item.toTrack(), "\"$query\"")
                }
            is AlbumsResult ->
                trackRow(item.title, "${item.type} • " + item.artists.toListName().connectArtists(), item.thumbnails.bestUrl(), scope) {
                    app.openAlbum(item.browseId)
                }
            is ArtistsResult ->
                trackRow(item.artist, gtr("artist"), item.thumbnails.bestUrl(), scope, round = true) { app.openArtist(item.browseId) }
            is PlaylistsResult ->
                trackRow(item.title, tr(Res.string.playlist_and_author, item.author), item.thumbnails.bestUrl(), scope) { app.openPlaylist(item.browseId) }
            else -> null
        }
}
