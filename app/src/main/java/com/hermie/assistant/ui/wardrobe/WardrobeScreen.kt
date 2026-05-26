package com.hermie.assistant.ui.wardrobe

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermie.assistant.modules.wardrobe.ClothingItem
import com.hermie.assistant.modules.wardrobe.OutfitSuggestion
import com.hermie.assistant.modules.wardrobe.SavedOutfit
import com.hermie.assistant.modules.wardrobe.WeatherData
import com.hermie.assistant.ui.theme.*

/**
 * Weather display info for the banner.
 */
data class WardrobeWeather(
    val temperature: Int,
    val tempMin: Int?,
    val tempMax: Int?,
    val cloudCover: Int,
    val precipitation: Double,
    val useFahrenheit: Boolean = false
)

@Composable
fun WardrobeScreen(
    isVisionModelDownloaded: Boolean,
    items: List<ClothingItem>,
    unprocessedCount: Int,
    occasions: List<Pair<String, Int>>,
    outfitSuggestions: List<OutfitSuggestion>,
    isGenerating: Boolean,
    favorites: List<SavedOutfit>,
    weather: WardrobeWeather? = null,
    onGenerateOutfits: (occasion: String, formality: Int, userRequest: String?) -> Unit,
    onPickOutfit: (OutfitSuggestion) -> Unit,
    onRejectAll: () -> Unit,
    onTryAgain: (() -> Unit)? = null,
    onAddClothes: () -> Unit,
    onDeactivateItem: (Long) -> Unit,
    onEditItem: (ClothingItem) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onUpdateFormality: ((String, Int) -> Unit)? = null,
    onDownloadVision: () -> Unit,
    onBack: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Outfits", "Wardrobe", "Favourites")
    var selectedOccasion by remember { mutableStateOf(occasions.firstOrNull()?.first ?: "Casual") }
    var selectedFormality by remember { mutableIntStateOf(occasions.firstOrNull()?.second ?: 3) }
    var userRequest by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HermieSurface)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top bar — same padding as other modules
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
                text = "Wardrobe",
                style = TextStyle(
                    fontFamily = HermieSerif,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = HermieForest
                ),
                modifier = Modifier.weight(1f)
            )
        }

        // Banner — weather or vision model gate
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (isVisionModelDownloaded) HermieForest else HermieTerra,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Column(modifier = Modifier.padding(26.dp)) {
                if (!isVisionModelDownloaded) {
                    // Vision model not downloaded — orange banner
                    Icon(
                        Icons.Outlined.Checkroom,
                        contentDescription = null,
                        tint = HermieCream,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Wardrobe",
                        style = TextStyle(
                            fontFamily = HermieSerif,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = HermieCream
                        )
                    )
                    Text(
                        "Download the vision model to categorize your clothes",
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
                        modifier = Modifier.clickable(onClick = onDownloadVision)
                    ) {
                        Text(
                            "Download Vision Model",
                            style = TextStyle(
                                fontFamily = HermieSerif,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = HermieCream
                            ),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                        )
                    }
                } else if (weather != null) {
                    // Green banner — weather info
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Weather icon + current temp
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                getWeatherIcon(weather),
                                contentDescription = null,
                                tint = HermieCream,
                                modifier = Modifier.size(32.dp)
                            )
                            Column {
                                Text(
                                    "${weather.temperature}°",
                                    style = TextStyle(
                                        fontFamily = HermieSerif,
                                        fontSize = 28.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = HermieCream
                                    )
                                )
                                Text(
                                    getWeatherDescription(weather),
                                    style = TextStyle(
                                        fontFamily = HermieSerif,
                                        fontSize = 11.sp,
                                        color = HermieCream.copy(alpha = 0.7f)
                                    )
                                )
                            }
                        }

                        // Min / Max
                        if (weather.tempMin != null && weather.tempMax != null) {
                            Column(horizontalAlignment = Alignment.End) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.ArrowUpward,
                                        contentDescription = null,
                                        tint = HermieCream.copy(alpha = 0.7f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        "${weather.tempMax}°",
                                        style = TextStyle(
                                            fontFamily = HermieSerif,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = HermieCream
                                        )
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.ArrowDownward,
                                        contentDescription = null,
                                        tint = HermieCream.copy(alpha = 0.7f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        "${weather.tempMin}°",
                                        style = TextStyle(
                                            fontFamily = HermieSerif,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = HermieCream.copy(alpha = 0.8f)
                                        )
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // No weather data — simple green banner
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Checkroom,
                            contentDescription = null,
                            tint = HermieCream,
                            modifier = Modifier.size(28.dp)
                        )
                        Column {
                            Text(
                                "${items.size} items",
                                style = TextStyle(
                                    fontFamily = HermieSerif,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = HermieCream
                                )
                            )
                            Text(
                                "in your wardrobe",
                                style = TextStyle(
                                    fontFamily = HermieSerif,
                                    fontSize = 12.sp,
                                    color = HermieCream.copy(alpha = 0.7f)
                                )
                            )
                        }
                    }
                }
            }
        }

        // Pending photos badge
        if (unprocessedCount > 0) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = HermieTan.copy(alpha = 0.3f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Outlined.HourglassTop, null, tint = HermieForest, modifier = Modifier.size(18.dp))
                    Text(
                        "$unprocessedCount photo${if (unprocessedCount > 1) "s" else ""} pending — will be categorized next sleep",
                        style = TextStyle(fontFamily = HermieSerif, fontSize = 12.sp, color = HermieForest)
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // Tabs — green underline, no grey outline
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

        // FAB overlaid on wardrobe tab
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> OutfitTab(
                    occasions = occasions,
                    selectedOccasion = selectedOccasion,
                    selectedFormality = selectedFormality,
                    userRequest = userRequest,
                    suggestions = outfitSuggestions,
                    isGenerating = isGenerating,
                    hasItems = items.isNotEmpty(),
                    onOccasionSelected = { name, formality ->
                        selectedOccasion = name
                        selectedFormality = formality
                    },
                    onFormalityChanged = { name, formality ->
                        onUpdateFormality?.invoke(name, formality)
                        if (name == selectedOccasion) selectedFormality = formality
                    },
                    onUserRequestChanged = { userRequest = it },
                    onGenerate = { onGenerateOutfits(selectedOccasion, selectedFormality, userRequest.ifBlank { null }) },
                    onPick = onPickOutfit,
                    onRejectAll = onRejectAll,
                    onTryAgain = onTryAgain
                )
                1 -> WardrobeTab(
                    items = items,
                    onDeactivate = onDeactivateItem,
                    onEdit = onEditItem
                )
                2 -> FavoritesTab(
                    favorites = favorites,
                    onToggleFavorite = onToggleFavorite
                )
            }

            // FAB for camera — only on Wardrobe tab
            if (selectedTab == 1) {
                FloatingActionButton(
                    onClick = onAddClothes,
                    containerColor = HermieForest,
                    contentColor = HermieCream,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) {
                    Icon(Icons.Filled.CameraAlt, "Add clothes")
                }
            }
        }
    }
}

