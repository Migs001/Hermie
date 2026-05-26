package com.hermie.assistant.ui.dnd

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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import com.hermie.assistant.modules.dnd.DndFilterRule
import com.hermie.assistant.modules.dnd.RuleType
import com.hermie.assistant.ui.theme.*

/**
 * Smart Do Not Disturb settings screen.
 * Toggle DND, manage rules, view missed notification summary.
 */
@Composable
fun DndScreen(
    isDndEnabled: Boolean,
    hasPolicyAccess: Boolean,
    hasNotificationAccess: Boolean,
    rules: List<DndFilterRule>,
    silencedCount: Int,
    onToggleDnd: (Boolean) -> Unit,
    onRequestPolicyAccess: () -> Unit,
    onRequestNotificationAccess: () -> Unit,
    onAddRule: (description: String, type: RuleType, contact: String?, app: String?) -> Unit,
    onRemoveRule: (String) -> Unit,
    onViewMissed: () -> Unit,
    onBack: () -> Unit
) {
    var showAddRuleDialog by remember { mutableStateOf(false) }

    val allPermissionsGranted = hasPolicyAccess && hasNotificationAccess

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
                "Do Not Disturb",
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
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // Banner — orange when permissions missing, then shows DND toggle
            item {
                val bannerColor = when {
                    !allPermissionsGranted -> HermieTerra
                    isDndEnabled -> HermieTerra
                    else -> HermieForest
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = bannerColor,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(26.dp)) {
                        if (!allPermissionsGranted) {
                            // Permissions not granted — orange banner
                            Icon(
                                Icons.Outlined.DoNotDisturb,
                                contentDescription = null,
                                tint = HermieCream,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Smart Do Not Disturb",
                                style = TextStyle(
                                    fontFamily = HermieSerif,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = HermieCream
                                )
                            )
                            Text(
                                "Grant permissions to enable smart notification filtering",
                                style = TextStyle(
                                    fontFamily = HermieSerif,
                                    fontSize = 13.sp,
                                    color = HermieCream.copy(alpha = 0.7f)
                                )
                            )
                            Spacer(Modifier.height(12.dp))
                            if (!hasPolicyAccess) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = HermieForest,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp)
                                        .clickable(onClick = onRequestPolicyAccess)
                                ) {
                                    Text(
                                        "Grant DND Access",
                                        style = TextStyle(
                                            fontFamily = HermieSerif,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = HermieCream
                                        ),
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                    )
                                }
                            }
                            if (!hasNotificationAccess) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = HermieForest,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(onClick = onRequestNotificationAccess)
                                ) {
                                    Text(
                                        "Grant Notification Access",
                                        style = TextStyle(
                                            fontFamily = HermieSerif,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = HermieCream
                                        ),
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                    )
                                }
                            }
                        } else {
                            // Permissions granted — DND toggle banner
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Smart Do Not Disturb",
                                        style = TextStyle(
                                            fontFamily = HermieSerif,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = HermieCream
                                        )
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        if (isDndEnabled) "Active — filtering notifications"
                                        else "Off — all notifications come through",
                                        style = TextStyle(
                                            fontFamily = HermieSerif,
                                            fontSize = 14.sp,
                                            color = HermieCream.copy(alpha = 0.8f)
                                        )
                                    )
                                    if (isDndEnabled && silencedCount > 0) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "$silencedCount notifications silenced",
                                            style = TextStyle(
                                                fontFamily = HermieSerif,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = HermieCream.copy(alpha = 0.7f)
                                            )
                                        )
                                    }
                                }

                                Switch(
                                    checked = isDndEnabled,
                                    onCheckedChange = { onToggleDnd(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = HermieCream,
                                        checkedTrackColor = HermieCream.copy(alpha = 0.3f),
                                        uncheckedThumbColor = HermieCream,
                                        uncheckedTrackColor = HermieCream.copy(alpha = 0.2f)
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Missed notifications button
            if (isDndEnabled) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = HermieTerra.copy(alpha = 0.1f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onViewMissed)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Notifications,
                                contentDescription = null,
                                tint = HermieTerra
                            )
                            Text(
                                "View missed notifications",
                                style = TextStyle(
                                    fontFamily = HermieSerif,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = HermieTerra
                                )
                            )
                        }
                    }
                }
            }

            // Rules section header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Filter Rules",
                        style = TextStyle(
                            fontFamily = HermieSerif,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = HermieForest
                        )
                    )
                    TextButton(onClick = { showAddRuleDialog = true }) {
                        Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Rule")
                    }
                }
            }

            // Info text
            if (rules.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = HermieOffWhite,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "No rules yet",
                                style = TextStyle(
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = HermieForest
                                )
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "When DND is on, all notifications are silenced by default. " +
                                "Add rules to choose what breaks through — " +
                                "specific contacts, apps, or smart filters like delivery alerts.",
                                style = TextStyle(fontSize = 13.sp, color = HermieGrey)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Tip: You can also tell Hermie in chat:\n" +
                                "\"Turn on DND but let Mom's messages through\"",
                                style = TextStyle(
                                    fontSize = 12.sp,
                                    color = HermieTerra,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                    }
                }
            }

            // Rules list
            items(rules, key = { it.id }) { rule ->
                RuleCard(rule = rule, onRemove = { onRemoveRule(rule.id) })
            }

            // How it works section
            item {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = HermieOffWhite,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "How Smart DND works",
                            style = TextStyle(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = HermieForest
                            )
                        )
                        Spacer(Modifier.height(8.dp))
                        HowItWorksItem(
                            icon = Icons.Outlined.DoNotDisturb,
                            text = "Silences your phone and intercepts all notifications"
                        )
                        HowItWorksItem(
                            icon = Icons.Outlined.FilterList,
                            text = "Checks your rules first — contacts and apps you allow always get through"
                        )
                        HowItWorksItem(
                            icon = Icons.Outlined.Psychology,
                            text = "Uses Hermie's local AI to judge if unclear notifications are important"
                        )
                        HowItWorksItem(
                            icon = Icons.Outlined.NotificationsActive,
                            text = "Alerts you with sound for truly important messages, even in DND"
                        )
                        HowItWorksItem(
                            icon = Icons.Outlined.Summarize,
                            text = "Keeps a log so you can ask \"what did I miss?\" anytime"
                        )
                    }
                }
            }
        }
    }

    // Add rule dialog
    if (showAddRuleDialog) {
        AddRuleDialog(
            onDismiss = { showAddRuleDialog = false },
            onAdd = { desc, type, contact, app ->
                onAddRule(desc, type, contact, app)
                showAddRuleDialog = false
            }
        )
    }
}

