package io.github.x_vlad_x.simpmusicgtk

import com.maxrave.domain.data.model.home.Content
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.utils.connectArtists
import com.maxrave.domain.utils.toListName
import com.maxrave.simpmusic.viewModel.HomeViewModel
import com.maxrave.simpmusic.viewModel.ListState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.gnome.adw.Clamp
import org.gnome.adw.StatusPage
import org.gnome.glib.GLib
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.Button
import org.gnome.gtk.Orientation
import org.gnome.gtk.PolicyType
import org.gnome.gtk.PositionType
import org.gnome.gtk.ScrolledWindow
import org.gnome.gtk.Stack
import org.gnome.gtk.StackTransitionType
import org.gnome.gtk.Widget
import org.koin.mp.KoinPlatform.getKoin
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.play_all
import simpmusic.composeapp.generated.resources.retry

class HomePage(
    private val app: AppWindow,
    private val scope: CoroutineScope,
) {
    private val vm: HomeViewModel = getKoin().get()
    private val sections = Box(Orientation.VERTICAL, 22)
    private val stack = Stack().apply { transitionType = StackTransitionType.CROSSFADE }
    private val scroller = ScrolledWindow()
    private var renderedKeys: List<String> = emptyList()
    val widget: Widget get() = stack

    init {
        app.addAccountListener {
            renderedKeys = emptyList()
            stack.visibleChildName = "loading"
            vm.getHomeItemList(vm.params.value)
        }
        sections.marginTop = 12
        sections.marginBottom = 24
        sections.marginStart = 12
        sections.marginEnd = 12
        scroller.apply {
                setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC)
                vexpand = true
                child =
                    Clamp().apply {
                        maximumSize = 1100
                        tighteningThreshold = 900
                        child = sections
                    }
            }
        // YouTube sends Home in pages: the next one is fetched at the bottom of the page.
        scroller.onEdgeReached { edge -> if (edge == PositionType.BOTTOM) loadMore() }
        stack.addNamed(spinner(), "loading")
        stack.addNamed(scroller, "content")
        stack.addNamed(
            StatusPage().apply {
                iconName = "network-error-symbolic"
                title = gtr("home_failed")
                description = gtr("check_connection")
                child =
                    Button.withLabel(tr(Res.string.retry)).apply {
                        halign = Align.CENTER
                        addCssClass("pill")
                        addCssClass("suggested-action")
                        onClicked { vm.getHomeItemList(vm.params.value) }
                    }
            },
            "error",
        )
        stack.visibleChildName = "loading"

        scope.launch {
            combine(vm.homeItemList, vm.newRelease) { home, releases -> home + releases }.collect { items ->
                if (items.isNotEmpty()) {
                    render(items)
                    stack.visibleChildName = "content"
                    // A first page shorter than the window would never reach its bottom edge.
                    GLib.idleAddOnce {
                        val adj = scroller.vadjustment
                        if (adj.upper <= adj.pageSize + 200) loadMore()
                    }
                }
            }
        }
        scope.launch {
            vm.loading.collect { loading ->
                if (!loading && vm.homeItemList.value.isEmpty()) stack.visibleChildName = "error"
            }
        }
    }

    private fun loadMore() {
        val state = vm.homeListState.value
        if (state == ListState.PAGINATING || state == ListState.PAGINATION_EXHAUST) return
        vm.continuation.value?.let { vm.getContinueHomeItem(it) }
    }

    private fun render(items: List<HomeItem>) {
        val keys = items.map { it.title }
        val appendOnly = renderedKeys.isNotEmpty() && keys.size > renderedKeys.size && keys.take(renderedKeys.size) == renderedKeys
        val toRender = if (appendOnly) items.drop(renderedKeys.size) else items
        if (!appendOnly) sections.clear()
        renderedKeys = keys
        for (item in toRender) {
            val contents = item.contents.filterNotNull()
            if (contents.isEmpty()) continue
            sections.append(sectionHeader(item))
            if (item.itemsPerColumn != null) {
                // Song shelves (Quick picks, Trending songs…): YouTube lays these out as lists.
                val list = boxedList()
                contents.take(8).forEach { content -> list.append(songRow(content)) }
                sections.append(list)
            } else {
                sections.append(shelf(contents.map { contentCard(it) }))
            }
        }
    }

    private fun sectionHeader(item: HomeItem): Widget {
        val titles =
            Box(Orientation.VERTICAL, 0).apply {
                hexpand = true
                item.subtitle?.takeIf { it.isNotBlank() }?.let { append(label(it.uppercase(), "caption", "dim-label")) }
                append(label(item.title, "title-3"))
            }
        return Box(Orientation.HORIZONTAL, 6).apply {
            addCssClass("section-header")
            append(titles)
            item.playAllEndpoint?.let { endpoint ->
                append(
                    Button.withLabel(tr(Res.string.play_all)).apply {
                        addCssClass("flat")
                        valign = Align.END
                        onClicked { vm.playFromEndpoint(endpoint, item.title) }
                    },
                )
            }
        }
    }

    private fun Content.subtitleText(): String? =
        artists?.toListName()?.connectArtists()?.takeIf { it.isNotBlank() } ?: description ?: views

    private fun songRow(content: Content) =
        trackRow(content.title, content.subtitleText(), content.thumbnails.bestUrl(), scope) { app.openContent(content) }

    private fun contentCard(content: Content): Widget {
        val opensPage = content.videoId.isNullOrEmpty()
        val isArtist = opensPage && ((content.browseId ?: content.playlistId)?.startsWith("UC") == true)
        return card(
            title = content.title,
            subtitle = content.subtitleText(),
            thumbUrl = content.thumbnails.bestUrl(),
            scope = scope,
            wide = !opensPage && content.thumbnails.isWide(),
            round = isArtist,
        ) { app.openContent(content) }
    }
}
