package com.example.split_table_ai



import android.content.Context

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle


import com.google.mlkit.nl.entityextraction.Entity
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractionParams
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import com.opencsv.CSVReader
import com.opencsv.CSVWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MessageData(
    val data: MutableMap<String, String> = mutableMapOf(),
    val detectedEntities: List<Pair<String, String>> = emptyList()
)

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                val context = LocalContext.current


                val processedMessages by viewModel.processedMessages.collectAsStateWithLifecycle()
                val selectedColumns by viewModel.selectedColumns.collectAsStateWithLifecycle()
                val selectedRows by viewModel.selectedRowIndices
                val currentMatchIndex by viewModel.currentMatchIndex
                val searchMatches by viewModel.searchMatches
                val currentMatch = viewModel.currentMatch

                var searchText by remember { mutableStateOf("") }

                var showSearchDrawer by remember { mutableStateOf(false) }

                val listState = rememberLazyListState()

                val csvPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                    onResult = { uri ->
                        uri?.let { viewModel.loadCsv(context, it) }
                    }
                )
                var isRowSelectMode by remember { mutableStateOf(false) }
                Scaffold(
                    topBar = {
                        AppTopBarMenu(
                            onAddFileClick = {
                                csvPicker.launch(
                                    arrayOf(
                                        "text/csv",
                                        "text/comma-separated-values",
                                        "application/vnd.ms-excel",
                                        "text/plain"
                                    )
                                )
                            },
                            onExtractClick = { viewModel.extractEntities(context) },
                            onSaveClick = { viewModel.export(context) },
                            onDeleteRowsClick = { viewModel.deleteSelectedRows() },
                            onDeleteColumnsClick = { viewModel.deleteSelectedColumns(selectedColumns) },
                            onSearchClick = { showSearchDrawer = true },
                            isDeleteRowEnabled = selectedRows.isNotEmpty(),
                            isDeleteColumnEnabled = selectedColumns.isNotEmpty(),
                            isRowSelectMode = isRowSelectMode,
                            onToggleRowSelectMode = { isRowSelectMode = !isRowSelectMode }

                        )
                    }
                ) { padding ->
                    Box(modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                    ) {

                        if (processedMessages.isNotEmpty()) {
                            EntityExtractTable(
                                messages = processedMessages,
                                selectedIndices = selectedRows,
                                onRowToggle = { rowIndex -> viewModel.toggleRowSelection(rowIndex) },
                                onExtractClicked = { columns -> viewModel.setSelectedColumns(columns) },
                                searchMatches = searchMatches,
                                currentMatch = currentMatch,
                                listState = listState,
                                rowSelectionMode = isRowSelectMode
                            )
                        }

                        if (showSearchDrawer) {
                            Surface(
                                tonalElevation = 4.dp,
                                shadowElevation = 8.dp,
                                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                            ) {
                                var replaceText by remember { mutableStateOf("") }

                                Column(
                                    modifier = Modifier
                                        .verticalScroll(rememberScrollState())
                                        .imePadding()
                                        .padding(16.dp)
                                ) {

                                    OutlinedTextField(
                                        value = searchText,
                                        onValueChange = {
                                            searchText = it
                                            viewModel.search(it)
                                        },
                                        label = { Text("Search") },
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Spacer(Modifier.height(16.dp))


                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = { viewModel.previousMatch() },
                                            enabled = searchMatches.isNotEmpty()
                                        ) {
                                            Icon(Icons.Default.ArrowBack, contentDescription = "Previous")
                                        }

                                        IconButton(
                                            onClick = { viewModel.nextMatch() },
                                            enabled = searchMatches.isNotEmpty()
                                        ) {
                                            Icon(Icons.Default.ArrowForward, contentDescription = "Next")
                                        }

                                        if (searchMatches.isNotEmpty()) {
                                            Text("${currentMatchIndex + 1} / ${searchMatches.size}", modifier = Modifier.padding(start = 8.dp))
                                        }
                                    }


                                    OutlinedTextField(
                                        value = replaceText,
                                        onValueChange = { replaceText = it },
                                        label = { Text("Replace With") },
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Spacer(Modifier.height(8.dp))

                                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                        Button(
                                            onClick = {
                                                viewModel.replaceCurrentMatch(replaceText)
                                            },
                                            enabled = searchMatches.isNotEmpty() && replaceText.isNotBlank()
                                        ) {
                                            Text("Replace")
                                        }

                                        Spacer(Modifier.width(8.dp))

                                        Button(
                                            onClick = {
                                                viewModel.clearSearch()
                                                searchText = ""
                                                replaceText = ""
                                                showSearchDrawer = false
                                            }
                                        ) {
                                            Text("Done")
                                        }
                                    }
                                }
                            }

                        }
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBarMenu(
    onAddFileClick: () -> Unit,
    onExtractClick: () -> Unit,
    onSaveClick: () -> Unit,
    onDeleteRowsClick: () -> Unit,
    onDeleteColumnsClick: () -> Unit,
    onSearchClick: () -> Unit,
    isDeleteRowEnabled: Boolean,
    isDeleteColumnEnabled: Boolean,
    isRowSelectMode: Boolean,
    onToggleRowSelectMode: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text("CSV Data Extractor") },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary
        ),
        actions = {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Add File") },
                        onClick = {
                            menuExpanded = false
                            onAddFileClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Select Rows") },
                        onClick = {
                            menuExpanded = false
                            onToggleRowSelectMode()
                        },
                        leadingIcon = {
                            Checkbox(
                                checked = isRowSelectMode,
                                onCheckedChange = null
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Extract Information") },
                        onClick = {
                            menuExpanded = false
                            onExtractClick()
                        },
                        enabled = !isRowSelectMode
                    )
                    DropdownMenuItem(
                        text = { Text("Save File") },
                        onClick = {
                            menuExpanded = false
                            onSaveClick()
                        },
                        enabled = !isRowSelectMode
                    )
                    DropdownMenuItem(
                        text = { Text("Delete Selected Rows") },
                        enabled = isDeleteRowEnabled ,
                        onClick = {
                            menuExpanded = false
                            onDeleteRowsClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete Selected Columns") },
                        enabled = isDeleteColumnEnabled && !isRowSelectMode,
                        onClick = {
                            menuExpanded = false
                            onDeleteColumnsClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Search") },
                        onClick = {
                            menuExpanded = false
                            onSearchClick()
                        },
                        enabled = !isRowSelectMode
                    )
                }
            }
        }
    )
}




