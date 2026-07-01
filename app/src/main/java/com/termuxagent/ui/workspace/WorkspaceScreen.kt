package com.termuxagent.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.termuxagent.data.workspace.WorkspaceManager
import com.termuxagent.ui.ViewModelFactories
import com.termuxagent.ui.theme.MonoTextStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    onBack: () -> Unit,
    vm: WorkspaceViewModel = viewModel(factory = ViewModelFactories.workspace)
) {
    val state by vm.state.collectAsState()
    var showNewDialog by remember { mutableStateOf<NewDialog?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    "Workspace",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    state.currentPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }
            IconButton(onClick = {
                if (state.currentPath != ".") vm.navigateUp()
            }, enabled = state.currentPath != ".") {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Up", modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = { showNewDialog = NewDialog.File }) {
                Icon(Icons.Rounded.InsertDriveFile, contentDescription = "New file")
            }
            IconButton(onClick = { showNewDialog = NewDialog.Folder }) {
                Icon(Icons.Rounded.CreateNewFolder, contentDescription = "New folder")
            }
            IconButton(onClick = { vm.refresh() }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (state.entries.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(
                        "Workspace is empty.\nAsk the agent to create something.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                ) {
                    items(state.entries, key = { it.path }) { entry ->
                        EntryRow(entry = entry, onClick = {
                            if (entry.isDirectory) vm.navigate(entry.path)
                            else vm.open(entry)
                        }, onDelete = { vm.delete(entry) })
                    }
                }
            }
        }
    }

    // Editor overlay
    state.editing?.let { target ->
        FileEditorSheet(
            target = target,
            onClose = { vm.closeEditor() },
            onSave = { content -> vm.saveContent(target.path, content) }
        )
    }

    // New file/folder dialog
    when (showNewDialog) {
        NewDialog.File -> NameDialog(
            title = "New file",
            label = "File name",
            onConfirm = { vm.newFile(it); showNewDialog = null },
            onDismiss = { showNewDialog = null }
        )
        NewDialog.Folder -> NameDialog(
            title = "New folder",
            label = "Folder name",
            onConfirm = { vm.newFolder(it); showNewDialog = null },
            onDismiss = { showNewDialog = null }
        )
        null -> {}
    }
}

private enum class NewDialog { File, Folder }

@Composable
private fun EntryRow(
    entry: WorkspaceManager.WorkspaceEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (entry.isDirectory) Icons.Rounded.Folder else Icons.Rounded.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
            val sub = if (entry.isDirectory) "folder" else formatBytes(entry.size)
            Text(
                text = "$sub · ${SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date(entry.lastModified))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Rounded.Close, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FileEditorSheet(
    target: EditTarget,
    onClose: () -> Unit,
    onSave: (String) -> Unit
) {
    var content by remember(target.path) { mutableStateOf(target.initialContent) }
    val isDir = target is EditTarget.Directory

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Editor top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    target.path.substringAfterLast('/'),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    if (isDir) "directory (read-only)" else "${formatBytes(target.let { (it as? EditTarget.TextFile)?.totalBytes ?: 0L })}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!isDir) {
                IconButton(onClick = { onSave(content) }) {
                    Icon(Icons.Rounded.Save, contentDescription = "Save", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        // Content area
        if (isDir) {
            // Directory tree (read-only)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = content,
                    style = MonoTextStyle.copy(color = MaterialTheme.colorScheme.onBackground)
                )
            }
        } else {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                textStyle = MonoTextStyle.copy(color = MaterialTheme.colorScheme.onBackground),
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    label: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim()) }
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "%.1fK".format(bytes / 1024.0)
    else -> "%.1fM".format(bytes / (1024.0 * 1024))
}
