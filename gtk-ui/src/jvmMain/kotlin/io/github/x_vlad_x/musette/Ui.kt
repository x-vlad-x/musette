// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x_vlad_x.musette

import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.gnome.adw.ActionRow
import org.gnome.adw.Clamp
import org.gnome.adw.Spinner
import org.gnome.gdk.Display
import org.gnome.gdk.Texture
import org.gnome.gdkpixbuf.PixbufLoader
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.Button
import org.gnome.gtk.ContentFit
import org.gnome.gtk.CssProvider
import org.gnome.gtk.Gtk
import org.gnome.gtk.Label
import org.gnome.gtk.ListBox
import org.gnome.gtk.Orientation
import org.gnome.gtk.Overflow
import org.gnome.gtk.Picture
import org.gnome.gtk.PolicyType
import org.gnome.gtk.ScrolledWindow
import org.gnome.gtk.SelectionMode
import org.gnome.gtk.Widget
import org.gnome.pango.EllipsizeMode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

private const val CSS = """
.cover { border-radius: 8px; }
.cover.small { border-radius: 6px; }
.cover.round { border-radius: 50%; }
.cover.large { border-radius: 12px; }
.card-button { padding: 4px; border-radius: 12px; }
.mini-player { padding: 6px 10px 8px 10px; }
.mini-progress trough, .mini-progress progress { min-height: 3px; }
.now-playing-title { font-size: 1.35em; font-weight: 800; }
.big-play { min-width: 64px; min-height: 64px; }
.big-play image { -gtk-icon-size: 28px; }
.liked { color: @accent_color; }
.queue-current { font-weight: 800; color: @accent_color; }
.section-header { margin-top: 6px; }
"""

fun installCss() {
    val provider = CssProvider()
    provider.loadFromString(CSS)
    Gtk.styleContextAddProviderForDisplay(Display.getDefault(), provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
}

/** Largest thumbnail, which is what SimpMusic's own UI shows. */
fun List<Thumbnail>?.bestUrl(): String? = this?.maxByOrNull { it.width * it.height }?.url ?: this?.lastOrNull()?.url

fun List<Thumbnail>?.isWide(): Boolean = this?.firstOrNull()?.let { it.width != it.height } ?: false

/** Thumbnails: memory cache on the GTK thread, disk cache, at most six downloads at a time. */
object Images {
    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build()
    private val memory =
        object : LinkedHashMap<String, Texture>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Texture>?) = size > 400
        }
    private val permits = Semaphore(6)
    private val diskDir: Path =
        Path.of(System.getenv("XDG_CACHE_HOME") ?: (System.getenv("HOME") + "/.cache"), "musette", "thumbs")

    fun load(
        url: String?,
        picture: Picture,
        scope: CoroutineScope,
    ) = texture(url, scope) { picture.paintable = it }

    /** Calls [onReady] on the GTK thread with the texture for [url] (at once if it is cached). */
    fun texture(
        url: String?,
        scope: CoroutineScope,
        onReady: (Texture) -> Unit,
    ) {
        if (url.isNullOrEmpty()) return
        memory[url]?.let {
            onReady(it)
            return
        }
        scope.launch {
            val texture = withContext(Dispatchers.IO) { permits.withPermit { fetch(url) } } ?: return@launch
            memory[url] = texture
            onReady(texture)
        }
    }

    private fun fetch(url: String): Texture? =
        runCatching {
            Files.createDirectories(diskDir)
            val file = diskDir.resolve(sha1(url))
            val bytes =
                if (Files.exists(file)) {
                    Files.readAllBytes(file)
                } else {
                    val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).GET().build()
                    val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
                    if (response.statusCode() != 200) return null
                    response.body().also { Files.write(file, it) }
                }
            decode(bytes)
        }.getOrNull()

    // GdkTexture reads PNG/JPEG/TIFF itself; anything else (WebP) goes through gdk-pixbuf's loaders.
    private fun decode(bytes: ByteArray): Texture? =
        runCatching { Texture.fromBytes(bytes) }.getOrNull()
            ?: runCatching {
                val loader = PixbufLoader()
                loader.write(bytes)
                loader.close()
                @Suppress("DEPRECATION")
                Texture.forPixbuf(loader.pixbuf)
            }.getOrNull()

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

fun label(
    text: String,
    vararg css: String,
    ellipsize: Boolean = true,
    wrap: Boolean = false,
    xalign: Float = 0f,
): Label =
    Label(text).apply {
        this.xalign = xalign
        if (wrap) {
            this.wrap = true
            justify = if (xalign == 0.5f) org.gnome.gtk.Justification.CENTER else org.gnome.gtk.Justification.LEFT
        } else if (ellipsize) {
            this.ellipsize = EllipsizeMode.END
        }
        css.forEach { addCssClass(it) }
    }