// ── Weather helpers ────────────────────────────────────────

private fun getWeatherIcon(w: WardrobeWeather) = when {
    w.precipitation > 2.0 -> Icons.Filled.Thunderstorm
    w.precipitation > 0.5 -> Icons.Filled.WaterDrop
    w.cloudCover > 80 -> Icons.Filled.Cloud
    w.cloudCover > 40 -> Icons.Filled.FilterDrama
    else -> Icons.Filled.WbSunny
}

private fun getWeatherDescription(w: WardrobeWeather) = when {
    w.precipitation > 2.0 -> "Heavy rain"
    w.precipitation > 0.5 -> "Light rain"
    w.cloudCover > 80 -> "Overcast"
    w.cloudCover > 40 -> "Partly cloudy"
    else -> "Clear"
}

// ── Outfit Tab ──────────────────────────────────────────────

@Composable
private fun OutfitTab(
    occasions: List<Pair<String, Int>>,
    selectedOccasion: String,
    selectedFormality: Int,
    userRequest: String,
    suggestions: List<OutfitSuggestion>,
    isGenerating: Boolean,
    hasItems: Boolean,
    onOccasionSelected: (String, Int) -> Unit,
    onFormalityChanged: (String, Int) -> Unit,
    onUserRequestChanged: (String) -> Unit,
    onGenerate: () -> Unit,
    onPick: (OutfitSuggestion) -> Unit,
    onRejectAll: () -> Unit,
    onTryAgain: (() -> Unit)? = null
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!hasItems) {
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = HermieOffWhite,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Outlined.Checkroom, null, tint = HermieGrey, modifier = Modifier.size(48.dp))
                        Text(
                            "No clothes yet",
                            style = TextStyle(fontFamily = HermieSerif, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = HermieForest)
                        )
                        Text(
                            "Go to the Wardrobe tab and add some photos of your clothes!",
                            style = TextStyle(fontFamily = HermieSerif, fontSize = 13.sp, color = HermieGrey)
                        )
                    }
                }
            }
            return@LazyColumn
        }

        // Occasion chips with formality popup
        item {
            Text(
                "What's the occasion?",
                style = TextStyle(fontFamily = HermieSerif, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = HermieForest)
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                occasions.forEach { (name, formality) ->
                    Box(modifier = Modifier.weight(1f)) {
                        OccasionChip(
                            name = name,
                            formality = formality,
                            isSelected = name == selectedOccasion,
                            onSelect = { onOccasionSelected(name, formality) },
                            onFormalityChange = { newFormality -> onFormalityChanged(name, newFormality) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // User request input
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = HermieOffWhite,
                modifier = Modifier.fillMaxWidth()
            ) {
                BasicTextField(
                    value = userRequest,
                    onValueChange = onUserRequestChanged,
                    textStyle = TextStyle(
                        fontFamily = HermieSerif,
                        fontSize = 14.sp,
                        color = HermieForest
                    ),
                    cursorBrush = SolidColor(HermieForest),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    decorationBox = { innerTextField ->
                        Box {
                            if (userRequest.isEmpty()) {
                                Text(
                                    "I want to wear my blue shirt...",
                                    style = TextStyle(
                                        fontFamily = HermieSerif,
                                        fontSize = 14.sp,
                                        color = HermieGrey.copy(alpha = 0.5f)
                                    )
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }
        }

        // Generate button — green, orange when generating
        item {
            val buttonColor = if (isGenerating) HermieTerra else HermieForest
            Button(
                onClick = onGenerate,
                enabled = !isGenerating,
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonColor,
                    contentColor = HermieCream,
                    disabledContainerColor = HermieTerra,
                    disabledContentColor = HermieCream
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = HermieCream,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Finding...", style = TextStyle(fontFamily = HermieSerif, fontSize = 14.sp))
                } else {
                    Text("Dress me up!", style = TextStyle(fontFamily = HermieSerif, fontSize = 14.sp))
                }
            }
        }

        // Outfit suggestions
        if (suggestions.isNotEmpty()) {
            item {
                Text(
                    "Here are 3 outfit ideas:",
                    style = TextStyle(fontFamily = HermieSerif, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = HermieForest),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            items(suggestions.withIndex().toList()) { (index, suggestion) ->
                OutfitCard(
                    index = index + 1,
                    suggestion = suggestion,
                    onPick = { onPick(suggestion) }
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onRejectAll,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = HermieGrey)
                    ) {
                        Text("None of these", style = TextStyle(fontFamily = HermieSerif, fontSize = 13.sp))
                    }
                    if (onTryAgain != null) {
                        Button(
                            onClick = onTryAgain,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = HermieForest,
                                contentColor = HermieCream
                            )
                        ) {
                            Text("Try Again", style = TextStyle(fontFamily = HermieSerif, fontSize = 13.sp))
                        }
                    }
                }
            }
        }
    }
}

// ── Occasion Chip with formality popup ──────────────────────

@Composable
private fun OccasionChip(
    name: String,
    formality: Int,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onFormalityChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var showFormality by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (isSelected) HermieForest else HermieOffWhite,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect() }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    name,
                    style = TextStyle(
                        fontFamily = HermieSerif,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) HermieCream else HermieForest
                    )
                )
                Spacer(Modifier.width(6.dp))
                // Formality badge — tap to open popup
                Surface(
                    shape = CircleShape,
                    color = if (isSelected) HermieCream.copy(alpha = 0.2f) else HermieTan.copy(alpha = 0.4f),
                    modifier = Modifier
                        .size(22.dp)
                        .clickable { showFormality = true }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "$formality",
                            style = TextStyle(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) HermieCream else HermieForest
                            )
                        )
                    }
                }
            }
        }

        // Formality dropdown
        DropdownMenu(
            expanded = showFormality,
            onDismissRequest = { showFormality = false },
            offset = DpOffset(0.dp, 4.dp)
        ) {
            Text(
                "Formality",
                style = TextStyle(
                    fontFamily = HermieSerif,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = HermieGrey,
                    letterSpacing = 0.5.sp
                ),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
            (1..5).forEach { level ->
                val label = when (level) {
                    1 -> "Very Casual"
                    2 -> "Casual"
                    3 -> "Smart Casual"
                    4 -> "Formal"
                    5 -> "Very Formal"
                    else -> ""
                }
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "$level",
                                style = TextStyle(
                                    fontFamily = HermieSerif,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (level == formality) HermieForest else HermieGrey
                                )
                            )
                            Text(
                                label,
                                style = TextStyle(
                                    fontFamily = HermieSerif,
                                    fontSize = 13.sp,
                                    color = if (level == formality) HermieForest else HermieGrey
                                )
                            )
                        }
                    },
                    onClick = {
                        onFormalityChange(level)
                        showFormality = false
                    }
                )
            }
        }
    }
}

