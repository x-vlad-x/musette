// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.x_vlad_x.musette

import com.maxrave.common.Config
import com.maxrave.domain.data.entities.GoogleAccountEntity
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.simpmusic.viewModel.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.gnome.adw.AlertDialog
import org.gnome.adw.Avatar
import org.gnome.adw.Clamp
import org.gnome.adw.ResponseAppearance
import org.gnome.adw.StatusPage
import org.gnome.gtk.Align
import org.gnome.gtk.Box
import org.gnome.gtk.Button
import org.gnome.gtk.Entry
import org.gnome.gtk.Image
import org.gnome.gtk.Orientation
import org.gnome.gtk.PolicyType
import org.gnome.gtk.ScrolledWindow
import org.gnome.gtk.Stack
import org.gnome.gtk.Widget
import org.koin.mp.KoinPlatform.getKoin
import org.webkitgtk.LoadEvent
import org.webkitgtk.NetworkSession
import org.webkitgtk.WebView
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.add_an_account
import simpmusic.composeapp.generated.resources.cancel
import simpmusic.composeapp.generated.resources.log_in
import simpmusic.composeapp.generated.resources.log_in_to_YouTube
import simpmusic.composeapp.generated.resources.log_out
import simpmusic.composeapp.generated.resources.logged_in_as
import simpmusic.composeapp.generated.resources.logged_out
import simpmusic.composeapp.generated.resources.login_failed
import simpmusic.composeapp.generated.resources.manage_your_youtube_accounts
import simpmusic.composeapp.generated.resources.signed_in
import simpmusic.composeapp.generated.resources.your_youtube_cookie
import simpmusic.composeapp.generated.resources.youtube_account

/**
 * YouTube sign-in, the way SimpMusic's own LogInScreen does it: Google's sign-in page in a web view,
 * and once it lands on music.youtube.com the site's cookies go to SettingsViewModel.addAccount,
 * which fetches the account, stores it and turns on authenticated requests. Switching and signing
 * out reuse SettingsViewModel.setUsedAccount, like the Settings screen.
 */
