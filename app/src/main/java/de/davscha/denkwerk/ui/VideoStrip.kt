package de.davscha.denkwerk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import de.davscha.denkwerk.data.WebVideo

/** Wischbare Reihe von Video-Kärtchen. Antippen spielt ab (YouTube-App bzw. Browser). */
@Composable
fun VideoStrip(videos: List<WebVideo>, onSummarize: ((WebVideo) -> Unit)?) {
    if (videos.isEmpty()) return
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        videos.forEach { v -> VideoCard(v, onSummarize) }
    }
}

@Composable
private fun VideoCard(v: WebVideo, onSummarize: ((WebVideo) -> Unit)?) {
    val uri = LocalUriHandler.current
    val isYoutube = v.url.contains("youtube.com") || v.url.contains("youtu.be")
    Column(
        Modifier
            .width(260.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { runCatching { uri.openUri(v.url) } },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            SubcomposeAsyncImage(
                model = v.thumb.ifBlank { null },
                contentDescription = v.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = {
                    Box(Modifier.fillMaxSize().background(Color(0xFF26262B)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.VideocamOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
            // Abspiel-Symbol in der Mitte
            Box(
                Modifier.align(Alignment.Center).size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.PlayArrow, "Abspielen", tint = Color.White, modifier = Modifier.size(28.dp)) }
            if (v.duration.isNotBlank()) {
                Text(
                    v.duration,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(v.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(36.dp))
            Text(
                listOf(v.publisher, v.published).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isYoutube && onSummarize != null) {
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable { onSummarize(v) }.padding(vertical = 4.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Summarize, null, tint = Mint, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Zusammenfassen", fontSize = 12.sp, color = Mint)
                }
            }
        }
    }
}