// ── Outfit Card ─────────────────────────────────────────────

@Composable
private fun OutfitCard(
    index: Int,
    suggestion: OutfitSuggestion,
    onPick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = HermieOffWhite,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(HermieForest),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$index",
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = HermieCream)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "Outfit $index",
                    style = TextStyle(fontFamily = HermieSerif, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = HermieForest)
                )
            }

            val slotOrder = listOf("top" to "Top", "bottom" to "Bottom", "outer" to "Outer", "shoes" to "Shoes", "accessory" to "Accessory")
            for ((slot, label) in slotOrder) {
                val item = suggestion.items[slot] ?: continue
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "$label:",
                        style = TextStyle(fontFamily = HermieSerif, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = HermieGrey),
                        modifier = Modifier.width(70.dp)
                    )
                    Text(
                        item.description.ifBlank { "${item.color} ${item.type}" },
                        style = TextStyle(fontFamily = HermieSerif, fontSize = 12.sp, color = HermieForest),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (suggestion.reasoning.isNotBlank()) {
                Text(
                    suggestion.reasoning,
                    style = TextStyle(fontFamily = HermieSerif, fontSize = 11.sp, color = HermieGrey),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Button(
                onClick = onPick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = HermieForest,
                    contentColor = HermieCream
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                Text("Pick this outfit", style = TextStyle(fontFamily = HermieSerif, fontSize = 13.sp))
            }
        }
    }
}

