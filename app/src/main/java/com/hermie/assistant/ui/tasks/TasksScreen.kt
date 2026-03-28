package com.hermie.assistant.ui.tasks

import androidx.compose.animation.*
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermie.assistant.modules.tasks.SubTask
import com.hermie.assistant.modules.tasks.Task
import com.hermie.assistant.modules.tasks.TaskStatus
import com.hermie.assistant.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Tasks screen — inspired by reference image 2 (workflow/flowchart style).
 * Shows tasks as a connected pipeline of subtasks.
 */
@Composable
fun TasksScreen(
    tasks: List<Task>,
    currentTask: Task?,
    onBack: () -> Unit,
    onCreateTask: (String, String) -> Unit,
    onSelectTask: (String) -> Unit,
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
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = HermieForest)
            }
            Text(
                text = "Tasks",
                style = TextStyle(
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
            // Show current task detail with workflow view
            TaskWorkflowView(
                task = currentTask,
                onExecuteAll = onExecuteAll,
                onExecuteNext = onExecuteNext,
                modifier = Modifier.weight(1f)
            )
        } else if (tasks.isEmpty()) {
            // Empty state
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
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            color = HermieGrey
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Create a task and Hermie will break it down\ninto manageable steps",
                        style = TextStyle(
                            fontSize = 14.sp,
                            color = HermieGrey.copy(alpha = 0.7f),
                            lineHeight = 20.sp
                        )
                    )
                }
            }
        } else {
            // Task list
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

    // Create task dialog
    if (showCreateDialog) {
        CreateTaskDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { title, desc ->
                onCreateTask(title, desc)
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
            // Status indicator
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        when (task.status) {
                            TaskStatus.COMPLETED -> HermieForest
                            TaskStatus.IN_PROGRESS, TaskStatus.PLANNING -> HermieTerra
                            TaskStatus.FAILED -> HermieError
                            TaskStatus.PENDING -> HermieGrey
                        }
                    )
            )

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = HermieForest
                    )
                )
                Spacer(Modifier.height(4.dp))
                val completed = task.subtasks.count { it.status == TaskStatus.COMPLETED }
                Text(
                    text = "${completed}/${task.subtasks.size} steps  •  ${task.status.name.lowercase().replace('_', ' ')}",
                    style = TextStyle(fontSize = 13.sp, color = HermieGrey)
                )
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, "Delete", tint = HermieGrey, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * Workflow view — shows subtasks as a connected pipeline (reference image 2 style).
 */
@Composable
private fun TaskWorkflowView(
    task: Task,
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
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = HermieCream
                    )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = task.description,
                    style = TextStyle(fontSize = 14.sp, color = HermieCream.copy(alpha = 0.7f))
                )
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
                SubtaskNode(
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
            val isRunning = task.status == TaskStatus.IN_PROGRESS

            OutlinedButton(
                onClick = onExecuteNext,
                enabled = hasNext && !isRunning,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = HermieForest),
                modifier = Modifier.weight(1f)
            ) {
                Text("Next Step")
            }
            Button(
                onClick = onExecuteAll,
                enabled = hasNext && !isRunning,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HermieForest),
                modifier = Modifier.weight(1f)
            ) {
                Text("Run All", color = HermieCream)
            }
        }
    }
}

@Composable
private fun SubtaskNode(subtask: SubTask, isLast: Boolean) {
    val lineColor = when (subtask.status) {
        TaskStatus.COMPLETED -> HermieForest
        TaskStatus.IN_PROGRESS -> HermieTerra
        else -> HermieTan
    }

    Row(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Connection line + dot
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(
                        when (subtask.status) {
                            TaskStatus.COMPLETED -> HermieForest
                            TaskStatus.IN_PROGRESS -> HermieTerra
                            TaskStatus.FAILED -> HermieError
                            else -> HermieTan
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (subtask.status == TaskStatus.COMPLETED) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = HermieCream,
                        modifier = Modifier.size(10.dp)
                    )
                }
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(60.dp)
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
                else -> HermieOffWhite
            },
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = subtask.title,
                    style = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = HermieForest
                    )
                )
                if (subtask.result != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = subtask.result,
                        style = TextStyle(fontSize = 13.sp, color = HermieGrey, lineHeight = 18.sp),
                        maxLines = 4
                    )
                }
                if (subtask.status == TaskStatus.IN_PROGRESS) {
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
            }
        }
    }
}

@Composable
private fun CreateTaskDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = HermieSurface,
        title = {
            Text(
                "New Task",
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = HermieForest)
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
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(title, description) },
                enabled = title.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = HermieForest),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Create", color = HermieCream)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = HermieGrey)
            }
        }
    )
}
