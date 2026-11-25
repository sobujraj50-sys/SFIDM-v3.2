package com.sfidm.downloader.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sfidm.downloader.ui.strings.SfidmStrings

data class DetectedMedia(
    val url: String,
    val label: String,
    val isStream: Boolean
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InAppBrowserScreen(
    strings: SfidmStrings,
    onRequestDownload: (url: String, preferMediaEngine: Boolean) -> Unit
) {
    var addressInput by remember { mutableStateOf("https://m.youtube.com") }
    var currentUrl by remember { mutableStateOf("https://m.youtube.com") }
    var loadProgress by remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val detectedList = remember { mutableStateListOf<DetectedMedia>() }
    var showDetectedDialog by remember { mutableStateOf(false) }

    val quickLinks = listOf(
        "YouTube" to "https://m.youtube.com",
        "Facebook" to "https://m.facebook.com",
        "TikTok" to "https://www.tiktok.com",
        "Pinterest" to "https://www.pinterest.com",
        "Instagram" to "https://www.instagram.com"
    )

    fun navigateTo(raw: String) {
        val trimmed = raw.trim()
        val target = when {
            trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> trimmed
            trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
            else -> "https://www.google.com/search?q=${trimmed.replace(" ", "+")}"
        }
        addressInput = target
        currentUrl = target
        detectedList.clear()
        webViewRef?.loadUrl(target)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(
                            onClick = { webViewRef?.goBack() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = { webViewRef?.reload() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reload", modifier = Modifier.size(20.dp))
                        }

                        OutlinedTextField(
                            value = addressInput,
                            onValueChange = { addressInput = it },
                            placeholder = { Text(strings.browserHint, fontSize = 12.sp) },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { navigateTo(addressInput) }),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        )

                        Spacer(modifier = Modifier.width(4.dp))

                        IconButton(
                            onClick = { onRequestDownload(currentUrl, true) },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = strings.grabCurrentPage,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        items(quickLinks) { (label, link) ->
                            AssistChip(
                                onClick = { navigateTo(link) },
                                label = { Text(label, fontSize = 11.sp) },
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                            )
                        }
                    }
                }
            }

            if (loadProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { loadProgress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loadProgress = newProgress
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                if (!url.isNullOrBlank()) {
                                    currentUrl = url
                                    addressInput = url
                                }
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val reqUrl = request?.url?.toString() ?: return null
                                val media = sniffMediaUrl(reqUrl)
                                if (media != null) {
                                    view?.post {
                                        if (detectedList.none { it.url == media.url }) {
                                            detectedList.add(0, media)
                                            if (detectedList.size > 30) {
                                                detectedList.removeAt(detectedList.lastIndex)
                                            }
                                        }
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }

                        loadUrl(currentUrl)
                        webViewRef = this
                    }
                }
            )
        }

        ExtendedFloatingActionButton(
            onClick = { showDetectedDialog = true },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) },
            text = {
                val text = if (detectedList.isNotEmpty()) {
                    "${strings.mediaFoundPrefix} ${detectedList.size} ${strings.mediaFoundSuffix}"
                } else {
                    strings.downloadBtn
                }
                Text(text, fontWeight = FontWeight.Bold)
            }
        )
    }

    if (showDetectedDialog) {
        AlertDialog(
            onDismissRequest = { showDetectedDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(strings.detectedMediaTitle, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            showDetectedDialog = false
                            onRequestDownload(currentUrl, true)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.grabCurrentPage, fontWeight = FontWeight.Bold)
                    }

                    if (detectedList.isEmpty()) {
                        Text(
                            text = strings.noMediaDetected,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 260.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(detectedList) { item ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.background,
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp)
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.label,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = item.url,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        IconButton(onClick = {
                                            showDetectedDialog = false
                                            onRequestDownload(item.url, item.isStream)
                                        }) {
                                            Icon(
                                                Icons.Default.Download,
                                                contentDescription = strings.downloadBtn,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetectedDialog = false }) {
                    Text(strings.close)
                }
            }
        )
    }
}

private fun sniffMediaUrl(url: String): DetectedMedia? {
    val lower = url.lowercase().substringBefore("?").substringBefore("#")
    return when {
        lower.endsWith(".m3u8") -> DetectedMedia(url, "HLS Stream (M3U8 → MP4)", isStream = true)
        lower.endsWith(".mpd") -> DetectedMedia(url, "DASH Stream (MPD → MP4)", isStream = true)
        lower.endsWith(".mp4") -> DetectedMedia(url, url.substringBefore("?").substringAfterLast("/").ifBlank { "Video.mp4" }, isStream = false)
        lower.endsWith(".webm") -> DetectedMedia(url, url.substringBefore("?").substringAfterLast("/").ifBlank { "Video.webm" }, isStream = false)
        lower.endsWith(".mp3") || lower.endsWith(".m4a") -> DetectedMedia(url, url.substringBefore("?").substringAfterLast("/").ifBlank { "Audio.mp3" }, isStream = false)
        lower.contains("i.pinimg.com/originals/") || lower.contains("i.pinimg.com/736x/") ->
            DetectedMedia(url, "Pinterest HD Image (${url.substringAfterLast("/")})", isStream = false)
        lower.endsWith(".pdf") || lower.endsWith(".zip") || lower.endsWith(".apk") ->
            DetectedMedia(url, url.substringBefore("?").substringAfterLast("/"), isStream = false)
        else -> null
    }
}
