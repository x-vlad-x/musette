package io.github.x_vlad_x.simpmusicgtk

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.internal.MainDispatcherFactory
import org.gnome.glib.GLib
import org.gnome.glib.MainContext
import kotlin.coroutines.CoroutineContext

/**
 * Dispatchers.Main on the GLib main loop, so every ViewModel's viewModelScope — and every
 * collector that touches a widget — runs on GTK's thread. Replaces the Swing dispatcher
 * composeApp brings along (found through ServiceLoader; higher loadPriority wins).
 */
class GtkMainDispatcher(
    private val isImmediate: Boolean = false,
) : MainCoroutineDispatcher() {
    override val immediate: MainCoroutineDispatcher
        get() = if (isImmediate) this else ImmediateHolder.instance

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !isImmediate || !onGtkThread()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        GLib.idleAddOnce { block.run() }
    }

    override fun toString(): String = if (isImmediate) "Dispatchers.Main.immediate[GTK]" else "Dispatchers.Main[GTK]"

    private object ImmediateHolder {
        val instance = GtkMainDispatcher(isImmediate = true)
    }

    companion object {
        fun onGtkThread(): Boolean = MainContext.default_().isOwner()
    }
}

@OptIn(InternalCoroutinesApi::class)
class GtkMainDispatcherFactory : MainDispatcherFactory {
    override val loadPriority: Int = Int.MAX_VALUE

    override fun createDispatcher(allFactories: List<MainDispatcherFactory>): MainCoroutineDispatcher = GtkMainDispatcher()
}