@Composable
fun EntityExtractTable(
    messages: List<MessageData>,
    selectedIndices: Set<Int>,
    onRowToggle: (Int) -> Unit,
    onExtractClicked: (selectedData: List<String>) -> Unit,
    searchMatches: List<Pair<Int, String>> = emptyList(),
    currentMatch: Pair<Int, String>? = null,
    listState: LazyListState,
    rowSelectionMode: Boolean
) {
    LaunchedEffect(currentMatch) {
        currentMatch?.let { (rowIndex, _) ->
            listState.animateScrollToItem(rowIndex)
        }
    }

    if (messages.isEmpty()) return

    val headers = messages.first().data.keys.toList()
    val columnChecked = remember { mutableStateMapOf<String, Boolean>() }
    val columnWidths = remember { mutableStateMapOf<String, MutableState<Dp>>() }
    val expandedCell = remember { mutableStateOf<Pair<Int, String>?>(null) }
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(headers) {
        headers.forEach { header ->
            if (columnChecked[header] == null) columnChecked[header] = false
            if (columnWidths[header] == null) columnWidths[header] = mutableStateOf(140.dp)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .verticalScroll(verticalScroll)
                .horizontalScroll(horizontalScroll)
                .padding(8.dp)
        ) {
            // Header Row
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rowSelectionMode) {
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(56.dp)
                            .border(1.dp, Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Sel", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                headers.forEach { header ->
                    val width = columnWidths[header]?.value ?: 140.dp
                    Box(
                        modifier = Modifier
                            .width(width)
                            .height(56.dp)
                            .border(1.dp, Color.Black)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 4.dp)
                            ) {
                                Checkbox(
                                    checked = columnChecked[header] ?: false,
                                    onCheckedChange = {
                                        columnChecked[header] = it
                                        onExtractClicked(
                                            columnChecked.filter { it.value }.map { it.key }
                                        )
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = header,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .width(8.dp)
                                    .fillMaxHeight()
                                    .pointerInput(header) {
                                        detectHorizontalDragGestures { _, dragAmount ->
                                            val current = columnWidths[header]?.value ?: 140.dp
                                            val newWidth = (current + dragAmount.dp).coerceIn(80.dp, 1200.dp)
                                            columnWidths[header]?.value = newWidth
                                        }
                                    }
                                    .background(Color.Gray)
                            )
                        }
                    }
                }
            }

            // Data Rows
            messages.forEachIndexed { rowIndex, message ->
                val isSelected = selectedIndices.contains(rowIndex)
                val rowBgColor = if (isSelected) Color(0xFFBBDEFB) else Color.Transparent

                Row(
                    modifier = Modifier
                        .background(rowBgColor)
                        .padding(vertical = 1.dp)
                        .fillMaxWidth(),

                ) {
                    if (rowSelectionMode) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(56.dp)
                                .border(1.dp, Color.LightGray),
                            contentAlignment = Alignment.Center
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = {
                                    focusManager.clearFocus(force = true)
                                    onRowToggle(rowIndex)
                                },
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    headers.forEach { header ->
                        val width = columnWidths[header]?.value ?: 140.dp
                        val value = message.data[header] ?: ""

                        val isMatch = searchMatches.contains(rowIndex to header)
                        val isCurrent = currentMatch == (rowIndex to header)
                        val isExpanded = expandedCell.value == rowIndex to header

                        val cellBg = when {
                            isCurrent -> Color.Yellow.copy(alpha = 0.4f)
                            isMatch -> Color.Cyan.copy(alpha = 0.2f)
                            else -> Color.Transparent
                        }

                        val cellModifier = Modifier
                            .width(width)
                            .border(1.dp, Color.LightGray)
                            .background(cellBg)
                            .clickable {
                                focusManager.clearFocus(force = true)
                                expandedCell.value =
                                    if (isExpanded) null else rowIndex to header
                            }
                            .padding(8.dp)

                        Box(
                            modifier = if (isExpanded) {
                                cellModifier.wrapContentHeight()
                            } else {
                                cellModifier.height(56.dp)
                            },
                            contentAlignment = Alignment.TopStart
                        ) {
                            SelectionContainer {
                                Text(
                                    text = value,
                                    style = LocalTextStyle.current.copy(fontSize = 12.sp),
                                    maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                                    overflow = if (isExpanded) TextOverflow.Clip else TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}





