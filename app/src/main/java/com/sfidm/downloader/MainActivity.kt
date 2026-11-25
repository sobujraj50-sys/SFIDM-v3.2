package com.sfidm.downloader

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sfidm.downloader.data.PrefsManager
import com.sfidm.downloader.engine.DownloadManager
import com.sfidm.downloader.engine.MediaVideoDownloader
import com.sfidm.downloader.model.DownloadItem
import com.sfidm.downloader.model.DownloadMode
import com.sfidm.downloader.model.DownloadStatus
import com.sfidm.downloader.model.FileCategory
import com.sfidm.downloader.model.MediaQuality
import com.sfidm.downloader.model.SpeedLimitOption
import com.sfidm.downloader.ui.browser.InAppBrowserScreen
import com.sfidm.downloader.ui.strings.AppLanguage
import com.sfidm.downloader.ui.strings.SfidmStrings
import com.sfidm.downloader.ui.strings.stringsFor
import com.sfidm.downloader.ui.theme.AppTheme
import com.sfidm.downloader.ui.theme.SfidmTheme
import com.sfidm.downloader.ui.vault.PrivateVaultScreen
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var sharedUrlState = mutableStateOf<String?>(null)

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission handled gracefully */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DownloadManager.init(applicationContext)
        val prefs = PrefsManager(applicationContext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        handleShareIntent(intent)

        setContent {
            var currentTheme by remember { mutableStateOf(prefs.getTheme()) }
            var currentLang by remember { mutableStateOf(prefs.getLanguage()) }

            SfidmTheme(appTheme = currentTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SfidmAppShell(
                        prefs = prefs,
                        currentTheme = currentTheme,
                        currentLang = currentLang,
                        incomingSharedUrl = sharedUrlState.value,
                        onConsumeSharedUrl = { sharedUrlState.value = null },
                        onThemeChange = {
                            currentTheme = it
                            prefs.saveTheme(it)
                        },
                        onToggleLanguage = {
                            val next = if (currentLang == AppLanguage.BN) AppLanguage.EN else AppLanguage.BN
                            currentLang = next
                            prefs.saveLanguage(next)
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                val url = Regex("https?://\\S+").find(sharedText)?.value ?: sharedText
                sharedUrlState.value = url
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SfidmAppShell(
    prefs: PrefsManager,
    currentTheme: AppTheme,
    currentLang: AppLanguage,
    incomingSharedUrl: String?,
    onConsumeSharedUrl: () -> Unit,
    onThemeChange: (AppTheme) -> Unit,
    onToggleLanguage: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val strings = remember(currentLang) { stringsFor(currentLang) }
    val downloads by DownloadManager.downloads.collectAsState()
    val speedLimitBytes by DownloadManager.speedLimitBytes.collectAsState()
    val wifiOnly by DownloadManager.wifiOnly.collectAsState()

    var bottomNavIndex by remember { mutableIntStateOf(0) }
    var selectedStatusTab by remember { mutableIntStateOf(0) }
    var selectedCategory by remember { mutableStateOf<FileCategory?>(null) }

    var showAddDialog by remember { mutableStateOf(false) }
    var prefillUrl by remember { mutableStateOf("") }
    var prefillMode by remember { mutableStateOf<DownloadMode?>(null) }
    var defaultToVault by remember { mutableStateOf(false) }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var scheduleTargetId by remember { mutableStateOf<String?>(null) }
    var errorDetailItem by remember { mutableStateOf<DownloadItem?>(null) }
    var isUpdatingEngine by remember { mutableStateOf(false) }
    var isVaultUnlocked by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                isVaultUnlocked = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(incomingSharedUrl) {
        if (!incomingSharedUrl.isNullOrBlank()) {
            prefillUrl = incomingSharedUrl
            prefillMode = null
            defaultToVault = false
            bottomNavIndex = 0
            showAddDialog = true
            onConsumeSharedUrl()
        }
    }

    val publicDownloads = downloads.filterNot { it.isVault }
    val vaultDownloads = downloads.filter { it.isVault }

    val activeDownloads = downloads.filter { it.status == DownloadStatus.DOWNLOADING }
    val totalSpeed = activeDownloads.sumOf { it.speedBytesPerSec }

    val statusTabs = listOf(strings.tabAll, strings.tabActive, strings.tabScheduled, strings.tabDone)
    val filteredList = publicDownloads
        .filter { item ->
            when (selectedStatusTab) {
                1 -> item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.QUEUED || item.status == DownloadStatus.PAUSED
                2 -> item.status == DownloadStatus.SCHEDULED
                3 -> item.status == DownloadStatus.COMPLETED
                else -> true
            }
        }
        .filter { item ->
            selectedCategory == null || item.category == selectedCategory
        }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "SFIDM",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 18.sp,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                if (speedLimitBytes > 0L) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        color = Color(0xFFF59E0B).copy(alpha = 0.18f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = SpeedLimitOption.fromBytes(speedLimitBytes).labelEn,
                                            color = Color(0xFFF59E0B),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "${activeDownloads.size} ${strings.activeCountSuffix}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    if (totalSpeed > 0L) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.padding(end = 2.dp)
                        ) {
                            Text(
                                text = "⚡ ${DownloadManager.formatSpeed(totalSpeed)}",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    IconButton(
                        enabled = !isUpdatingEngine,
                        onClick = {
                            isUpdatingEngine = true
                            Toast.makeText(context, strings.updatingEngine, Toast.LENGTH_SHORT).show()
                            scope.launch {
                                val res = DownloadManager.updateVideoEngine()
                                isUpdatingEngine = false
                                val verTag = if (res.versionInfo.isNotBlank()) " (${res.versionInfo})" else ""
                                val msg = when {
                                    res.success && res.alreadyUpToDate -> "${strings.engineUpToDate}$verTag"
                                    res.success -> "${strings.engineUpdated}$verTag"
                                    else -> "${strings.engineUpdateFailed}: ${res.errorMessage ?: ""}"
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    ) {
                        if (isUpdatingEngine) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Default.SystemUpdateAlt,
                                contentDescription = strings.updateEngineTitle,
                                tint = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    TextButton(
                        onClick = onToggleLanguage,
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text(
                            text = strings.langToggleLabel,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }

                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = strings.settingsTitle,
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp
            ) {
                NavigationBarItem(
                    selected = bottomNavIndex == 0,
                    onClick = { bottomNavIndex = 0 },
                    icon = { Icon(Icons.Default.Download, contentDescription = strings.navDownloads) },
                    label = { Text(strings.navDownloads) }
                )
                NavigationBarItem(
                    selected = bottomNavIndex == 1,
                    onClick = { bottomNavIndex = 1 },
                    icon = { Icon(Icons.Default.Language, contentDescription = strings.navBrowser) },
                    label = { Text(strings.navBrowser) }
                )
                NavigationBarItem(
                    selected = bottomNavIndex == 2,
                    onClick = { bottomNavIndex = 2 },
                    icon = {
                        Icon(
                            imageVector = if (isVaultUnlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                            contentDescription = strings.navVault
                        )
                    },
                    label = { Text(strings.navVault) }
                )
            }
        },
        floatingActionButton = {
            if (bottomNavIndex == 0 || (bottomNavIndex == 2 && isVaultUnlocked)) {
                FloatingActionButton(
                    onClick = {
                        prefillUrl = readClipboardUrl(context)
                        prefillMode = null
                        defaultToVault = (bottomNavIndex == 2)
                        showAddDialog = true
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = strings.addDownloadTitle)
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (bottomNavIndex) {
                0 -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(statusTabs.indices.toList()) { idx ->
                                val selected = selectedStatusTab == idx
                                FilterChip(
                                    selected = selected,
                                    onClick = { selectedStatusTab = idx },
                                    label = {
                                        Text(
                                            text = statusTabs[idx],
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            fontSize = 12.sp
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f)
                                    )
                                )
                            }
                        }

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            item {
                                AssistChip(
                                    onClick = { selectedCategory = null },
                                    label = {
                                        Text(
                                            text = strings.catAll,
                                            fontSize = 11.sp,
                                            fontWeight = if (selectedCategory == null) FontWeight.Bold else FontWeight.Normal,
                                            color = if (selectedCategory == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Folder,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = if (selectedCategory == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                )
                            }
                            items(FileCategory.entries) { cat ->
                                val isSelected = selectedCategory == cat
                                AssistChip(
                                    onClick = { selectedCategory = if (isSelected) null else cat },
                                    label = {
                                        Text(
                                            text = if (currentLang == AppLanguage.BN) cat.labelBn else cat.labelEn,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                )
                            }
                        }

                        if (filteredList.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Bolt,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                        modifier = Modifier.size(56.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = strings.emptyListTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = strings.emptyListSub,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 88.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(filteredList, key = { it.id }) { item ->
                                    DownloadCard(
                                        item = item,
                                        currentLang = currentLang,
                                        strings = strings,
                                        onPause = { DownloadManager.pauseDownload(item.id) },
                                        onResume = { DownloadManager.startDownload(item.id) },
                                        onSchedule = { scheduleTargetId = item.id },
                                        onToggleVault = {
                                            if (DownloadManager.moveItemToVault(item.id)) {
                                                Toast.makeText(context, strings.movedToVault, Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onDelete = { DownloadManager.deleteDownload(item.id) },
                                        onOpen = { openDownloadedFile(context, item, strings.openFileError) },
                                        onShowError = { errorDetailItem = item }
                                    )
                                }
                            }
                        }
                    }
                }

                1 -> {
                    InAppBrowserScreen(
                        strings = strings,
                        onRequestDownload = { url, preferMedia ->
                            prefillUrl = url
                            prefillMode = if (preferMedia) DownloadMode.MEDIA_VIDEO else DownloadMode.DIRECT_FILE
                            defaultToVault = false
                            showAddDialog = true
                        }
                    )
                }

                2 -> {
                    PrivateVaultScreen(
                        prefs = prefs,
                        isUnlocked = isVaultUnlocked,
                        onUnlockSuccess = { isVaultUnlocked = true },
                        onLockNow = { isVaultUnlocked = false },
                        vaultDownloads = vaultDownloads,
                        currentLang = currentLang,
                        strings = strings
                    ) { item ->
                        DownloadCard(
                            item = item,
                            currentLang = currentLang,
                            strings = strings,
                            onPause = { DownloadManager.pauseDownload(item.id) },
                            onResume = { DownloadManager.startDownload(item.id) },
                            onSchedule = { scheduleTargetId = item.id },
                            onToggleVault = {
                                if (DownloadManager.exportItemFromVault(item.id)) {
                                    Toast.makeText(context, strings.exportedToDownloads, Toast.LENGTH_SHORT).show()
                                }
                            },
                            onDelete = { DownloadManager.deleteDownload(item.id) },
                            onOpen = { openDownloadedFile(context, item, strings.openFileError) },
                            onShowError = { errorDetailItem = item }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddDownloadDialog(
            initialUrl = prefillUrl,
            initialMode = prefillMode,
            initialVault = defaultToVault,
            currentLang = currentLang,
            strings = strings,
            onDismiss = { showAddDialog = false },
            onStart = { url, name, mode, quality, threads, isVault, scheduledMillis ->
                if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                    Toast.makeText(context, strings.invalidUrl, Toast.LENGTH_SHORT).show()
                } else {
                    DownloadManager.addDownload(url, name, mode, quality, threads, isVault, scheduledMillis)
                    showAddDialog = false
                    bottomNavIndex = if (isVault) 2 else 0
                }
            }
        )
    }

    if (scheduleTargetId != null) {
        QuickScheduleDialog(
            strings = strings,
            onDismiss = { scheduleTargetId = null },
            onConfirm = { triggerMillis ->
                scheduleTargetId?.let { DownloadManager.scheduleExistingDownload(it, triggerMillis) }
                scheduleTargetId = null
            }
        )
    }

    if (errorDetailItem != null) {
        val errItem = errorDetailItem!!
        AlertDialog(
            onDismissRequest = { errorDetailItem = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(strings.errorDetailsTitle, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = errItem.url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = errItem.errorMessage.ifBlank { "Unknown error" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cb?.setPrimaryClip(ClipData.newPlainText("SFIDM Error", errItem.errorMessage))
                    Toast.makeText(context, strings.copiedMsg, Toast.LENGTH_SHORT).show()
                }) {
                    Text(strings.copyErrorBtn)
                }
            },
            dismissButton = {
                TextButton(onClick = { errorDetailItem = null }) {
                    Text(strings.close)
                }
            }
        )
    }

    if (showSettingsDialog) {
        ControlSettingsDialog(
            currentTheme = currentTheme,
            currentLang = currentLang,
            speedLimitBytes = speedLimitBytes,
            wifiOnly = wifiOnly,
            strings = strings,
            onSpeedLimitChange = { DownloadManager.setSpeedLimit(it) },
            onWifiOnlyChange = { DownloadManager.setWifiOnly(it) },
            onThemeSelect = { onThemeChange(it) },
            onDismiss = { showSettingsDialog = false }
        )
    }
}

@Composable
fun DownloadCard(
    item: DownloadItem,
    currentLang: AppLanguage,
    strings: SfidmStrings,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSchedule: () -> Unit,
    onToggleVault: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onShowError: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    val statusColor = when (item.status) {
        DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> accent
        DownloadStatus.SCHEDULED -> Color(0xFFA855F7)
        DownloadStatus.PAUSED -> Color(0xFFF59E0B)
        DownloadStatus.COMPLETED -> Color(0xFF10B981)
        DownloadStatus.FAILED -> Color(0xFFEF4444)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.status == DownloadStatus.COMPLETED || item.status == DownloadStatus.FAILED) {
                if (item.status == DownloadStatus.COMPLETED) onOpen() else onShowError()
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(statusColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            item.status == DownloadStatus.SCHEDULED -> Icons.Default.Schedule
                            item.category == FileCategory.IMAGES -> Icons.Default.Image
                            item.mode == DownloadMode.MEDIA_VIDEO && item.quality == MediaQuality.AUDIO_MP3 -> Icons.Default.AudioFile
                            item.mode == DownloadMode.MEDIA_VIDEO -> Icons.Default.Movie
                            else -> fileIconFor(item.fileName)
                        },
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val statusLabel = when (item.status) {
                        DownloadStatus.QUEUED -> strings.statusQueued
                        DownloadStatus.SCHEDULED -> "${strings.statusScheduledPrefix} ${formatClockTime(item.scheduledTimeMillis)}"
                        DownloadStatus.DOWNLOADING -> {
                            if (item.speedBytesPerSec > 0L) {
                                "${DownloadManager.formatSpeed(item.speedBytesPerSec)} • ${strings.statusDownloading}"
                            } else if (item.statusDetail.isNotBlank()) {
                                item.statusDetail
                            } else {
                                strings.preparingVideo
                            }
                        }
                        DownloadStatus.PAUSED -> {
                            if (item.statusDetail == "WIFI_WAIT") strings.waitingForWifi else strings.statusPaused
                        }
                        DownloadStatus.COMPLETED -> {
                            val folderTag = if (item.isVault) "Vault" else "SFIDM/${item.category.folderName}"
                            "${strings.statusCompleted} • $folderTag"
                        }
                        DownloadStatus.FAILED -> "${strings.statusFailed}: ${item.errorMessage.substringBefore("\n")}"
                    }
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                when (item.status) {
                    DownloadStatus.DOWNLOADING -> {
                        IconButton(onClick = onPause, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Pause, contentDescription = "Pause", tint = accent)
                        }
                    }
                    DownloadStatus.PAUSED, DownloadStatus.QUEUED, DownloadStatus.SCHEDULED -> {
                        IconButton(onClick = onResume, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Start Now", tint = accent)
                        }
                        IconButton(onClick = onSchedule, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Schedule, contentDescription = "Schedule", tint = Color(0xFFA855F7), modifier = Modifier.size(19.dp))
                        }
                    }
                    DownloadStatus.FAILED -> {
                        IconButton(onClick = onResume, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = "Retry", tint = statusColor)
                        }
                    }
                    DownloadStatus.COMPLETED -> {
                        IconButton(onClick = onToggleVault, modifier = Modifier.size(36.dp)) {
                            Icon(
                                imageVector = if (item.isVault) Icons.Default.LockOpen else Icons.Default.Lock,
                                contentDescription = if (item.isVault) strings.exportFromVaultDesc else strings.moveToVaultDesc,
                                tint = Color(0xFFF5B041),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        IconButton(onClick = onOpen, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.OpenInNew, contentDescription = "Open", tint = statusColor, modifier = Modifier.size(19.dp))
                        }
                    }
                }

                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete",
                        tint = Color(0xFFEF4444).copy(alpha = 0.85f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (item.status == DownloadStatus.DOWNLOADING && item.progress <= 0.01f && item.mode == DownloadMode.MEDIA_VIDEO) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = statusColor,
                    trackColor = Color.White.copy(alpha = 0.08f)
                )
            } else {
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = statusColor,
                    trackColor = Color.White.copy(alpha = 0.08f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val sizeText = when {
                    item.totalBytes > 0L -> "${DownloadManager.formatBytes(item.downloadedBytes)} / ${DownloadManager.formatBytes(item.totalBytes)}"
                    item.downloadedBytes > 0L -> DownloadManager.formatBytes(item.downloadedBytes)
                    item.mode == DownloadMode.MEDIA_VIDEO -> item.quality.labelEn
                    else -> "0 B"
                }
                Text(
                    text = sizeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (item.status == DownloadStatus.DOWNLOADING && item.etaSeconds > 0L) {
                        Text(
                            text = "${formatEta(item.etaSeconds)} ${strings.leftSuffix}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        color = Color.White.copy(alpha = 0.06f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        val catLabel = if (currentLang == AppLanguage.BN) item.category.labelBn else item.category.labelEn
                        Text(
                            text = catLabel,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Surface(
                        color = accent.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        val badge = if (item.mode == DownloadMode.MEDIA_VIDEO) item.quality.id.uppercase() else "${item.threads}x"
                        Text(
                            text = badge,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = "${(item.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
fun AddDownloadDialog(
    initialUrl: String,
    initialMode: DownloadMode?,
    initialVault: Boolean,
    currentLang: AppLanguage,
    strings: SfidmStrings,
    onDismiss: () -> Unit,
    onStart: (
        url: String,
        fileName: String,
        mode: DownloadMode,
        quality: MediaQuality,
        threads: Int,
        isVault: Boolean,
        scheduledTimeMillis: Long
    ) -> Unit
) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(initialUrl) }
    var fileName by remember { mutableStateOf("") }
    var mode by remember {
        mutableStateOf(
            initialMode ?: if (initialUrl.isNotBlank() && MediaVideoDownloader.isLikelyMediaPage(initialUrl)) {
                DownloadMode.MEDIA_VIDEO
            } else if (initialUrl.isNotBlank()) {
                DownloadMode.DIRECT_FILE
            } else {
                DownloadMode.MEDIA_VIDEO
            }
        )
    }
    var selectedQuality by remember { mutableStateOf(MediaQuality.BEST) }
    var selectedThreads by remember { mutableIntStateOf(8) }
    var saveToVault by remember { mutableStateOf(initialVault) }
    var enableSchedule by remember { mutableStateOf(false) }
    var scheduledMillis by remember { mutableLongStateOf(System.currentTimeMillis() + 15 * 60 * 1000L) }
    val threadOptions = listOf(4, 8, 16)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(strings.addDownloadTitle, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        if (it.startsWith("http", true)) {
                            mode = if (MediaVideoDownloader.isLikelyMediaPage(it)) {
                                DownloadMode.MEDIA_VIDEO
                            } else {
                                DownloadMode.DIRECT_FILE
                            }
                        }
                    },
                    label = { Text(strings.urlLabel) },
                    placeholder = { Text(strings.urlPlaceholder) },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = {
                            val pasted = readClipboardUrl(context)
                            if (pasted.isNotBlank()) {
                                url = pasted
                                mode = if (MediaVideoDownloader.isLikelyMediaPage(pasted)) {
                                    DownloadMode.MEDIA_VIDEO
                                } else {
                                    DownloadMode.DIRECT_FILE
                                }
                            }
                        }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = strings.pasteClipboard)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == DownloadMode.MEDIA_VIDEO,
                        onClick = { mode = DownloadMode.MEDIA_VIDEO },
                        label = { Text(strings.modeMediaLabel, fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = mode == DownloadMode.DIRECT_FILE,
                        onClick = { mode = DownloadMode.DIRECT_FILE },
                        label = { Text(strings.modeDirectLabel, fontSize = 11.sp) }
                    )
                }

                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text(strings.fileNameLabel) },
                    placeholder = { Text(strings.fileNamePlaceholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (mode == DownloadMode.MEDIA_VIDEO) {
                    Text(
                        text = strings.qualityLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(MediaQuality.entries) { q ->
                            FilterChip(
                                selected = selectedQuality == q,
                                onClick = { selectedQuality = q },
                                label = {
                                    Text(
                                        text = if (currentLang == AppLanguage.BN) q.labelBn else q.labelEn,
                                        fontSize = 11.sp
                                    )
                                }
                            )
                        }
                    }
                } else {
                    Text(
                        text = strings.threadsLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        threadOptions.forEach { count ->
                            FilterChip(
                                selected = selectedThreads == count,
                                onClick = { selectedThreads = count },
                                label = { Text("${count}x", fontWeight = FontWeight.Bold) }
                            )
                        }
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = Color(0xFFF5B041),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = strings.saveToVaultLabel,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = saveToVault, onCheckedChange = { saveToVault = it })
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = Color(0xFFA855F7),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = strings.scheduleDownloadLabel,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = enableSchedule, onCheckedChange = { enableSchedule = it })
                }

                if (enableSchedule) {
                    SchedulePresetRow(
                        scheduledMillis = scheduledMillis,
                        strings = strings,
                        onUpdateMillis = { scheduledMillis = it }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trigger = if (enableSchedule) scheduledMillis else 0L
                    onStart(url.trim(), fileName.trim(), mode, selectedQuality, selectedThreads, saveToVault, trigger)
                },
                enabled = url.isNotBlank()
            ) {
                Text(
                    text = if (enableSchedule) strings.scheduleBtn else strings.startDownload,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancel)
            }
        }
    )
}

@Composable
fun SchedulePresetRow(
    scheduledMillis: Long,
    strings: SfidmStrings,
    onUpdateMillis: (Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "${strings.scheduleTimeLabel}: ${formatClockTime(scheduledMillis)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                AssistChip(
                    onClick = { onUpdateMillis(System.currentTimeMillis() + 15 * 60 * 1000L) },
                    label = { Text(strings.preset15Min, fontSize = 11.sp) }
                )
            }
            item {
                AssistChip(
                    onClick = { onUpdateMillis(System.currentTimeMillis() + 60 * 60 * 1000L) },
                    label = { Text(strings.preset1Hour, fontSize = 11.sp) }
                )
            }
            item {
                AssistChip(
                    onClick = {
                        val cal = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, 2)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            if (timeInMillis <= System.currentTimeMillis()) {
                                add(Calendar.DAY_OF_YEAR, 1)
                            }
                        }
                        onUpdateMillis(cal.timeInMillis)
                    },
                    label = { Text(strings.preset2AM, fontSize = 11.sp) }
                )
            }
            item {
                AssistChip(
                    onClick = { onUpdateMillis(scheduledMillis + 30 * 60 * 1000L) },
                    label = { Text("+30m", fontSize = 11.sp) }
                )
            }
        }
    }
}

@Composable
fun QuickScheduleDialog(
    strings: SfidmStrings,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    var scheduledMillis by remember { mutableLongStateOf(System.currentTimeMillis() + 15 * 60 * 1000L) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(strings.scheduleDownloadLabel, fontWeight = FontWeight.Bold) },
        text = {
            SchedulePresetRow(
                scheduledMillis = scheduledMillis,
                strings = strings,
                onUpdateMillis = { scheduledMillis = it }
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(scheduledMillis) }) {
                Text(strings.scheduleBtn, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancel)
            }
        }
    )
}

@Composable
fun ControlSettingsDialog(
    currentTheme: AppTheme,
    currentLang: AppLanguage,
    speedLimitBytes: Long,
    wifiOnly: Boolean,
    strings: SfidmStrings,
    onSpeedLimitChange: (Long) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onThemeSelect: (AppTheme) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(strings.settingsTitle, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = strings.speedLimitLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(SpeedLimitOption.entries) { opt ->
                        FilterChip(
                            selected = speedLimitBytes == opt.bytesPerSec,
                            onClick = { onSpeedLimitChange(opt.bytesPerSec) },
                            label = {
                                Text(
                                    text = if (currentLang == AppLanguage.BN) opt.labelBn else opt.labelEn,
                                    fontSize = 11.sp
                                )
                            }
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.wifiOnlyLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = strings.wifiOnlySub,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = wifiOnly, onCheckedChange = onWifiOnlyChange)
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Text(
                        text = strings.autoCategoryInfo,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                Text(
                    text = strings.chooseTheme,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                AppTheme.entries.forEach { theme ->
                    val selected = theme == currentTheme
                    Surface(
                        onClick = { onThemeSelect(theme) },
                        shape = RoundedCornerShape(10.dp),
                        color = theme.bg,
                        border = BorderStroke(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) theme.accent else Color.White.copy(alpha = 0.1f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(theme.accent)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (currentLang == AppLanguage.BN) theme.labelBn else theme.labelEn,
                                color = Color(0xFFF1F5F9),
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = theme.accent,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.close, fontWeight = FontWeight.Bold)
            }
        }
    )
}

private fun fileIconFor(fileName: String) = when {
    fileName.endsWith(".mp4", true) || fileName.endsWith(".mkv", true) || fileName.endsWith(".webm", true) -> Icons.Default.Movie
    fileName.endsWith(".mp3", true) || fileName.endsWith(".m4a", true) || fileName.endsWith(".wav", true) -> Icons.Default.AudioFile
    fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) || fileName.endsWith(".png", true) || fileName.endsWith(".webp", true) -> Icons.Default.Image
    fileName.endsWith(".zip", true) || fileName.endsWith(".rar", true) || fileName.endsWith(".7z", true) -> Icons.Default.FolderZip
    fileName.endsWith(".apk", true) -> Icons.Default.Android
    fileName.endsWith(".pdf", true) || fileName.endsWith(".docx", true) -> Icons.Default.Description
    else -> Icons.Default.InsertDriveFile
}

private fun readClipboardUrl(context: Context): String {
    return try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
        if (text.startsWith("http://", true) || text.startsWith("https://", true)) text else ""
    } catch (_: Exception) {
        ""
    }
}

private fun formatEta(seconds: Long): String {
    return when {
        seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

private fun formatClockTime(millis: Long): String {
    if (millis <= 0L) return ""
    val fmt = SimpleDateFormat("hh:mm a, dd MMM", Locale.getDefault())
    return fmt.format(Date(millis))
}

private fun openDownloadedFile(context: Context, item: DownloadItem, errorMsg: String) {
    try {
        val file = File(item.filePath)
        if (!file.exists()) {
            Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, item.fileName))
    } catch (_: Exception) {
        Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
    }
}
