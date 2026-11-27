// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x_vlad_x.musette

import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import java.util.ResourceBundle

/*
 * UI strings. Almost all of them are SimpMusic's own (Res.string.*), so the interface comes in every
 * language SimpMusic is translated into and follows the language chosen in its settings (Main.kt
 * makes it the default locale). The few strings SimpMusic has no equivalent for are in
 * resources/strings/gtk*.properties; a language without its own file gets the English ones.
 */

fun tr(
    res: StringResource,
    vararg args: Any,
): String = runBlocking { getString(res, *args) }

fun tr(
    res: PluralStringResource,
    count: Int,
): String =
    // Compose fills in only positional "%1$d"; some of SimpMusic's plurals (n_song) use a bare "%d".
    runBlocking { getPluralString(res, count, count) }.replace("%d", count.toString())

private val gtkStrings: ResourceBundle by lazy { ResourceBundle.getBundle("strings/gtk") }

/** A string of this front-end that SimpMusic doesn't have (see resources/strings/gtk.properties). */
fun gtr(
    key: String,
    vararg args: Any,
): String {
    val text = gtkStrings.getString(key)
    return if (args.isEmpty()) text else text.format(*args)
}
