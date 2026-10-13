package io.github.x_vlad_x.simpmusicgtk

import com.maxrave.common.AppIdentity
import com.maxrave.common.SELECTED_LANGUAGE
import com.maxrave.common.SUPPORTED_LANGUAGE
import com.maxrave.common.SUPPORTED_LOCATION
import com.maxrave.data.di.loader.loadAllModules
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.simpmusic.BuildKonfig
import com.maxrave.simpmusic.di.viewModelModule
import com.maxrave.simpmusic.viewModel.changeLanguageNative
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.gnome.adw.Application
import org.gnome.gio.ApplicationFlags
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform.getKoin
import java.util.Locale

const val APP_ID = "io.github.x_vlad_x.SimpMusicGtk"

fun main(args: Array<String>) {
    // libhybris devices (FuriOS, Droidian…): the GPU driver lives under /android, which WebKit's
    // bubblewrap sandbox hides, so the web process dies loading libEGL and the sign-in page stays
    // blank. That web view only ever shows Google's sign-in pages.
    if (java.io.File("/android/system").isDirectory) setEnv("WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS", "1")
    startKoin {
        loadAllModules(
            AppIdentity(
                applicationId = "com.maxrave.simpmusic",
                versionName = BuildKonfig.versionName,
                platform = "${System.getProperty("os.name")} ${System.getProperty("os.version")} (GTK)",
            ),
        )
        loadKoinModules(viewModelModule)
    }
    changeLanguageNative(followSystemLanguage(getKoin().get()))

    // GApplication is single-instance by itself: a second launch just re-activates this one.
    val app = Application(APP_ID, ApplicationFlags.DEFAULT_FLAGS)
    var window: AppWindow? = null
    // gtk_init runs setlocale(LC_ALL, ""), which undoes the LC_NUMERIC=C that MpvLibrary set when
    // it loaded — and libmpv's mpv_create() returns NULL under any other numeric locale.
    app.onStartup { forceCNumericLocale() }
    app.onActivate {
        forceCNumericLocale()
        if (window == null) {
            installCss()
            window = AppWindow(app)
        }
        window!!.window.present()
    }
    app.run(args)
}

/**
 * This interface has no language setting, so SimpMusic follows the system language like any GNOME
 * app: it is saved as SimpMusic's own choice, which also sets the language of YouTube's content.
 * A language SimpMusic doesn't support falls back to English. The content region (SimpMusic's
 * default is Vietnam) is taken from the system locale once, while it has never been chosen.
 */
private fun followSystemLanguage(dataStore: DataStoreManager): String {
    val system = Locale.getDefault()
    val code =
        when {
            system.language == "zh" && (system.script == "Hant" || system.country in setOf("TW", "HK", "MO")) -> "zh-Hant-TW"
            system.language == "zh" -> "zh-CN"
            else -> SUPPORTED_LANGUAGE.codes.firstOrNull { Locale.forLanguageTag(it).language == system.language }
        } ?: SUPPORTED_LANGUAGE.codes.first()
    runBlocking {
        if (dataStore.getString(SELECTED_LANGUAGE).first() != code) dataStore.putString(SELECTED_LANGUAGE, code)
        if (dataStore.getString("location").first() == null && system.country in SUPPORTED_LOCATION.items) {
            dataStore.setLocation(system.country)
        }
    }
    return code
}

/** setlocale(LC_NUMERIC, "C") through libc, so libmpv can create players after GTK set the locale. */
private fun forceCNumericLocale() {
    runCatching {
        val linker = java.lang.foreign.Linker.nativeLinker()
        val setlocale =
            linker.downcallHandle(
                linker.defaultLookup().find("setlocale").orElseThrow(),
                java.lang.foreign.FunctionDescriptor.of(
                    java.lang.foreign.ValueLayout.ADDRESS,
                    java.lang.foreign.ValueLayout.JAVA_INT,
                    java.lang.foreign.ValueLayout.ADDRESS,
                ),
            )
        java.lang.foreign.Arena.ofConfined().use { arena ->
            val lcNumeric = 1 // glibc
            setlocale.invokeWithArguments(lcNumeric, arena.allocateFrom("C"))
        }
    }.onFailure { System.err.println("[gtk] could not reset LC_NUMERIC: $it") }
}

/** setenv(3) through libc: must happen before WebKit spawns its web process. */
private fun setEnv(
    name: String,
    value: String,
) {
    runCatching {
        val linker = java.lang.foreign.Linker.nativeLinker()
        val setenv =
            linker.downcallHandle(
                linker.defaultLookup().find("setenv").orElseThrow(),
                java.lang.foreign.FunctionDescriptor.of(
                    java.lang.foreign.ValueLayout.JAVA_INT,
                    java.lang.foreign.ValueLayout.ADDRESS,
                    java.lang.foreign.ValueLayout.ADDRESS,
                    java.lang.foreign.ValueLayout.JAVA_INT,
                ),
            )
        java.lang.foreign.Arena.ofConfined().use { arena ->
            setenv.invokeWithArguments(arena.allocateFrom(name), arena.allocateFrom(value), 1)
        }
    }.onFailure { System.err.println("[gtk] could not set $name: $it") }
}