// ── Wardrobe Tab ────────────────────────────────────────────

@Composable
private fun WardrobeTab(
    items: List<ClothingItem>,
    onDeactivate: (Long) -> Unit,
    onEdit: (ClothingItem) -> Unit
) {
    if (items.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.CameraAlt, null, tint = HermieGrey, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text(
                    "Take photos of your clothes",
                    style = TextStyle(fontFamily = HermieSerif, fontSize = 15.sp, color = HermieGrey)
                )
                Text(
                    "Use the camera button below to snap photos.\nThey'll be categorized when Hermie sleeps.",
                    style = TextStyle(fontFamily = HermieSerif, fontSize = 12.sp, color = HermieGrey.copy(alpha = 0.7f)),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        return
    }

    val grouped = items.groupBy { it.type }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        grouped.forEach { (type, typeItems) ->
            item {
                Text(
                    type.replaceFirstChar { it.uppercase() } + " (${typeItems.size})",
                    style = TextStyle(
                        fontFamily = HermieSerif,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = HermieForest,
                        letterSpacing = 0.5.sp
                    ),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            items(typeItems) { clothingItem ->
                ClothingItemCard(
                    item = clothingItem,
                    onEdit = { onEdit(clothingItem) },
                    onDeactivate = { onDeactivate(clothingItem.id) }
                )
            }
        }
    }
}

@Composable
private fun ClothingItemCard(
    item: ClothingItem,
    onEdit: () -> Unit,
    onDeactivate: () -> Unit
) {
    var showActions by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = HermieOffWhite,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showActions = !showActions }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(HermieForest.copy(alpha = 0.6f))
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.description.ifBlank { "${item.color} ${item.type}" },
                        style = TextStyle(fontFamily = HermieSerif, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = HermieForest),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${item.color} • ${item.pattern} • ${item.fabric} • Formality ${item.formality}/5",
                        style = TextStyle(fontFamily = HermieSerif, fontSize = 11.sp, color = HermieGrey)
                    )
                }

                if (item.wearCount > 0) {
                    Text(
                        "${item.wearCount}x",
                        style = TextStyle(fontFamily = HermieSerif, fontSize = 11.sp, color = HermieGrey)
                    )
                }
            }

            AnimatedVisibility(visible = showActions) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(onClick = onEdit) {
                        Text("Edit", style = TextStyle(fontFamily = HermieSerif, fontSize = 12.sp, color = HermieForest))
                    }
                    TextButton(onClick = onDeactivate) {
                        Text("Remove", style = TextStyle(fontFamily = HermieSerif, fontSize = 12.sp, color = HermieError))
                    }
                }
            }
        }
    }
}

