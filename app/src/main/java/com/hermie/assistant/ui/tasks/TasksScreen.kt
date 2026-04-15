package com.hermie.assistant.ui.tasks

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermie.assistant.modules.tasks.*
import com.hermie.assistant.ui.theme.*

/**
 * Tasks screen — workflow/pipeline view with expandable subtask details.
 */
@Composable
fun TasksScreen(
    tasks: List<Task>,
    currentTask: Task?,
    executionStatus: String?,
    onBack: () -> Unit,
    onCreateTask: (String, String, Boolean) -> Unit,
    onSelectTask: (String) -> Unit,
    onDeselectTask: () -> Unit,
    onExecuteAll: () -> Unit,
    onExecuteNext: () -> Unit,
    onDeleteTask: (String) -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }

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
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (currentTask != null) onDeselectTask() else onBack()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = HermieForest)
            }
            Text(
                text = if (currentTask != null) "Task Detail" else "Tasks",
                style = TextStyle(
                    fontFamily = HermieSerif,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = HermieForest
                ),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Filled.Add, "New Task", tint = HermieForest)
            }
        }

        if (currentTask != null) {
            TaskWorkflowView(
                task = currentTask,
                executionStatus = executionStatus,
                onExecuteAll = onExecuteAll,
                onExecuteNext = onExecuteNext,
                modifier = Modifier.weight(1f)
            )
        } else if (tasks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No tasks yet",
                        style = TextStyle(
                            fontFamily = HermieSerif,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = HermieGrey
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Create a task and Hermie will break it down\ninto steps and execute them with tools",
                        style = TextStyle(
                            fontFamily = HermieSerif,
                            fontSize = 14.sp,
                            color = HermieGrey.copy(alpha = 0.7f),
                            lineHeight = 20.sp
                        )
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(tasks) { task ->
                    TaskCard(
                        task = task,
                        onClick = { onSelectTask(task.id) },
                        onDelete = { onDeleteTask(task.id) }
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateTaskDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { title, desc, review ->
                onCreateTask(title, desc, review)
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun TaskCard(
    task: Task,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = HermieOffWhite,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(statusColor(task.status))
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = TextStyle(
                        fontFamily = HermieSerif,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = HermieForest
                    )
                )
                Spacer(Modifier.height(4.dp))
                val completed = task.subtasks.count { it.status == TaskStatus.COMPLETED }
                val failed = task.subtasks.count { it.status == TaskStatus.FAILED }
                Text(
                    text = buildString {
                        append("$completed/${task.subtasks.size} done")
                        if (failed > 0) append(" ($failed failed)")
                        append("  •  ${task.status.name.lowercase().replace('_', ' ')}")
                    },
                    style = TextStyle(fontFamily = HermieSerif, fontSize = 13.sp, color = HermieGrey)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, "Delete", tint = HermieGrey, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * Workflow view with expandable subtask inspection.
 */
@Composable
private fun TaskWorkflowView(
    task: Task,
    executionStatus: String?,
    onExecuteAll: () -> Unit,
    onExecuteNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // Task header
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = HermieForest,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = task.title,
                    style = TextStyle(
                        fontFamily = HermieSerif,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = HermieCream
                    )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = task.description,
                    style = TextStyle(fontFamily = HermieSerif, fontSize = 14.sp, color = HermieCream.copy(alpha = 0.7f))
                )

                // Execution status indicator
                if (executionStatus != null) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = HermieTerra,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = executionStatus,
                            style = TextStyle(fontFamily = HermieSerif, fontSize = 12.sp, color = HermieTerra)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Subtask pipeline
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            items(task.subtasks) { subtask ->
                ExpandableSubtaskNode(
                    subtask = subtask,
                    isLast = subtask == task.subtasks.lastOrNull()
                )
            }
        }

        // Action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val hasNext = task.subtasks.any { it.status == TaskStatus.PENDING }
            val isRunning = task.status == TaskStatus.IN_PROGRESS || executionStatus != null

            OutlinedButton(
                onClick = onExecuteNext,
                enabled = hasNext && !isRunning,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = HermieForest),
                modifier = Modifier.weight(1f)
            ) {
                Text("Next Step", fontFamily = HermieSerif)
            }
            Button(
                onClick = onExecuteAll,
                enabled = hasNext && !isRunning,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HermieForest),
                modifier = Modifier.weight(1f)
            ) {
                Text("Run All", color = HermieCream, fontFamily = HermieSerif)
            }
        }
    }
}

/**
 * Expandable subtask node — click to reveal iteration history, tool calls, results.
 */