class Account(
    private val app: AppWindow,
    private val scope: CoroutineScope,
) {
    private val settingsVm: SettingsViewModel = getKoin().get()
    private val dataStore: DataStoreManager = getKoin().get()
    private val headerAvatar = Avatar(28, null, false)
    private val signInTitle = tr(Res.string.log_in)

    val headerButton: Widget =
        Button().apply {
            child = headerAvatar
            addCssClass("flat")
            addCssClass("circular")
            tooltipText = tr(Res.string.youtube_account)
            onClicked { openAccountPage() }
        }

    init {
        settingsVm.getAllGoogleAccount()
        scope.launch {
            combine(dataStore.loggedIn, dataStore.getString("AccountName"), dataStore.getString("AccountThumbUrl")) { loggedIn, name, thumb ->
                Triple(loggedIn == DataStoreManager.TRUE, name.orEmpty(), thumb.orEmpty())
            }.collect { (loggedIn, name, thumb) ->
                headerAvatar.text = if (loggedIn) name else null
                headerAvatar.showInitials = loggedIn && name.isNotBlank()
                headerAvatar.customImage = null
                if (loggedIn && thumb.isNotBlank()) Images.texture(thumb, scope) { headerAvatar.customImage = it }
            }
        }
    }

    fun openAccountPage() = app.push(tr(Res.string.youtube_account)) { pageScope, _ -> accountPage(pageScope) }

    private fun accountPage(pageScope: CoroutineScope): Widget {
        val stack = Stack()
        val signedOut =
            StatusPage().apply {
                iconName = "avatar-default-symbolic"
                title = tr(Res.string.log_in_to_YouTube)
                description = gtr("sign_in_description")
                child =
                    Box(Orientation.VERTICAL, 12).apply {
                        halign = Align.CENTER
                        append(pillButton(gtr("sign_in_with_google"), "web-browser-symbolic", true) { openLogin() })
                        append(
                            Button.withLabel(gtr("sign_in_with_cookie")).apply {
                                addCssClass("flat")
                                onClicked { askForCookie() }
                            },
                        )
                    }
            }
        val column =
            Box(Orientation.VERTICAL, 12).apply {
                marginTop = 18
                marginBottom = 24
                marginStart = 12
                marginEnd = 12
            }
        val signedIn =
            ScrolledWindow().apply {
                setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC)
                child = Clamp().apply { maximumSize = 600; child = column }
            }
        stack.addNamed(signedOut, "out")
        stack.addNamed(signedIn, "in")

        pageScope.launch {
            combine(settingsVm.googleAccounts, dataStore.loggedIn) { accounts, loggedIn -> accounts.data.orEmpty() to (loggedIn == DataStoreManager.TRUE) }
                .collect { (accounts, loggedIn) ->
                    val current = accounts.firstOrNull { it.isUsed }
                    if (!loggedIn || current == null) {
                        stack.visibleChildName = "out"
                    } else {
                        renderSignedIn(column, accounts, current, pageScope)
                        stack.visibleChildName = "in"
                    }
                }
        }
        return stack
    }

    private fun renderSignedIn(
        column: Box,
        accounts: List<GoogleAccountEntity>,
        current: GoogleAccountEntity,
        pageScope: CoroutineScope,
    ) {
        column.clear()
        val bigAvatar = Avatar(96, current.name, true).apply { halign = Align.CENTER }
        Images.texture(current.thumbnailUrl, pageScope) { bigAvatar.customImage = it }
        column.append(bigAvatar)
        column.append(label(current.name, "title-2", xalign = 0.5f))
        if (current.email.isNotBlank()) column.append(label(current.email, "dim-label", xalign = 0.5f))

        column.append(label(tr(Res.string.manage_your_youtube_accounts), "heading").apply { marginTop = 12 })
        val list = boxedList()
        accounts.forEach { account ->
            val row =
                trackRow(account.name, account.email, account.thumbnailUrl, pageScope, round = true) {
                    if (!account.isUsed) {
                        settingsVm.setUsedAccount(account)
                        accountChanged(tr(Res.string.logged_in_as, account.name))
                    }
                }
            if (account.isUsed) row.addSuffix(Image.fromIconName("object-select-symbolic").apply { valign = Align.CENTER })
            list.append(row)
        }
        column.append(list)
        column.append(
            // One above the other: translated labels are too long to sit side by side on a phone.
            Box(Orientation.VERTICAL, 12).apply {
                halign = Align.CENTER
                homogeneous = true
                marginTop = 12
                append(pillButton(tr(Res.string.add_an_account), "list-add-symbolic", false) { openLogin() })
                append(
                    pillButton(tr(Res.string.log_out), "system-log-out-symbolic", false) {
                        settingsVm.setUsedAccount(null)
                        accountChanged(tr(Res.string.logged_out))
                    }.apply { addCssClass("destructive-action") },
                )
            },
        )
    }

    fun openLogin() =
        app.push(signInTitle) { pageScope, _ ->
            // Ephemeral session: every sign-in starts without cookies, like the Android app clearing them.
            val webView = WebView.builder().setNetworkSession(NetworkSession.ephemeral()).build()
            webView.vexpand = true
            var handled = false
            webView.onLoadChanged { event ->
                val uri = webView.uri ?: return@onLoadChanged
                if (handled || event == LoadEvent.STARTED || !uri.startsWith(Config.YOUTUBE_MUSIC_MAIN_URL)) return@onLoadChanged
                handled = true
                webView.networkSession.cookieManager.getCookies(Config.YOUTUBE_MUSIC_MAIN_URL, null) { result ->
                    val cookie =
                        runCatching {
                            webView.networkSession.cookieManager
                                .getCookiesFinish(result)
                                .filterNotNull()
                                .joinToString("; ") { "${it.name}=${it.value}" }
                        }.getOrDefault("")
                    pageScope.launch { finishSignIn(cookie) }
                }
            }
            webView.loadUri(Config.LOG_IN_URL)
            webView
        }

    private fun askForCookie() {
        val entry = Entry().apply { placeholderText = "SAPISID=…; __Secure-3PSID=…" }
        val dialog =
            AlertDialog(tr(Res.string.your_youtube_cookie), gtr("cookie_hint")).apply {
                extraChild = entry
                addResponse("cancel", tr(Res.string.cancel))
                addResponse("sign-in", tr(Res.string.log_in))
                setResponseAppearance("sign-in", ResponseAppearance.SUGGESTED)
                defaultResponse = "sign-in"
                closeResponse = "cancel"
            }
        dialog.onResponse(null) { response ->
            if (response == "sign-in" && entry.text.isNotBlank()) scope.launch { finishSignIn(entry.text.trim()) }
        }
        dialog.present(app.window)
    }

    private suspend fun finishSignIn(cookie: String) {
        val success = cookie.contains("SAPISID") && withContext(Dispatchers.IO) { settingsVm.addAccount(cookie) }
        // Leave the sign-in page (the account page under it shows the new account).
        if (app.nav.visiblePage?.title == signInTitle) app.nav.pop()
        if (success) {
            settingsVm.getAllGoogleAccount()
            accountChanged(tr(Res.string.signed_in))
        } else {
            app.toast(tr(Res.string.login_failed))
        }
    }

    private fun accountChanged(message: String) {
        app.toast(message)
        app.onAccountChanged()
    }
}
