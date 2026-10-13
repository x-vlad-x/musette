package io.github.x_vlad_x.simpmusicgtk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.maxrave.common.Config
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.home.Content
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.utils.toTrack
import com.maxrave.simpmusic.ui.screen.library.LibraryDynamicPlaylistType
import com.maxrave.simpmusic.viewModel.SharedViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import multiplatform.network.cmptoast.ToastManager
import org.gnome.adw.Application
import org.gnome.adw.ApplicationWindow
import org.gnome.adw.Breakpoint
import org.gnome.adw.BreakpointCondition
import org.gnome.adw.HeaderBar
import org.gnome.adw.NavigationPage
import org.gnome.adw.NavigationView
import org.gnome.adw.Toast
import org.gnome.adw.ToastOverlay
import org.gnome.adw.ToolbarView
import org.gnome.adw.ViewStack
import org.gnome.adw.ViewSwitcher
import org.gnome.adw.ViewSwitcherBar
import org.gnome.adw.ViewSwitcherPolicy
import org.gnome.adw.WindowTitle
import org.gnome.glib.GLib
import org.gnome.gtk.Widget
import org.koin.mp.KoinPlatform.getKoin
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.album
import simpmusic.composeapp.generated.resources.home
import simpmusic.composeapp.generated.resources.library
import simpmusic.composeapp.generated.resources.most_played
import simpmusic.composeapp.generated.resources.playlist
import simpmusic.composeapp.generated.resources.search

/**
 * The window and everything every page needs: navigation, playback shortcuts and toasts.
 *
 * Layout: ToastOverlay › BottomSheet (content: NavigationView, bottom bar: mini player,
 * sheet: Now Playing). The root page holds a ViewStack (Home, Search) switched from the header.
 */
