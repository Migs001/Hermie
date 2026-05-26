package com.hermie.assistant.ui.memory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermie.assistant.modules.memory.BufferEntry
import com.hermie.assistant.modules.memory.MemoryNode
import com.hermie.assistant.ui.theme.*

/**
 * Memory Visualizer screen — shows what the memory module has stored.
 * Tabs: Graph Nodes, Short-Term Buffer, Stats
 */
@Composable
fun MemoryScreen(
    nodes: List<MemoryNode>,
    bufferEntries: List<BufferEntry>,
    nodeCount: Int,
    bufferCount: Int,
    unprocessedCount: Int,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Pending", "Nodes")

    // Pending = unprocessed buffer entries (not yet consolidated)
    val pendingCount = unprocessedCount

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
                text = "Memory",
                style = TextStyle(
                    fontFamily = HermieSerif,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = HermieForest
                ),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, "Refresh", tint = HermieForest)
            }
        }

        // Stats banner — green
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = HermieForest,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.padding(26.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Graph Nodes stat
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Outlined.Hub,
                        contentDescription = null,
                        tint = HermieCream,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            "$nodeCount",
                            style = TextStyle(
                                fontFamily = HermieSerif,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = HermieCream
                            )
                        )
                        Text(
                            "Graph Nodes",
                            style = TextStyle(
                                fontFamily = HermieSerif,
                                fontSize = 11.sp,
                                color = HermieCream.copy(alpha = 0.7f)
                            )
                        )
                    }
                }

                // Divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(40.dp)
                        .background(HermieCream.copy(alpha = 0.2f))
                )

                // Pending stat
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp)
                ) {
                    Icon(
                        Icons.Outlined.Storage,
                        contentDescription = null,
                        tint = HermieCream,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            "$pendingCount",
                            style = TextStyle(
                                fontFamily = HermieSerif,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = HermieCream
                            )
                        )
                        Text(
                            "Pending",
                            style = TextStyle(
                                fontFamily = HermieSerif,
                                fontSize = 11.sp,
                                color = HermieCream.copy(alpha = 0.7f)
                            )
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Tab row — Wardrobe-style (green underline, no grey outline)
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
            },
            divider = {}
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            title,
                            style = TextStyle(
                                fontFamily = HermieSerif,
                                fontSize = 13.sp,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTab == index) HermieForest else HermieGrey
                            )
                        )
                    }
                )
            }
        }

        // Tab content
        when (selectedTab) {
            0 -> BufferTab(bufferEntries)
            1 -> NodesTab(nodes)
        }
    }
}

@Composable
private fun NodesTab(nodes: List<MemoryNode>) {
    if (nodes.isEmpty()) {
        EmptyState("No memory nodes yet", "Facts will appear here after consolidation")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(nodes, key = { it.id }) { node ->
            NodeCard(node)
        }
    }
}

@Composable
private fun NodeCard(node: MemoryNode) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = HermieOffWhite,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Category badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = categoryColor(node.category).copy(alpha = 0.15f)
                ) {
                    Text(
                        text = node.category.substringAfter("."),
                        style = TextStyle(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = categoryColor(node.category)
                        ),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Spacer(Modifier.weight(1f))

                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = HermieGrey,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = node.fact,
                style = TextStyle(
                    fontFamily = HermieSerif,
                    fontSize = 14.sp,
                    color = HermieForest,
                    lineHeight = 20.sp
                ),
                maxLines = if (expanded) Int.MAX_VALUE else 2
            )

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    HorizontalDivider(color = HermieTan.copy(alpha = 0.3f))
                    Spacer(Modifier.height(8.dp))
                    DetailRow("ID", "#${node.id}")
                    DetailRow("Category", node.category)
                    DetailRow("Physical", if (node.isPhysical) "yes" else "no")
                    DetailRow("Concept", if (node.isConcept) "yes" else "no")
                    DetailRow("Access count", "${node.accessCount}")
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = TextStyle(fontSize = 11.sp, color = HermieGrey)
        )
        Text(
            value,
            style = TextStyle(fontSize = 11.sp, color = HermieForest, fontFamily = FontFamily.Monospace)
        )
    }
}

@Composable
private fun BufferTab(entries: List<BufferEntry>) {
    if (entries.isEmpty()) {
        EmptyState("Buffer is empty", "Facts extracted by the SLM appear here before consolidation")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(entries, key = { it.id }) { entry ->
            BufferEntryCard(entry)
        }
    }
}

@Composable
private fun BufferEntryCard(entry: BufferEntry) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = HermieOffWhite,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Processed badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (entry.processed) HermieForest.copy(alpha = 0.15f)
                    else HermieTerra.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = if (entry.processed) "consolidated" else "pending",
                        style = TextStyle(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (entry.processed) HermieForest else HermieTerra
                        ),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                // Tags
                if (entry.tags.isNotBlank()) {
                    Text(
                        text = entry.tags,
                        style = TextStyle(fontSize = 10.sp, color = HermieGrey),
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // Extracted fact
            Text(
                text = entry.extracted,
                style = TextStyle(
                    fontFamily = HermieSerif,
                    fontSize = 14.sp,
                    color = HermieForest,
                    lineHeight = 20.sp
                )
            )

            // Raw input (dimmer)
            if (entry.rawInput != entry.extracted) {
                Text(
                    text = entry.rawInput,
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = HermieGrey,
                        lineHeight = 16.sp
                    ),
                    maxLines = 2,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

// StatsTab and StatCard removed — stats are now in the banner

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.Psychology,
                contentDescription = null,
                tint = HermieGrey.copy(alpha = 0.4f),
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = title,
                style = TextStyle(
                    fontFamily = HermieSerif,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = HermieGrey
                )
            )
            Text(
                text = subtitle,
                style = TextStyle(
                    fontFamily = HermieSerif,
                    fontSize = 12.sp,
                    color = HermieGrey.copy(alpha = 0.7f)
                )
            )
        }
    }
}

private fun categoryColor(category: String) = when {
    category.startsWith("user.") -> HermieTerra
    category.startsWith("knowledge.") -> HermieForest
    category.startsWith("context.") -> HermieGrey
    else -> HermieForest
}
