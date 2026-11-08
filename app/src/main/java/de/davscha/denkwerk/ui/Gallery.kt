package de.davscha.denkwerk.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import de.davscha.denkwerk.data.WebImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.TravelExplore
import java.net.URLEncoder

typealias GalleryImage = WebImage

/** Öffnet die Slideshow – wird vom Chat-Bildschirm bereitgestellt */
val LocalOpenGallery = staticCompositionLocalOf<(List<GalleryImage>, Int) -> Unit> { { _, _ -> } }

/** Was Coil laden soll: Datei auf dem Gerät oder Web-Adresse */
private fun WebImage.model(thumbnail: Boolean): Any =
    if (local) File(url) else if (thumbnail && thumb.isNotBlank()) thumb else url

/**
 * Bild an Google Lens geben: Web-Bilder über lens.google.com, eigene Bilder per „Teilen“ an die Google-App
 * (dort öffnet sich Lens). Ohne Google-App erscheint die normale Teilen-Auswahl.
 */
fun openInLens(context: Context, img: GalleryImage) {
    try {
        if (!img.local) {
            val url = "https://lens.google.com/uploadbyurl?url=" + URLEncoder.encode(img.url, "UTF-8")
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(img.url))
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/*")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val google = Intent(send).setPackage("com.google.android.googlequicksearchbox")
        try {
            context.startActivity(google)
        } catch (e: ActivityNotFoundException) {
            context.startActivity(Intent.createChooser(send, "Bild suchen mit …").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Google Lens konnte nicht geöffnet werden", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** Waagerechte Bilderreihe im Chat. Ein Bild groß, mehrere als wischbare Reihe mit Slideshow-Knopf. */
@Composable
fun ImageStrip(images: List<GalleryImage>, onOpen: (Int) -> Unit) {
    if (images.isEmpty()) return
    val single = images.size == 1
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            images.forEachIndexed { i, img ->
                ChatImage(
                    img,
                    thumbnail = !single,
                    modifier = Modifier
                        .size(if (single) 260.dp else 160.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onOpen(i) },
                )
            }
        }
        if (!single) {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { onOpen(0) }.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Slideshow, null, tint = Mint, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("${images.size} Bilder · Slideshow", fontSize = 12.sp, color = Mint)
            }
        }
    }
}

/** Bild mit Ladekreis und Ersatzsymbol, falls die Seite das Laden verweigert */
@Composable
private fun ChatImage(img: GalleryImage, thumbnail: Boolean, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop) {
    SubcomposeAsyncImage(
        model = img.model(thumbnail),
        contentDescription = img.title.ifBlank { null },
        contentScale = contentScale,
        modifier = modifier,
        loading = {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp), color = Mint)
            }
        },
        error = {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.BrokenImage, "Bild nicht ladbar", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

/** Vollbild-Slideshow: wischen, automatisch weiterblättern, speichern, Quelle öffnen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SlideshowDialog(
    images: List<GalleryImage>,
    startIndex: Int,
    onDismiss: () -> Unit,
    onSave: (GalleryImage) -> Unit,
    /** Rückwärtssuche in Denkwerk (null = nicht anbieten) */
    onReverse: ((GalleryImage) -> Unit)? = null,
) {
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0))) { images.size }
    var playing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current

    // Automatisch weiterblättern alle 3 Sekunden
    LaunchedEffect(playing, pager.currentPage) {
        if (playing && images.size > 1) {
            delay(3000)
            pager.animateScrollToPage((pager.currentPage + 1) % images.size)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                Box(Modifier.fillMaxSize().clickable { playing = false }, contentAlignment = Alignment.Center) {
                    ChatImage(images[page], thumbnail = false, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }

            // Oben: Schließen, Zähler, Abspielen
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Schließen", tint = Color.White) }
                Text(
                    if (images.size > 1) "${pager.currentPage + 1} / ${images.size}" else "",
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                )
                if (images.size > 1) {
                    IconButton(onClick = { playing = !playing }) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Anhalten" else "Abspielen", tint = Color.White)
                    }
                }
            }

            // Unten: Titel, Punkte, Knöpfe
            val current = images.getOrNull(pager.currentPage)
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (current != null && current.title.isNotBlank()) {
                    Text(current.title, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (images.size in 2..20) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        images.indices.forEach { i ->
                            Box(
                                Modifier
                                    .size(if (i == pager.currentPage) 9.dp else 7.dp)
                                    .clip(CircleShape)
                                    .background(if (i == pager.currentPage) Mint else Color.White.copy(alpha = 0.4f))
                                    .clickable { scope.launch { pager.animateScrollToPage(i) } },
                            )
                        }
                    }
                }
                // Bild-Rückwärtssuche: Google Lens (extern) und in Denkwerk (Yandex über die KI)
                if (current != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { openInLens(context, current) }) {
                            Icon(Icons.Default.ImageSearch, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Google Lens", color = Color.White)
                        }
                        if (onReverse != null) {
                            TextButton(onClick = { onReverse(current) }) {
                                Icon(Icons.Default.TravelExplore, null, tint = Mint, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Herkunft suchen", color = Mint)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (current != null && !current.local) {
                        TextButton(onClick = { onSave(current) }) {
                            Icon(Icons.Default.Download, null, tint = Mint, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Speichern", color = Mint)
                        }
                    }
                    val src = current?.source?.takeIf { it.startsWith("http") } ?: current?.url?.takeIf { it.startsWith("http") }
                    if (src != null) {
                        TextButton(onClick = { runCatching { uri.openUri(src) } }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Quelle öffnen", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