class AppWindow(
    app: Application,
) {
    val shared: SharedViewModel = getKoin().get()
    val window = ApplicationWindow(app)
    val nav = NavigationView()
    private val toasts = ToastOverlay()
    private val pageCleanups = HashMap<NavigationPage, () -> Unit>()
    private val accountListeners = mutableListOf<() -> Unit>()

    init {
        window.title = "SimpMusic"
        window.setDefaultSize(420, 860)
        window.setSizeRequest(360, 500)

        val stack = ViewStack()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val account = Account(this, appScope)
        stack.addTitledWithIcon(HomePage(this, appScope).widget, "home", tr(Res.string.home), "go-home-symbolic")
        stack.addTitledWithIcon(SearchPage(this, appScope).widget, "search", tr(Res.string.search), "system-search-symbolic")
        val library = LibraryPage(this, appScope)
        stack.addTitledWithIcon(library.widget, "library", tr(Res.string.library), "folder-music-symbolic")
        stack.onNotify("visible-child-name") { if (stack.visibleChildName == "library") library.onShown() }

        val switcher =
            ViewSwitcher().apply {
                this.stack = stack
                policy = ViewSwitcherPolicy.WIDE
            }
        val narrowTitle = WindowTitle("SimpMusic", "")
        val header =
            HeaderBar().apply {
                packEnd(account.headerButton)
                titleWidget = switcher
            }
        // On a phone-width window the tabs move to a bar at the bottom, as in GNOME's mobile apps.
        val switcherBar = ViewSwitcherBar().apply { this.stack = stack }
        val root =
            ToolbarView().apply {
                addTopBar(header)
                addBottomBar(switcherBar)
                content = stack
            }
        window.addBreakpoint(
            Breakpoint(BreakpointCondition.parse("max-width: 550sp")).apply {
                onApply {
                    header.titleWidget = narrowTitle
                    switcherBar.reveal = true
                }
                onUnapply {
                    switcherBar.reveal = false
                    header.titleWidget = switcher
                }
            },
        )
        nav.add(NavigationPage(root, "SimpMusic").apply { tag = "root" })
        nav.onPopped { page -> pageCleanups.remove(page)?.invoke() }

        val player = Player(this, appScope)
        player.sheet.content = nav
        // The mini player floats over the content; keep the content (and the bottom tab bar) clear of it.
        val padForMiniPlayer = { nav.marginBottom = player.sheet.bottomBarHeight }
        player.sheet.onNotify("bottom-bar-height") { padForMiniPlayer() }
        padForMiniPlayer()
        toasts.child = player.sheet
        window.content = toasts

        // Debug hook for testing without a touchscreen: SIMPMUSIC_GTK_OPEN=account|login|library|mostplayed|playlist:<id>
        val open = System.getenv("SIMPMUSIC_GTK_OPEN").orEmpty()
        when {
            open == "account" -> account.openAccountPage()
            open == "login" -> account.openLogin()
            open == "library" -> stack.visibleChildName = "library"
            open == "mostplayed" -> library.openSongs(LibraryDynamicPlaylistType.MostPlayed, tr(Res.string.most_played))
            open.startsWith("playlist:") -> openPlaylist(open.removePrefix("playlist:"))
        }

        // cmptoast keeps the ViewModels' messages in a Compose state; show them as Adwaita toasts.
        GLib.timeoutAdd(GLib.PRIORITY_DEFAULT, 400) {
            ToastManager.currentToast.value?.let {
                toast(it.message)
                ToastManager.dismissToast()
            }
            true
        }
    }

    fun addAccountListener(listener: () -> Unit) {
        accountListeners += listener
    }

    /** Sign-in, sign-out or account switch: pages showing personal content reload. */
    fun onAccountChanged() {
        // setUsedAccount applies the cookie asynchronously; give it a moment before refetching.
        GLib.timeoutAddOnce(1500) { accountListeners.forEach { it() } }
    }

    fun toast(message: String) {
        toasts.addToast(Toast(message).apply { timeout = 3 })
    }

    /** Pushes a page with its own coroutine scope and ViewModels, both released when it is popped. */
    fun push(
        title: String,
        build: (scope: CoroutineScope, store: PageViewModels) -> Widget,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val store = PageViewModels()
        val body = build(scope, store)
        val page =
            NavigationPage(
                ToolbarView().apply {
                    addTopBar(HeaderBar())
                    content = body
                },
                title,
            )
        pageCleanups[page] = {
            scope.cancel()
            store.clear()
        }
        nav.push(page)
    }

    fun openAlbum(browseId: String) = push(tr(Res.string.album)) { scope, vms -> AlbumPage(this, scope, vms, browseId).widget }

    fun openPlaylist(playlistId: String) = push(tr(Res.string.playlist)) { scope, vms -> PlaylistPage(this, scope, vms, playlistId).widget }

    fun openArtist(channelId: String) = push(gtr("artist")) { scope, vms -> ArtistPage(this, scope, vms, channelId).widget }

    /** Plays a track followed by its YouTube Music radio — what tapping a song does in SimpMusic. */
    fun playRadio(
        track: Track,
        queueName: String = track.title,
    ) {
        shared.setQueueData(
            QueueData.Data(
                listTracks = arrayListOf(track),
                firstPlayedTrack = track,
                playlistId = "RDAMVM${track.videoId}",
                playlistName = queueName,
                playlistType = PlaylistType.RADIO,
                continuation = null,
            ),
        )
        shared.loadMediaItem(track, Config.SONG_CLICK)
    }

    /** Routes a Home card the way SimpMusic's HomeContentCard does. */
    fun openContent(content: Content) {
        val playlistId = content.playlistId
        val browseId = content.browseId
        val noVideo = content.videoId.isNullOrEmpty()
        when {
            playlistId != null && noVideo -> if (playlistId.startsWith("UC")) openArtist(playlistId) else openPlaylist(playlistId)
            browseId != null && noVideo ->
                when {
                    browseId.startsWith("UC") -> openArtist(browseId)
                    browseId.startsWith("MPSP") -> toast(gtr("podcasts_unsupported"))
                    else -> openAlbum(browseId)
                }
            else -> playRadio(content.toTrack(), content.title)
        }
    }
}

/** The ViewModels one page created; cleared (onCleared → viewModelScope cancelled) when it closes. */
class PageViewModels {
    private val store = ViewModelStore()
    private var count = 0

    fun <T : ViewModel> add(viewModel: T): T {
        store.put("vm${count++}", viewModel)
        return viewModel
    }

    fun clear() = store.clear()
}
