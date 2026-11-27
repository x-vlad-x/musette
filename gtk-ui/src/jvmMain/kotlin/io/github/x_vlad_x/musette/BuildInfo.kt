// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x_vlad_x.musette

import java.util.Properties

object BuildInfo {
    private val revisions =
        Properties().apply {
            requireNotNull(BuildInfo::class.java.getResourceAsStream("/musette/upstream.env")).use { load(it) }
        }

    val version: String =
        requireNotNull(BuildInfo::class.java.getResourceAsStream("/musette/VERSION"))
            .bufferedReader()
            .use { it.readText().trim() }
    val simpmusicTag: String = revisions.getProperty("SIMPMUSIC_TAG")
    val simpmusicCommit: String = revisions.getProperty("SIMPMUSIC_COMMIT")
    val coreCommit: String = revisions.getProperty("CORE_COMMIT")

    fun summary(): String = "Musette $version (SimpMusic $simpmusicTag, core ${coreCommit.take(12)})"

    fun sourceRevisions(): String = "Musette $version\nSimpMusic $simpmusicTag $simpmusicCommit\ncore $coreCommit"
}