@Composable
private fun ExpandableSubtaskNode(subtask: SubTask, isLast: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val rotationAngle by animateFloatAsState(if (expanded) 180f else 0f, label = "arrow")
    val hasDetails = subtask.iterations.isNotEmpty() || subtask.result != null

    val lineColor = statusColor(subtask.status)

    Row(modifier = Modifier.fillMaxWidth()) {
        // Connection line + dot
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(lineColor),
                contentAlignment = Alignment.Center
            ) {
                when (subtask.status) {
                    TaskStatus.COMPLETED -> Icon(Icons.Filled.Check, null, tint = HermieCream, modifier = Modifier.size(10.dp))
                    TaskStatus.FAILED -> Icon(Icons.Filled.Close, null, tint = HermieCream, modifier = Modifier.size(10.dp))
                    TaskStatus.IN_PROGRESS -> CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        color = HermieCream,
                        strokeWidth = 1.5.dp
                    )
                    else -> {}
                }
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(if (expanded) 120.dp else 60.dp)
                        .background(lineColor.copy(alpha = 0.4f))
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // Subtask card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = when (subtask.status) {
                TaskStatus.IN_PROGRESS -> HermieTerra.copy(alpha = 0.08f)
                TaskStatus.COMPLETED -> HermieForest.copy(alpha = 0.05f)
                TaskStatus.FAILED -> HermieError.copy(alpha = 0.05f)
                else -> HermieOffWhite
            },
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else 8.dp)
                .then(if (hasDetails) Modifier.clickable { expanded = !expanded } else Modifier)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = subtask.title,
                        style = TextStyle(
                            fontFamily = HermieSerif,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = HermieForest
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    if (hasDetails) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (expanded) "Collapse" else "Expand",
                            tint = HermieGrey,
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(rotationAngle)
                        )
                    }
                }

                // Show spawned badge
                if (subtask.parentSubtaskId != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "spawned subtask",
                        style = TextStyle(fontFamily = HermieSerif, fontSize = 11.sp, color = HermieTerra)
                    )
                }

                // Summary result (always visible when completed)
                if (subtask.result != null && !expanded) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = subtask.result,
                        style = TextStyle(fontFamily = HermieSerif, fontSize = 13.sp, color = HermieGrey, lineHeight = 18.sp),
                        maxLines = 3
                    )
                }

                // In-progress indicator
                if (subtask.status == TaskStatus.IN_PROGRESS && subtask.iterations.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = HermieTerra,
                        trackColor = HermieTan.copy(alpha = 0.3f)
                    )
                }

                // ── Expanded iteration details ──
                AnimatedVisibility(visible = expanded) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        // Final result
                        if (subtask.result != null) {
                            ResultSection(
                                label = if (subtask.status == TaskStatus.FAILED) "Failed" else "Result",
                                text = subtask.result,
                                isError = subtask.status == TaskStatus.FAILED
                            )
                            Spacer(Modifier.height(12.dp))
                        }

                        // Iteration history
                        subtask.iterations.forEach { iteration ->
                            IterationCard(iteration)
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultSection(label: String, text: String, isError: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isError) HermieError.copy(alpha = 0.08f) else HermieForest.copy(alpha = 0.08f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = TextStyle(
                    fontFamily = HermieSerif,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isError) HermieError else HermieForest,
                    letterSpacing = 1.sp
                )
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = text,
                style = TextStyle(fontFamily = HermieSerif, fontSize = 13.sp, color = HermieForest, lineHeight = 18.sp)
            )
        }
    }
}

/**
 * Single iteration card — shows tool calls and their results.
 */
@Composable
private fun IterationCard(iteration: Iteration) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = HermieTan.copy(alpha = 0.12f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Iteration ${iteration.index + 1}",
                style = TextStyle(
                    fontFamily = HermieSerif,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = HermieGrey,
                    letterSpacing = 1.sp
                )
            )

            if (iteration.toolCalls.isNotEmpty()) {
                iteration.toolCalls.forEach { call ->
                    Spacer(Modifier.height(6.dp))
                    ToolCallRow(call)
                }
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "No tools called",
                    style = TextStyle(fontFamily = HermieSerif, fontSize = 12.sp, color = HermieGrey.copy(alpha = 0.6f))
                )
            }

            if (iteration.spawnedSubtasks.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Spawned ${iteration.spawnedSubtasks.size} subtask(s)",
                    style = TextStyle(fontFamily = HermieSerif, fontSize = 12.sp, color = HermieTerra, fontWeight = FontWeight.Medium)
                )
            }
        }
    }
}

@Composable
private fun ToolCallRow(call: ToolCall) {
    Column {
        // Tool name + params
        Text(
            text = "${call.toolName}(${call.params.entries.joinToString(", ") { "${it.key}=\"${it.value}\"" }})",
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = HermieForest,
                fontWeight = FontWeight.Medium
            )
        )
        Spacer(Modifier.height(2.dp))
        // Result
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (call.isSuccess) HermieForest else HermieError)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = call.result.take(200) + if (call.result.length > 200) "..." else "",
                style = TextStyle(
                    fontFamily = HermieSerif,
                    fontSize = 11.sp,
                    color = if (call.isSuccess) HermieGrey else HermieError,
                    lineHeight = 15.sp
                )
            )
        }
    }
}

@Composable
private fun statusColor(status: TaskStatus) = when (status) {
    TaskStatus.COMPLETED -> HermieForest
    TaskStatus.IN_PROGRESS, TaskStatus.PLANNING -> HermieTerra
    TaskStatus.FAILED -> HermieError
    TaskStatus.PENDING -> HermieTan
    TaskStatus.AWAITING_REVIEW -> HermieTerra
    TaskStatus.SCHEDULED -> HermieGrey
    TaskStatus.QUEUED -> HermieTan
    TaskStatus.PAUSED -> HermieGrey
}

@Composable
private fun CreateTaskDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, Boolean) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var requirePlanReview by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = HermieSurface,
        title = {
            Text(
                "New Task",
                style = TextStyle(fontFamily = HermieSerif, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = HermieForest)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("What do you need done?") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = HermieForest,
                        cursorColor = HermieForest
                    )
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Details (optional)") },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = HermieForest,
                        cursorColor = HermieForest
                    )
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = requirePlanReview,
                        onCheckedChange = { requirePlanReview = it },
                        colors = CheckboxDefaults.colors(checkedColor = HermieForest)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Review plan before running",
                        style = TextStyle(
                            fontFamily = HermieSerif,
                            fontSize = 14.sp,
                            color = HermieForest
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(title, description, requirePlanReview) },
                enabled = title.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = HermieForest),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Create", color = HermieCream, fontFamily = HermieSerif)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = HermieGrey, fontFamily = HermieSerif)
            }
        }
    )
}