/** A picture of a fixed size whose natural size doesn't follow the (much larger) thumbnail. */
fun cover(
    width: Int,
    height: Int,
    url: String?,
    scope: CoroutineScope,
    vararg css: String,
): Widget {
    val picture =
        Picture().apply {
            canShrink = true
            contentFit = ContentFit.COVER
            setSizeRequest(width, height)
            overflow = Overflow.HIDDEN
            addCssClass("cover")
            css.forEach { addCssClass(it) }
        }
    Images.load(url, picture, scope)
    return Clamp().apply {
        maximumSize = width
        tighteningThreshold = width
        halign = Align.START
        valign = Align.START
        child = picture
    }
}

/** A Home-style card: artwork with a title and a subtitle under it. */
fun card(
    title: String,
    subtitle: String?,
    thumbUrl: String?,
    scope: CoroutineScope,
    wide: Boolean = false,
    round: Boolean = false,
    onClick: () -> Unit,
): Widget {
    val width = if (wide) 240 else 150
    val height = if (wide) 135 else 150
    val box =
        Box(Orientation.VERTICAL, 4).apply {
            append(if (round) cover(width, height, thumbUrl, scope, "round") else cover(width, height, thumbUrl, scope))
            append(label(title, "heading").apply { maxWidthChars = 1; halign = Align.FILL })
            if (!subtitle.isNullOrBlank()) append(label(subtitle, "caption", "dim-label").apply { maxWidthChars = 1 })
        }
    val button =
        Button().apply {
            child = box
            addCssClass("flat")
            addCssClass("card-button")
            onClicked { onClick() }
        }
    return Clamp().apply {
        maximumSize = width + 8
        tighteningThreshold = width + 8
        valign = Align.START
        child = button
    }
}

/** Horizontally scrolling shelf of cards. */
fun shelf(cards: List<Widget>): Widget {
    val row = Box(Orientation.HORIZONTAL, 2)
    cards.forEach { row.append(it) }
    return ScrolledWindow().apply {
        setPolicy(PolicyType.AUTOMATIC, PolicyType.NEVER)
        propagateNaturalHeight = true
        child = row
    }
}

fun boxedList(): ListBox =
    ListBox().apply {
        selectionMode = SelectionMode.NONE
        addCssClass("boxed-list")
    }

/** A list row with a small artwork, two lines of text and an optional trailing label. */
fun trackRow(
    title: String,
    subtitle: String?,
    thumbUrl: String?,
    scope: CoroutineScope,
    trailing: String? = null,
    round: Boolean = false,
    prefixText: String? = null,
    onClick: () -> Unit,
): ActionRow =
    ActionRow().apply {
        useMarkup = false
        this.title = title
        if (!subtitle.isNullOrBlank()) this.subtitle = subtitle
        titleLines = 1
        subtitleLines = 1
        activatable = true
        if (prefixText != null) {
            addPrefix(label(prefixText, "dim-label", "numeric", xalign = 1f).apply { widthChars = 2 })
        } else {
            addPrefix(if (round) cover(44, 44, thumbUrl, scope, "small", "round") else cover(44, 44, thumbUrl, scope, "small"))
        }
        if (!trailing.isNullOrBlank()) addSuffix(label(trailing, "dim-label", "numeric").apply { valign = Align.CENTER })
        onActivated { onClick() }
    }

fun spinner(): Widget =
    Spinner().apply {
        setSizeRequest(48, 48)
        halign = Align.CENTER
        valign = Align.CENTER
        vexpand = true
    }

fun pillButton(
    text: String,
    icon: String,
    suggested: Boolean,
    onClick: () -> Unit,
): Button =
    Button().apply {
        child =
            org.gnome.adw.ButtonContent().apply {
                label = text
                iconName = icon
            }
        addCssClass("pill")
        if (suggested) addCssClass("suggested-action")
        onClicked { onClick() }
    }

fun iconButton(
    icon: String,
    tooltip: String,
    vararg css: String,
    onClick: () -> Unit,
): Button =
    Button.fromIconName(icon).apply {
        tooltipText = tooltip
        valign = Align.CENTER
        css.forEach { addCssClass(it) }
        onClicked { onClick() }
    }

fun Box.clear() {
    var child = firstChild
    while (child != null) {
        val next = child.nextSibling
        remove(child)
        child = next
    }
}

fun ListBox.clear() = removeAll()

fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
