// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x_vlad_x.musette

import org.gnome.adw.AboutDialog
import org.gnome.gtk.License
import org.gnome.gtk.Widget

private const val SOURCE_URL = "https://github.com/x-vlad-x/simpmusic-gtk"

fun showAbout(parent: Widget) {
    AboutDialog().apply {
        applicationName = APP_NAME
        applicationIcon = APP_ID
        version = BuildInfo.version
        developerName = "Vladislav Shadiuk"
        developers = arrayOf("Vladislav Shadiuk")
        comments = gtr("about_description", BuildInfo.simpmusicTag, BuildInfo.coreCommit.take(12))
        copyright = "© 2026 Vladislav Shadiuk"
        licenseType = License.GPL_3_0_ONLY
        website = SOURCE_URL
        issueUrl = "$SOURCE_URL/issues"
        debugInfo = BuildInfo.sourceRevisions()
        addCreditSection(gtr("about_simpmusic"), arrayOf("maxrave-dev and contributors"))
        addAcknowledgementSection(
            gtr("about_libraries"),
            arrayOf(
                "Kotlin and Compose Multiplatform",
                "Kotlin Coroutines",
                "Koin",
                "Ktor",
                "Room and SQLite",
                "java-gi",
                "GTK and libadwaita",
                "WebKitGTK",
                "mpv",
                "QuickJS",
                "JNA",
                "PipePipeExtractor",
            ),
        )
    }.present(parent)
}