// ── Favorites Tab ───────────────────────────────────────────

@Composable
private fun FavoritesTab(
    favorites: List<SavedOutfit>,
    onToggleFavorite: (Long) -> Unit
) {
    if (favorites.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.FavoriteBorder, null, tint = HermieGrey, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text(
                    "No favourite outfits yet",
                    style = TextStyle(fontFamily = HermieSerif, fontSize = 15.sp, color = HermieGrey)
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(favorites) { outfit ->
            SavedOutfitCard(outfit = outfit, onToggleFavorite = { onToggleFavorite(outfit.id) })
        }
    }
}

@Composable
private fun SavedOutfitCard(
    outfit: SavedOutfit,
    onToggleFavorite: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = HermieOffWhite,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        outfit.occasion,
                        style = TextStyle(fontFamily = HermieSerif, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = HermieForest)
                    )
                    if (outfit.weatherSummary.isNotBlank()) {
                        Text(
                            outfit.weatherSummary,
                            style = TextStyle(fontFamily = HermieSerif, fontSize = 11.sp, color = HermieGrey)
                        )
                    }
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (outfit.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        "Toggle favourite",
                        tint = if (outfit.isFavorite) HermieForest else HermieGrey
                    )
                }
            }

            val slotOrder = listOf("top" to "Top", "bottom" to "Bottom", "outer" to "Outer", "shoes" to "Shoes", "accessory" to "Accessory")
            for ((slot, label) in slotOrder) {
                val item = outfit.items[slot] ?: continue
                Text(
                    "$label: ${item.description.ifBlank { "${item.color} ${item.type}" }}",
                    style = TextStyle(fontFamily = HermieSerif, fontSize = 12.sp, color = HermieForest),
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                )
            }
        }
    }
}
