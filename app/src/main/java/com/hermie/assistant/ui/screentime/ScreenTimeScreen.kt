package com.hermie.assistant.ui.screentime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermie.assistant.data.HermieSettings
import com.hermie.assistant.ui.theme.*

data class AppUsageInfo(
    val packageName: String,
    val appName: String,
    val minutesUsed: Long,
    val limitMinutes: Int?,       // null = no limit set
    val personalReason: String?   // why user wants to limit
)

@Composable
fun ScreenTimeScreen(
    hasPermission: Boolean,
    appUsage: Map<String, Long>,
    settings: HermieSettings,
    onRequestPermission: () -> Unit,
    onSetLimit: (packageName: String, minutes: Int, reason: String) -> Unit,
    onRemoveLimit: (packageName: String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var selectedApp by remember { mutableStateOf<AppUsageInfo?>(null) }
    var isSearchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // This key increments every time the user sets or removes a limit,
    // forcing the triggers/reasons to be re-read from settings
    var refreshKey by remember { mutableIntStateOf(0) }

    // Re-read triggers and reasons whenever refreshKey changes
    val triggers = remember(refreshKey) { settings.getScreenTimeTriggers() }
    val reasons = remember(refreshKey) { settings.getScreenTimeReasons() }

    val appList = remember(appUsage, triggers, reasons, refreshKey) {
        buildAppList(context, appUsage, triggers, reasons)
    }

    // Separate apps with limits (monitored) from others
    val monitoredApps = appList.filter { it.limitMinutes != null }

    // All apps for search — sorted by usage
    val searchableApps = remember(appList, searchQuery) {
        val list = if (searchQuery.isBlank()) appList
        else appList.filter {
            it.appName.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
        // Don't show already-monitored apps in the search list
        list.filter { it.limitMinutes == null }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Main content ─────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(HermieSurface)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Top bar — Wardrobe-style (larger, serif)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = HermieForest)
                }
                Text(
                    text = "Screen Time",
                    style = TextStyle(
                        fontFamily = HermieSerif,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = HermieForest
                    ),
                    modifier = Modifier.weight(1f)
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Banner — orange when no permission or >3h, green otherwise
                item {
                    val totalMinutes = appUsage.values.sum()
                    val isOverThreshold = hasPermission && totalMinutes >= 180
                    val bannerColor = when {
                        !hasPermission -> HermieTerra
                        isOverThreshold -> HermieTerra
                        else -> HermieForest
                    }

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = bannerColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Column(modifier = Modifier.padding(26.dp)) {
                            if (hasPermission) {
                                val hours = totalMinutes / 60
                                val mins = totalMinutes % 60
                                Text(
                                    text = if (hours > 0) "${hours}h ${mins}m" else "${mins}m",
                                    style = TextStyle(
                                        fontFamily = HermieSerif,
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = HermieCream
                                    )
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "total screen time today",
                                    style = TextStyle(
                                        fontFamily = HermieSerif,
                                        fontSize = 13.sp,
                                        color = HermieCream.copy(alpha = 0.7f)
                                    )
                                )
                            } else {
                                Icon(
                                    Icons.Outlined.Timer,
                                    contentDescription = null,
                                    tint = HermieCream,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "Screen Time",
                                    style = TextStyle(
                                        fontFamily = HermieSerif,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = HermieCream
                                    )
                                )
                                Text(
                                    text = "Grant permission to track your screen time",
                                    style = TextStyle(
                                        fontFamily = HermieSerif,
                                        fontSize = 13.sp,
                                        color = HermieCream.copy(alpha = 0.7f)
                                    )
                                )
                                Spacer(Modifier.height(12.dp))
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = HermieForest,
                                    modifier = Modifier.clickable(onClick = onRequestPermission)
                                ) {
                                    Text(
                                        text = "Grant Usage Access",
                                        style = TextStyle(
                                            fontFamily = HermieSerif,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = HermieCream
                                        ),
                                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (hasPermission) {
                    item { Spacer(Modifier.height(4.dp)) }

                    // Search apps button
                    item {
                        SearchButton { isSearchOpen = true }
                    }

                    // Monitored apps section
                    if (monitoredApps.isNotEmpty()) {
                        item {
                            Text(
                                text = "MONITORED",
                                style = TextStyle(
                                    fontFamily = HermieSerif,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = HermieGrey,
                                    letterSpacing = 1.5.sp
                                ),
                                modifier = Modifier.padding(
                                    top = 16.dp, bottom = 8.dp,
                                    start = 24.dp, end = 24.dp
                                )
                            )
                        }
                        items(monitoredApps, key = { "mon_${it.packageName}" }) { app ->
                            Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                                MonitoredAppCard(
                                    app = app,
                                    onClick = { selectedApp = app }
                                )
                            }
                        }
                    } else {
                        item {
                            // Empty state hint
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 40.dp, vertical = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Outlined.Timer,
                                    contentDescription = null,
                                    tint = HermieGrey.copy(alpha = 0.4f),
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = "No apps monitored yet",
                                    style = TextStyle(
                                        fontFamily = HermieSerif,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = HermieGrey
                                    )
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Tap \"Search apps\" to add apps\nHermie will keep track for you",
                                    style = TextStyle(
                                        fontFamily = HermieSerif,
                                        fontSize = 13.sp,
                                        color = HermieGrey.copy(alpha = 0.6f),
                                        textAlign = TextAlign.Center,
                                        lineHeight = 18.sp
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Search overlay (fullscreen modal) ────────────────
        AnimatedVisibility(
            visible = isSearchOpen,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200))
        ) {
            AppSearchOverlay(
                apps = searchableApps,
                searchQuery = searchQuery,
                onQueryChange = { searchQuery = it },
                onAppSelected = { app ->
                    selectedApp = app
                },
                onDismiss = {
                    isSearchOpen = false
                    searchQuery = ""
                }
            )
        }
    }

    // Dialog for setting limit
    selectedApp?.let { app ->
        SetLimitDialog(
            app = app,
            onDismiss = { selectedApp = null },
            onSetLimit = { minutes, reason ->
                onSetLimit(app.packageName, minutes, reason)
                selectedApp = null
                refreshKey++ // Force UI to re-read from settings
            },
            onRemoveLimit = {
                onRemoveLimit(app.packageName)
                selectedApp = null
                refreshKey++ // Force UI to re-read from settings
            }
        )
    }
}

// ── Search Overlay ───────────────────────────────────────────

@Composable
private fun AppSearchOverlay(
    apps: List<AppUsageInfo>,
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onAppSelected: (AppUsageInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HermieSurface.copy(alpha = 0.97f))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Search bar at top
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", tint = HermieForest)
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = HermieOffWhite,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = null,
                            tint = HermieGrey,
                            modifier = Modifier.size(20.dp)
                        )
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = onQueryChange,
                            textStyle = TextStyle(
                                fontFamily = HermieSerif,
                                fontSize = 15.sp,
                                color = HermieForest
                            ),
                            cursorBrush = SolidColor(HermieForest),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "Search apps...",
                                            style = TextStyle(
                                                fontFamily = HermieSerif,
                                                fontSize = 15.sp,
                                                color = HermieGrey.copy(alpha = 0.5f)
                                            )
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                        if (searchQuery.isNotEmpty()) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Clear",
                                tint = HermieGrey,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable { onQueryChange("") }
                            )
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))
            }

            // App list — slides up from bottom
            AnimatedVisibility(
                visible = true,
                enter = slideInVertically(tween(300)) { it / 3 } + fadeIn(tween(300))
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)
                ) {
                    items(apps, key = { "search_${it.packageName}" }) { app ->
                        SearchAppCard(
                            app = app,
                            onClick = { onAppSelected(app) }
                        )
                    }

                    if (apps.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (searchQuery.isNotEmpty())
                                        "No apps found for \"$searchQuery\""
                                    else
                                        "All apps are already monitored!",
                                    style = TextStyle(
                                        fontFamily = HermieSerif,
                                        fontSize = 15.sp,
                                        color = HermieGrey
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchAppCard(
    app: AppUsageInfo,
    onClick: () -> Unit
) {
    val hours = app.minutesUsed / 60
    val mins = app.minutesUsed % 60
    val usageText = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = HermieOffWhite,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // App icon — first letter
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(HermieTan.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = app.appName.take(1).uppercase(),
                    style = TextStyle(
                        fontFamily = HermieSerif,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = HermieForest
                    )
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    style = TextStyle(
                        fontFamily = HermieSerif,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = HermieForest
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (app.minutesUsed > 0) {
                    Text(
                        text = "$usageText today",
                        style = TextStyle(
                            fontFamily = HermieSerif,
                            fontSize = 11.sp,
                            color = HermieGrey
                        )
                    )
                }
            }

            Icon(
                Icons.Outlined.Add,
                contentDescription = "Add limit",
                tint = HermieTerra,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ── Main Screen Components ───────────────────────────────────

@Composable
private fun SearchButton(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = HermieOffWhite,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                tint = HermieGrey,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "Search apps...",
                style = TextStyle(
                    fontFamily = HermieSerif,
                    fontSize = 15.sp,
                    color = HermieGrey.copy(alpha = 0.5f)
                ),
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Outlined.Add,
                contentDescription = null,
                tint = HermieTerra,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// PermissionRequestCard and TotalUsageCard are now inlined in the banner above

@Composable
private fun MonitoredAppCard(
    app: AppUsageInfo,
    onClick: () -> Unit
) {
    val hours = app.minutesUsed / 60
    val mins = app.minutesUsed % 60
    val usageText = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"

    val isOverLimit = app.limitMinutes != null && app.minutesUsed >= app.limitMinutes

    Column {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = HermieOffWhite,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // App icon — first letter
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isOverLimit) HermieError.copy(alpha = 0.1f)
                            else HermieTan.copy(alpha = 0.3f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = app.appName.take(1).uppercase(),
                        style = TextStyle(
                            fontFamily = HermieSerif,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isOverLimit) HermieError else HermieForest
                        )
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.appName,
                        style = TextStyle(
                            fontFamily = HermieSerif,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = HermieForest
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = usageText,
                            style = TextStyle(
                                fontFamily = HermieSerif,
                                fontSize = 12.sp,
                                color = if (isOverLimit) HermieError else HermieGrey
                            )
                        )
                        if (app.limitMinutes != null) {
                            Text(
                                text = "/ ${app.limitMinutes}m limit",
                                style = TextStyle(
                                    fontFamily = HermieSerif,
                                    fontSize = 12.sp,
                                    color = if (isOverLimit) HermieError else HermieTerra
                                )
                            )
                        }
                    }
                }

                Icon(
                    if (isOverLimit) Icons.Filled.Warning else Icons.Outlined.Timer,
                    contentDescription = null,
                    tint = if (isOverLimit) HermieError else HermieTerra,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Progress bar
        if (app.limitMinutes != null && app.limitMinutes > 0) {
            val progress = (app.minutesUsed.toFloat() / app.limitMinutes).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = if (isOverLimit) HermieError else HermieTerra,
                trackColor = HermieTan.copy(alpha = 0.2f)
            )
        }
    }
}

// ── Set Limit Dialog ─────────────────────────────────────────

@Composable
private fun SetLimitDialog(
    app: AppUsageInfo,
    onDismiss: () -> Unit,
    onSetLimit: (minutes: Int, reason: String) -> Unit,
    onRemoveLimit: () -> Unit
) {
    var minutesInput by remember { mutableStateOf(app.limitMinutes?.toString() ?: "30") }
    var reasonInput by remember { mutableStateOf(app.personalReason ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = HermieSurface,
        title = {
            Column {
                Text(
                    text = app.appName,
                    style = TextStyle(
                        fontFamily = HermieSerif,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = HermieForest
                    )
                )
                Text(
                    text = app.packageName,
                    style = TextStyle(
                        fontFamily = HermieSerif,
                        fontSize = 11.sp,
                        color = HermieGrey
                    )
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val hours = app.minutesUsed / 60
                val mins = app.minutesUsed % 60
                Text(
                    text = "Used ${if (hours > 0) "${hours}h ${mins}m" else "${mins}m"} today",
                    style = TextStyle(
                        fontFamily = HermieSerif,
                        fontSize = 14.sp,
                        color = HermieGrey
                    )
                )

                // Time limit input
                Column {
                    Text(
                        text = "Daily limit (minutes)",
                        style = TextStyle(
                            fontFamily = HermieSerif,
                            fontSize = 12.sp,
                            color = HermieGrey,
                            letterSpacing = 0.5.sp
                        ),
                        modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
                    )
                    BasicTextField(
                        value = minutesInput,
                        onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 3) minutesInput = it },
                        textStyle = TextStyle(
                            fontFamily = HermieSerif,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = HermieForest
                        ),
                        cursorBrush = SolidColor(HermieForest),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(HermieOffWhite, RoundedCornerShape(14.dp))
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        decorationBox = { innerTextField ->
                            Box {
                                if (minutesInput.isEmpty()) {
                                    Text(
                                        "e.g. 30",
                                        style = TextStyle(
                                            fontSize = 18.sp,
                                            color = HermieGrey.copy(alpha = 0.5f)
                                        )
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }

                // Quick presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(15, 30, 60, 120).forEach { preset ->
                        val label = if (preset >= 60) "${preset / 60}h" else "${preset}m"
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (minutesInput == preset.toString()) HermieForest else HermieOffWhite,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { minutesInput = preset.toString() }
                        ) {
                            Text(
                                text = label,
                                style = TextStyle(
                                    fontFamily = HermieSerif,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (minutesInput == preset.toString()) HermieCream else HermieForest,
                                    textAlign = TextAlign.Center
                                ),
                                modifier = Modifier.padding(vertical = 10.dp)
                            )
                        }
                    }
                }

                // Personal reason
                Column {
                    Text(
                        text = "Why limit this app? (personal ammo for Hermie)",
                        style = TextStyle(
                            fontFamily = HermieSerif,
                            fontSize = 12.sp,
                            color = HermieGrey,
                            letterSpacing = 0.5.sp
                        ),
                        modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
                    )
                    BasicTextField(
                        value = reasonInput,
                        onValueChange = { reasonInput = it },
                        textStyle = TextStyle(
                            fontFamily = HermieSerif,
                            fontSize = 15.sp,
                            color = HermieForest
                        ),
                        cursorBrush = SolidColor(HermieForest),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .background(HermieOffWhite, RoundedCornerShape(14.dp))
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        decorationBox = { innerTextField ->
                            Box {
                                if (reasonInput.isEmpty()) {
                                    Text(
                                        "e.g. I waste too much time scrolling instead of studying",
                                        style = TextStyle(
                                            fontFamily = HermieSerif,
                                            fontSize = 15.sp,
                                            color = HermieGrey.copy(alpha = 0.5f),
                                            lineHeight = 20.sp
                                        )
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            }
        },
        confirmButton = {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = HermieForest,
                modifier = Modifier.clickable {
                    val minutes = minutesInput.toIntOrNull()
                    if (minutes != null && minutes > 0) {
                        onSetLimit(minutes, reasonInput)
                    }
                }
            ) {
                Text(
                    text = if (app.limitMinutes != null) "Update Limit" else "Set Limit",
                    style = TextStyle(
                        fontFamily = HermieSerif,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = HermieCream
                    ),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
        },
        dismissButton = {
            if (app.limitMinutes != null) {
                Text(
                    text = "Remove Limit",
                    style = TextStyle(
                        fontFamily = HermieSerif,
                        fontSize = 14.sp,
                        color = HermieError
                    ),
                    modifier = Modifier
                        .clickable { onRemoveLimit() }
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                )
            } else {
                Text(
                    text = "Cancel",
                    style = TextStyle(
                        fontFamily = HermieSerif,
                        fontSize = 14.sp,
                        color = HermieGrey
                    ),
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                )
            }
        }
    )
}

// ── App list building ────────────────────────────────────────

/**
 * Build the app list from three sources:
 * 1. Usage stats data (primary — sees ALL apps regardless of package visibility)
 * 2. Launcher-visible apps (queryIntentActivities with MAIN/LAUNCHER)
 * 3. Apps with existing triggers/monitors (persisted, even if app is uninstalled)
 */
private fun buildAppList(
    context: Context,
    usage: Map<String, Long>,
    triggers: Map<String, Int>,
    reasons: Map<String, String>
): List<AppUsageInfo> {
    val pm = context.packageManager
    val allPackages = mutableMapOf<String, String>() // packageName → appName

    // Source 1: Usage stats
    for (pkg in usage.keys) {
        if (pkg != context.packageName) {
            allPackages[pkg] = getAppNameSafe(pm, pkg)
        }
    }

    // Source 2: Launcher-visible apps
    try {
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val launchableApps: List<ResolveInfo> = pm.queryIntentActivities(launcherIntent, 0)
        for (ri in launchableApps) {
            val pkg = ri.activityInfo.packageName
            if (pkg != context.packageName && pkg !in allPackages) {
                allPackages[pkg] = ri.loadLabel(pm).toString()
            }
        }
    } catch (_: Exception) { }

    // Source 3: Persisted monitors
    for (pkg in triggers.keys) {
        if (pkg !in allPackages && pkg != context.packageName) {
            allPackages[pkg] = getAppNameSafe(pm, pkg)
        }
    }

    return allPackages.map { (pkg, name) ->
        AppUsageInfo(
            packageName = pkg,
            appName = name,
            minutesUsed = usage[pkg] ?: 0,
            limitMinutes = triggers[pkg],
            personalReason = reasons[pkg]
        )
    }.sortedWith(
        // Monitored apps first, then by usage descending
        compareByDescending<AppUsageInfo> { it.limitMinutes != null }
            .thenByDescending { it.minutesUsed }
    )
}

private fun getAppNameSafe(pm: PackageManager, packageName: String): String {
    return try {
        val info = pm.getApplicationInfo(packageName, 0)
        pm.getApplicationLabel(info).toString()
    } catch (_: Exception) {
        packageName.substringAfterLast('.')
            .replaceFirstChar { it.uppercase() }
    }
}
