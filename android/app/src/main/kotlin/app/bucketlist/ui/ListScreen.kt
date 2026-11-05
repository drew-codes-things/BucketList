package app.bucketlist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bucketlist.BiometricAuth
import app.bucketlist.BucketViewModel
import app.bucketlist.describeDoneDate
import app.bucketlist.describeProgress
import app.bucketlist.progressFraction
import bucketlist.core.BucketItem

private val CardShape = RoundedCornerShape(16.dp)
private val AddButtonShape = RoundedCornerShape(18.dp)
private val ListPadding = 16.dp
private val SpaceForAddButton = 96.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(viewModel: BucketViewModel, authenticate: BiometricAuth) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BucketItem?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel.message) {
        val text = viewModel.message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        viewModel.message = null
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Bucket List") },
                actions = { ListActions(viewModel, authenticate) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        floatingActionButton = { AddButton(onClick = { adding = true }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val snapshot = viewModel.snapshot
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = ListPadding, end = ListPadding, top = 4.dp, bottom = SpaceForAddButton),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { ProgressHeader(snapshot.done.size, snapshot.items.size) }
            if (snapshot.unreadableCount > 0) {
                item { Text("${snapshot.unreadableCount} item(s) could not be opened with this passkey", color = MaterialTheme.colorScheme.error) }
            }
            if (snapshot.items.isEmpty() && !viewModel.refreshing) {
                item { EmptyState() }
            }
            section("To do", snapshot.toDo, viewModel) { editing = it }
            section("Done", snapshot.done, viewModel) { editing = it }
        }
    }

    if (adding) {
        ItemEditor(
            item = null,
            saving = viewModel.saving,
            onSave = { title, notes -> viewModel.add(title, notes) { adding = false } },
            onDelete = null,
            onDismiss = { adding = false },
        )
    }
    editing?.let { item ->
        ItemEditor(
            item = item,
            saving = viewModel.saving,
            onSave = { title, notes -> viewModel.edit(item, title, notes) { editing = null } },
            onDelete = { viewModel.delete(item) { editing = null } },
            onDismiss = { editing = null },
        )
    }
    BiometricOffer(viewModel, authenticate)
}

private fun LazyListScope.section(label: String, items: List<BucketItem>, viewModel: BucketViewModel, onOpen: (BucketItem) -> Unit) {
    if (items.isEmpty()) return
    item(key = "label-$label") {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = TextDim,
            modifier = Modifier.padding(top = 12.dp, start = 4.dp),
        )
    }
    items(items, key = { it.id }) { item ->
        ItemCard(item, enabled = !viewModel.saving, onToggle = { viewModel.setDone(item, it) }, onOpen = { onOpen(item) })
    }
}

@Composable
private fun ProgressHeader(doneCount: Int, totalCount: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(describeProgress(doneCount, totalCount), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progressFraction(doneCount, totalCount) },
            color = BucketRed,
            trackColor = PanelLine,
            gapSize = 0.dp,
            drawStopIndicator = {},
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        )
    }
}

@Composable
private fun EmptyState() {
    Text(
        "Tap + to add the first thing you both want to do.",
        color = TextDim,
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
    )
}

@Composable
private fun ItemCard(item: BucketItem, enabled: Boolean, onToggle: (Boolean) -> Unit, onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Panel)
            .border(1.dp, PanelLine, CardShape)
            .clickable(onClickLabel = "Edit", role = Role.Button, onClick = onOpen)
            .padding(end = 14.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Checkbox(
            checked = item.isDone,
            onCheckedChange = onToggle,
            enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = BucketRed, uncheckedColor = TextDim, checkmarkColor = Color.White),
        )
        Column(Modifier.weight(1f).padding(top = 12.dp, bottom = 10.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (item.isDone) TextDim else MaterialTheme.colorScheme.onBackground,
                textDecoration = if (item.isDone) TextDecoration.LineThrough else TextDecoration.None,
            )
            if (item.notes.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(item.notes, style = MaterialTheme.typography.bodyMedium, color = TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            item.doneAtMillis?.let {
                Spacer(Modifier.height(4.dp))
                Text(describeDoneDate(it), style = MaterialTheme.typography.labelMedium, color = BucketRedSoft)
            }
        }
    }
}

@Composable
private fun AddButton(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(60.dp)
            .clip(AddButtonShape)
            .background(RedGradient)
            .clickable(onClickLabel = "Add to the list", role = Role.Button, onClick = onClick),
    ) {
        Icon(Icons.Default.Add, contentDescription = "Add to the list", tint = Color.White)
    }
}

@Composable
private fun ListActions(viewModel: BucketViewModel, authenticate: BiometricAuth) {
    var settingsOpen by remember { mutableStateOf(false) }
    if (viewModel.refreshing) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = BucketRed)
        }
    } else {
        IconButton(onClick = { viewModel.refresh() }) { Icon(Icons.Default.Refresh, "Refresh") }
    }
    IconButton(onClick = { viewModel.lock() }) { Icon(Icons.Default.Lock, "Lock") }
    if (viewModel.biometricAvailable()) {
        Box {
            IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Default.MoreVert, "Settings") }
            DropdownMenu(expanded = settingsOpen, onDismissRequest = { settingsOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (viewModel.biometricEnabled) "Turn off fingerprint unlock" else "Turn on fingerprint unlock") },
                    onClick = {
                        settingsOpen = false
                        if (viewModel.biometricEnabled) viewModel.disableBiometric() else enableFingerprint(viewModel, authenticate)
                    },
                )
            }
        }
    }
}

private fun enableFingerprint(viewModel: BucketViewModel, authenticate: BiometricAuth) {
    viewModel.prepareBiometricEnrollment()?.let { cipher -> authenticate(cipher, "Turn on fingerprint unlock") { viewModel.completeBiometricEnrollment(it) } }
}

@Composable
private fun BiometricOffer(viewModel: BucketViewModel, authenticate: BiometricAuth) {
    if (!viewModel.offerBiometric) return
    AlertDialog(
        onDismissRequest = { viewModel.declineBiometric() },
        title = { Text("Unlock with fingerprint?") },
        text = { Text("Next time you can open the list with your fingerprint instead of typing your passkey. Your passkey still works.") },
        confirmButton = { TextButton(onClick = { enableFingerprint(viewModel, authenticate) }) { Text("Turn on") } },
        dismissButton = { TextButton(onClick = { viewModel.declineBiometric() }) { Text("Not now") } },
    )
}