// PermissionsCard and PermissionRow are now inlined in the banner above

@Composable
private fun RuleCard(rule: DndFilterRule, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = HermieOffWhite,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Type icon
            Icon(
                when (rule.ruleType) {
                    RuleType.ALLOW_CONTACT -> Icons.Outlined.Person
                    RuleType.ALLOW_APP -> Icons.Outlined.Apps
                    RuleType.BLOCK_APP -> Icons.Outlined.Block
                    RuleType.CUSTOM_LLM -> Icons.Outlined.Psychology
                },
                contentDescription = null,
                tint = when (rule.ruleType) {
                    RuleType.BLOCK_APP -> HermieError
                    RuleType.CUSTOM_LLM -> HermieTerra
                    else -> HermieForest
                },
                modifier = Modifier.size(24.dp)
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    rule.description,
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = HermieForest
                    )
                )
                Text(
                    buildString {
                        append(when (rule.ruleType) {
                            RuleType.ALLOW_CONTACT -> "Always allows through"
                            RuleType.ALLOW_APP -> "Always allows through"
                            RuleType.BLOCK_APP -> "Always silenced"
                            RuleType.CUSTOM_LLM -> "Smart filter"
                        })
                        if (rule.isTemporary) append(" \u00b7 temporary")
                    },
                    style = TextStyle(fontSize = 12.sp, color = HermieGrey)
                )
            }

            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
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

@Composable
private fun HowItWorksItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, null, tint = HermieGrey, modifier = Modifier.size(20.dp))
        Text(text, style = TextStyle(fontSize = 13.sp, color = HermieGrey, lineHeight = 18.sp))
    }
}

/**
 * Preset templates for adding rules — guides users instead of open-ended input.
 */
private data class RulePreset(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val subtitle: String,
    val ruleType: RuleType,
    val fieldLabel: String,
    val fieldPlaceholder: String,
    val descriptionTemplate: String, // {value} gets replaced with user input
    val examples: List<String>
)

