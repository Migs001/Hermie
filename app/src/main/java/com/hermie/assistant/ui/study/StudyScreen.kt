package com.hermie.assistant.ui.study

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermie.assistant.modules.study.QueuedStudyItem
import com.hermie.assistant.ui.theme.*

/**
 * Study module screen — two tabs: Wikipedia search and PDF upload.
 * Each source can be studied immediately or queued for sleep-mode processing.
 * A green banner shows queue status when items are queued.
 */
@Composable
fun StudyScreen(
    isStudying: Boolean,
    searchResults: List<Pair<String, String>>,
    isSearching: Boolean,
    factsExtracted: Int,
    queuedItems: List<QueuedStudyItem>,
    onSearchWikipedia: (String) -> Unit,
    onStudyArticle: (String) -> Unit,
    onQueueArticle: (String) -> Unit,
    onStudyPdf: (Uri, String) -> Unit,
    onQueuePdf: (Uri, String) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
    onBack: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Wikipedia", "PDF", "Queue")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HermieSurface)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = HermieForest
                )
            }
            Text(
                text = "Study",
                style = TextStyle(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = HermieForest
                )
            )
            Spacer(Modifier.weight(1f))
            if (factsExtracted > 0) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = HermieForest.copy(alpha = 0.1f)
                ) {
                    Text(
                        text = "$factsExtracted facts",
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = HermieForest
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Queue banner — shows when items are queued
        AnimatedVisibility(
            visible = queuedItems.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Surface(
                color = HermieForest,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .clickable { selectedTab = 2 }  // Tap to go to Queue tab
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Outlined.Queue,
                        contentDescription = null,
                        tint = HermieCream,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${queuedItems.size} queued for sleep",
                            style = TextStyle(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = HermieCream
                            )
                        )
                        val wikiCount = queuedItems.count { it is QueuedStudyItem.Wikipedia }
                        val pdfCount = queuedItems.count { it is QueuedStudyItem.Pdf }
                        val parts = buildList {
                            if (wikiCount > 0) add("$wikiCount article${if (wikiCount > 1) "s" else ""}")
                            if (pdfCount > 0) add("$pdfCount PDF${if (pdfCount > 1) "s" else ""}")
                        }
                        Text(
                            text = parts.joinToString(" + ") + " — will study at bedtime",
                            style = TextStyle(
                                fontSize = 12.sp,
                                color = HermieCream.copy(alpha = 0.7f)
                            )
                        )
                    }
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = "View queue",
                        tint = HermieCream.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Tab row
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = HermieSurface,
            contentColor = HermieForest,
            indicator = { tabPositions ->
                if (selectedTab < tabPositions.size) {
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = HermieForest
                    )
                }
            }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                title,
                                style = TextStyle(
                                    fontWeight = if (selectedTab == index) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selectedTab == index) HermieForest else HermieGrey
                                )
                            )
                            // Badge on Queue tab
                            if (index == 2 && queuedItems.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(HermieTerra),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "${queuedItems.size}",
                                        style = TextStyle(
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = HermieCream
                                        )
                                    )
                                }
                            }
                        }
                    },
                    icon = {
                        Icon(
                            when (index) {
                                0 -> Icons.Outlined.Language
                                1 -> Icons.Outlined.PictureAsPdf
                                else -> Icons.Outlined.Queue
                            },
                            contentDescription = null,
                            tint = if (selectedTab == index) HermieForest else HermieGrey,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )
            }
        }

        // Content
        when (selectedTab) {
            0 -> WikipediaTab(
                searchResults = searchResults,
                isSearching = isSearching,
                isStudying = isStudying,
                onSearch = onSearchWikipedia,
                onStudyArticle = onStudyArticle,
                onQueueArticle = onQueueArticle
            )
            1 -> PdfTab(
                isStudying = isStudying,
                onStudyPdf = onStudyPdf,
                onQueuePdf = onQueuePdf
            )
            2 -> QueueTab(
                queuedItems = queuedItems,
                onRemove = onRemoveFromQueue
            )
        }
    }
}

@Composable
private fun WikipediaTab(
    searchResults: List<Pair<String, String>>,
    isSearching: Boolean,
    isStudying: Boolean,
    onSearch: (String) -> Unit,
    onStudyArticle: (String) -> Unit,
    onQueueArticle: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(16.dp))

        // Search bar
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = {
                Text("Search Wikipedia...", color = HermieGrey)
            },
            leadingIcon = {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = HermieGrey)
            },
            trailingIcon = {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = HermieForest,
                        strokeWidth = 2.dp
                    )
                } else if (query.isNotBlank()) {
                    IconButton(onClick = {
                        focusManager.clearFocus()
                        onSearch(query)
                    }) {
                        Icon(Icons.Filled.Send, contentDescription = "Search", tint = HermieForest)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                focusManager.clearFocus()
                if (query.isNotBlank()) onSearch(query)
            }),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = HermieForest,
                unfocusedBorderColor = HermieGrey.copy(alpha = 0.3f),
                cursorColor = HermieForest
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        // Results
        if (searchResults.isEmpty() && !isSearching) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.AutoStories,
                        contentDescription = null,
                        tint = HermieGrey.copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Search for a topic to study",
                        style = TextStyle(fontSize = 15.sp, color = HermieGrey)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Hermie will read the article and extract key facts",
                        style = TextStyle(fontSize = 13.sp, color = HermieGrey.copy(alpha = 0.7f))
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(searchResults) { (title, snippet) ->
                    WikipediaResultCard(
                        title = title,
                        snippet = snippet,
                        isStudying = isStudying,
                        onStudyNow = { onStudyArticle(title) },
                        onQueue = { onQueueArticle(title) }
                    )
                }
            }
        }
    }
}

