package de.davscha.denkwerk.ui

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Ist der Code-Block eine vollständige HTML-Seite, die man ausprobieren kann? */
fun looksLikeHtmlPage(language: String, code: String): Boolean {
    val lang = language.lowercase()
    val t = code.trimStart().lowercase()
    val isHtml = lang in setOf("html", "htm", "xhtml") || t.startsWith("<!doctype html") || t.startsWith("<html")
    return isHtml && (t.contains("<body") || t.contains("<html") || t.contains("<div"))
}

/** Vollbild-Vorschau einer HTML-Seite aus dem Chat – mit JavaScript, zum Testen. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlPreviewDialog(html: String, onDismiss: () -> Unit) {
    var reloadKey by remember { mutableIntStateOf(0) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            // Oben: Schließen, Titel, Neu laden
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF222226)).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Schließen", tint = Color.White) }
                Text("Vorschau", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { reloadKey++ }) { Icon(Icons.Default.Refresh, "Neu laden", tint = Color.White) }
            }

            Box(Modifier.fillMaxSize().background(Color.White)) {
                // reloadKey im key → bei „Neu laden“ wird die Seite frisch aufgebaut (Zustand zurückgesetzt)
                androidx.compose.runtime.key(reloadKey) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                // Volle Größe statt „wrap_content“ – sonst ist 100vh im WebView falsch
                                // und Seiten mit height:100vh + overflow:hidden (z. B. Spiele) bleiben schwarz
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                settings.javaScriptEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = false // Web-Audio-Töne in Spielen
                                settings.domStorageEnabled = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                webViewClient = WebViewClient()
                                webChromeClient = WebChromeClient() // alert(), confirm() usw.
                                // Basis-URL, damit Anker (#demo), CDN-Skripte und localStorage funktionieren
                                loadDataWithBaseURL("https://denkwerk.local/", html, "text/html", "utf-8", null)
                            }
                        },
                        onRelease = { it.destroy() },
                    )
                }
            }
        }
    }
}