private val RULE_PRESETS = listOf(
    RulePreset(
        icon = Icons.Outlined.Person,
        title = "Allow a contact",
        subtitle = "Always let this person's messages through",
        ruleType = RuleType.ALLOW_CONTACT,
        fieldLabel = "Contact name",
        fieldPlaceholder = "Mom",
        descriptionTemplate = "Allow notifications from {value}",
        examples = listOf("Mom", "John Smith", "Boss")
    ),
    RulePreset(
        icon = Icons.Outlined.Apps,
        title = "Allow an app",
        subtitle = "Always let notifications from this app through",
        ruleType = RuleType.ALLOW_APP,
        fieldLabel = "App name",
        fieldPlaceholder = "WhatsApp",
        descriptionTemplate = "Allow notifications from {value}",
        examples = listOf("WhatsApp", "Slack", "Gmail")
    ),
    RulePreset(
        icon = Icons.Outlined.LocalShipping,
        title = "Delivery alerts",
        subtitle = "Get notified about package deliveries and shipping updates",
        ruleType = RuleType.CUSTOM_LLM,
        fieldLabel = "",
        fieldPlaceholder = "",
        descriptionTemplate = "Alert on delivery, shipping, or package notifications",
        examples = emptyList()
    ),
    RulePreset(
        icon = Icons.Outlined.Warning,
        title = "Urgent messages only",
        subtitle = "Only break through for truly urgent or emergency texts",
        ruleType = RuleType.CUSTOM_LLM,
        fieldLabel = "",
        fieldPlaceholder = "",
        descriptionTemplate = "Alert on urgent, emergency, or time-sensitive messages",
        examples = emptyList()
    ),
    RulePreset(
        icon = Icons.Outlined.Block,
        title = "Block an app",
        subtitle = "Always silence notifications from this app",
        ruleType = RuleType.BLOCK_APP,
        fieldLabel = "App name",
        fieldPlaceholder = "Instagram",
        descriptionTemplate = "Block notifications from {value}",
        examples = listOf("Instagram", "TikTok", "Facebook")
    ),
    RulePreset(
        icon = Icons.Outlined.Edit,
        title = "Custom smart rule",
        subtitle = "Describe what you want in plain language",
        ruleType = RuleType.CUSTOM_LLM,
        fieldLabel = "What should break through DND?",
        fieldPlaceholder = "e.g. Groupchat messages about weekend trip",
        descriptionTemplate = "{value}",
        examples = listOf(
            "Messages mentioning money or payment",
            "Groupchat: Weekend trip",
            "Work emails about the deadline"
        )
    )
)

@Composable
private fun AddRuleDialog(
    onDismiss: () -> Unit,
    onAdd: (description: String, type: RuleType, contact: String?, app: String?) -> Unit
) {
    var selectedPreset by remember { mutableStateOf<RulePreset?>(null) }
    var fieldValue by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            if (selectedPreset == null) {
                Text("What kind of rule?", fontWeight = FontWeight.Bold)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { selectedPreset = null; fieldValue = "" },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "Back",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(selectedPreset!!.title, fontWeight = FontWeight.Bold)
                }
            }
        },
        text = {
            if (selectedPreset == null) {
                // Step 1: Pick a preset
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RULE_PRESETS.forEach { preset ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = HermieOffWhite,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (preset.fieldLabel.isEmpty()) {
                                        // No user input needed — add directly
                                        onAdd(
                                            preset.descriptionTemplate,
                                            preset.ruleType,
                                            null,
                                            null
                                        )
                                    } else {
                                        selectedPreset = preset
                                        fieldValue = ""
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    preset.icon,
                                    contentDescription = null,
                                    tint = when (preset.ruleType) {
                                        RuleType.BLOCK_APP -> HermieError
                                        RuleType.CUSTOM_LLM -> HermieTerra
                                        else -> HermieForest
                                    },
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        preset.title,
                                        style = TextStyle(
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = HermieForest
                                        )
                                    )
                                    Text(
                                        preset.subtitle,
                                        style = TextStyle(
                                            fontSize = 12.sp,
                                            color = HermieGrey
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Step 2: Fill in the details
                val preset = selectedPreset!!
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Explanation
                    Text(
                        preset.subtitle,
                        style = TextStyle(fontSize = 13.sp, color = HermieGrey)
                    )

                    // Input field
                    OutlinedTextField(
                        value = fieldValue,
                        onValueChange = { fieldValue = it },
                        label = { Text(preset.fieldLabel) },
                        placeholder = { Text(preset.fieldPlaceholder, fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // Example chips
                    if (preset.examples.isNotEmpty()) {
                        Text(
                            "Examples:",
                            style = TextStyle(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = HermieGrey
                            )
                        )
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            preset.examples.forEach { example ->
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = HermieTerra.copy(alpha = 0.1f),
                                    modifier = Modifier.clickable { fieldValue = example }
                                ) {
                                    Text(
                                        example,
                                        modifier = Modifier.padding(
                                            horizontal = 10.dp,
                                            vertical = 6.dp
                                        ),
                                        style = TextStyle(
                                            fontSize = 12.sp,
                                            color = HermieTerra
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (selectedPreset != null) {
                TextButton(
                    onClick = {
                        val preset = selectedPreset!!
                        val desc = preset.descriptionTemplate.replace("{value}", fieldValue.trim())
                        val contact = if (preset.ruleType == RuleType.ALLOW_CONTACT)
                            fieldValue.trim() else null
                        val app = if (preset.ruleType == RuleType.ALLOW_APP ||
                            preset.ruleType == RuleType.BLOCK_APP)
                            fieldValue.trim() else null
                        onAdd(desc, preset.ruleType, contact, app)
                    },
                    enabled = fieldValue.isNotBlank()
                ) {
                    Text("Add Rule")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