@Composable
private fun WikipediaResultCard(
    title: String,
    snippet: String,
    isStudying: Boolean,
    onStudyNow: () -> Unit,
    onQueue: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = HermieOffWhite
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Outlined.Article,
                    contentDescription = null,
                    tint = HermieForest,
                    modifier = Modifier.size(24.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = TextStyle(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = HermieForest
                        )
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = snippet,
                        style = TextStyle(fontSize = 13.sp, color = HermieGrey),
                        maxLines = 3
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            // Two-button row: Study Now + Queue for Sleep
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Queue button (outlined, secondary)
                OutlinedButton(
                    onClick = onQueue,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = HermieForest
                    ),
                    border = BorderStroke(1.dp, HermieForest.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.Bedtime, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Queue", fontSize = 13.sp)
                }
                // Study Now button (filled, primary)
                Button(
                    onClick = onStudyNow,
                    enabled = !isStudying,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = HermieForest,
                        contentColor = HermieCream
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.AutoStories, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Study Now", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun PdfTab(
    isStudying: Boolean,
    onStudyPdf: (Uri, String) -> Unit,
    onQueuePdf: (Uri, String) -> Unit
) {
    var selectedPdf by remember { mutableStateOf<Pair<Uri, String>?>(null) }

    val pdfPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
            selectedPdf = uri to name
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))

        // PDF picker card
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = HermieOffWhite,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !isStudying) {
                    pdfPicker.launch(arrayOf("application/pdf"))
                }
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Outlined.UploadFile,
                    contentDescription = null,
                    tint = HermieForest,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (selectedPdf != null) selectedPdf!!.second else "Select a PDF",
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = HermieForest
                    )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (selectedPdf != null) "Tap to change" else "Tap to browse files",
                    style = TextStyle(fontSize = 13.sp, color = HermieGrey)
                )
            }
        }

        if (selectedPdf != null) {
            Spacer(Modifier.height(16.dp))
            // Two-button row for PDF
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Queue button
                OutlinedButton(
                    onClick = {
                        selectedPdf?.let { (uri, name) -> onQueuePdf(uri, name) }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = HermieForest
                    ),
                    border = BorderStroke(1.dp, HermieForest.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Queue", fontSize = 14.sp)
                }
                // Study Now button
                Button(
                    onClick = {
                        selectedPdf?.let { (uri, name) -> onStudyPdf(uri, name) }
                    },
                    enabled = !isStudying,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = HermieForest,
                        contentColor = HermieCream
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.AutoStories, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Study Now", fontSize = 14.sp)
                }
            }
        }

        // Empty state
        if (selectedPdf == null) {
            Spacer(Modifier.height(48.dp))
            Text(
                "Hermie will extract key facts from your PDF\nand store them in memory for later use.",
                style = TextStyle(
                    fontSize = 14.sp,
                    color = HermieGrey.copy(alpha = 0.7f),
                    lineHeight = 20.sp
                ),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

@Composable
private fun QueueTab(
    queuedItems: List<QueuedStudyItem>,
    onRemove: (Int) -> Unit
) {
    if (queuedItems.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.Queue,
                    contentDescription = null,
                    tint = HermieGrey.copy(alpha = 0.4f),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "No items queued",
                    style = TextStyle(fontSize = 15.sp, color = HermieGrey)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Queue articles or PDFs to study at bedtime",
                    style = TextStyle(fontSize = 13.sp, color = HermieGrey.copy(alpha = 0.7f))
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Text(
                    "These items will be studied when you send Hermie to sleep.",
                    style = TextStyle(fontSize = 13.sp, color = HermieGrey),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            itemsIndexed(queuedItems) { index, item ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = HermieOffWhite
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            when (item) {
                                is QueuedStudyItem.Wikipedia -> Icons.Outlined.Language
                                is QueuedStudyItem.Pdf -> Icons.Outlined.PictureAsPdf
                            },
                            contentDescription = null,
                            tint = HermieForest,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when (item) {
                                    is QueuedStudyItem.Wikipedia -> item.title
                                    is QueuedStudyItem.Pdf -> item.fileName
                                },
                                style = TextStyle(
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = HermieForest
                                ),
                                maxLines = 1
                            )
                            Text(
                                text = when (item) {
                                    is QueuedStudyItem.Wikipedia -> "Wikipedia article"
                                    is QueuedStudyItem.Pdf -> "PDF document"
                                },
                                style = TextStyle(fontSize = 12.sp, color = HermieGrey)
                            )
                        }
                        IconButton(
                            onClick = { onRemove(index) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Remove",
                                tint = HermieGrey,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
