package com.example.split_table_ai


import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope



import com.google.mlkit.nl.entityextraction.Entity
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractionParams
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import com.opencsv.CSVReader
import com.opencsv.CSVWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class MainViewModel : ViewModel() {

    private val _rawMessages = MutableStateFlow<List<MessageData>>(emptyList())
    val rawMessages: StateFlow<List<MessageData>> = _rawMessages

    private val _processedMessages = MutableStateFlow<List<MessageData>>(emptyList())
    val processedMessages: StateFlow<List<MessageData>> = _processedMessages

    private val _selectedColumns = MutableStateFlow<List<String>>(emptyList())
    val selectedColumns: StateFlow<List<String>> = _selectedColumns

    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting

    private var loadedFileName: String? = null

    private var originalFileUri: Uri? = null

    fun loadCsv(context: Context, uri: Uri) {
        val parsed = parseCsvFile(context, uri)
        _rawMessages.value = parsed
        _processedMessages.value = parsed

        originalFileUri = uri // Save the original URI
    }



    fun getFileNameFromUri(context: Context, uri: Uri): String {
        var name = "exported_data.csv" // fallback name
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    name = it.getString(nameIndex)
                }
            }
        }
        return name
    }


    fun setSelectedColumns(columns: List<String>) {
        _selectedColumns.value = columns
    }

    fun extractEntities(context: Context) {
        if (_rawMessages.value.isEmpty() || _selectedColumns.value.isEmpty()) return

        _isExtracting.value = true

        extractEntitiesFromMessages(context, _rawMessages.value, _selectedColumns.value) { updated ->
            val extended = processDetectedEntitiesAndExtendTable(updated)
            _processedMessages.value = extended
            _isExtracting.value = false
        }
    }

    fun export(context: Context) {


        exportMessagesToCsv(context, _processedMessages.value, originalFileUri)
    }

    fun exportMessagesToCsv(context: Context, messages: List<MessageData>, uri: Uri?) {
        if (messages.isEmpty() || uri == null) {
            Toast.makeText(context, "No export location available.", Toast.LENGTH_SHORT).show()
            return
        }

        val allHeaders = messages.flatMap { it.data.keys }.toSet().toList()

        try {
            val outputStream = context.contentResolver.openOutputStream(uri, "wt") // "wt" = write & truncate
            val writer = OutputStreamWriter(outputStream ?: throw IOException("Unable to open output stream"))
            val csvWriter = CSVWriter(writer)

            csvWriter.writeNext(allHeaders.toTypedArray())

            messages.forEach { message ->
                val row = allHeaders.map { header -> message.data[header] ?: "" }.toTypedArray()
                csvWriter.writeNext(row)
            }

            csvWriter.close()
            Log.d("export", "CSV saved to $uri")
            Toast.makeText(context, "CSV saved successfully", Toast.LENGTH_LONG).show()

        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(context, "Failed to save CSV: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun parseCsvFile(context: Context, uri: Uri): List<MessageData> {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return emptyList()
        val reader = CSVReader(InputStreamReader(inputStream))
        val rows = reader.readAll()

        if (rows.isEmpty()) return emptyList()

        val headers = rows[0].map { it.trim() } // First row as header

        return rows.drop(1).mapNotNull { row ->
            if (row.size != headers.size) return@mapNotNull null

            val rowMap = headers.zip(row).associate { (key, value) ->
                key to value.trim()
            }.toMutableMap()

            MessageData(data = rowMap)
        }
    }




    fun extractEntitiesFromMessages(
        context: Context,
        messages: List<MessageData>,
        selectedColumns: List<String>,
        onComplete: (List<MessageData>) -> Unit
    ) {
        val extractor = EntityExtraction.getClient(
            EntityExtractorOptions.Builder(EntityExtractorOptions.ENGLISH).build()
        )

        extractor.downloadModelIfNeeded().addOnSuccessListener {
            val updatedMessages = mutableListOf<MessageData>()
            var index = 0

            fun next() {
                if (index >= messages.size) {
                    onComplete(updatedMessages)
                    return
                }

                val originalMessage = messages[index]

                // Combine selected columns
                val combinedText = selectedColumns.joinToString(" ") { header ->
                    originalMessage.data[header] ?: ""
                }.replace("-", " ")

                val params = EntityExtractionParams.Builder(combinedText).build()

                extractor.annotate(params)
                    .addOnSuccessListener { annotations ->
                        val entities: List<Pair<String, String>> = annotations.flatMap { annotation ->
                            annotation.entities.mapNotNull { entity ->
                                val text = annotation.annotatedText

                                val type: String? = when (entity.type) {
                                    Entity.TYPE_ADDRESS -> "Address"
                                    Entity.TYPE_DATE_TIME -> {
                                        val resolved = resolveDateTimeText(text)
                                        return@mapNotNull "Date-Time" to resolved
                                    }
                                    Entity.TYPE_EMAIL -> "Email"
                                    Entity.TYPE_PHONE -> {
                                        if (text.replace(" ", "").length < 10 || !isLikelyPhone(text)) return@mapNotNull null
                                        "Phone"
                                    }
                                    Entity.TYPE_PAYMENT_CARD -> "Card"
                                    Entity.TYPE_TRACKING_NUMBER -> "Tracking"
                                    Entity.TYPE_URL -> "URL"
                                    Entity.TYPE_MONEY -> "Money"
                                    Entity.TYPE_FLIGHT_NUMBER -> "Flight"
                                    Entity.TYPE_IBAN -> "IBAN"
                                    Entity.TYPE_ISBN -> "ISBN-13"
                                    else -> null
                                }

                                type?.let { typeName -> typeName to text }
                            }
                        }

                        updatedMessages.add(originalMessage.copy(detectedEntities = entities))
                        index++
                        next()
                    }
                    .addOnFailureListener {
                        updatedMessages.add(originalMessage.copy(detectedEntities = emptyList()))
                        index++
                        next()
                    }
            }

            next()
        }.addOnFailureListener {
            onComplete(messages) // If model download fails
        }
    }

    fun isLikelyPhone(text: String): Boolean {
        val phoneRegex = Regex("""\+?[0-9][0-9\-\(\)\s]{7,}""") // More than 7 digits
        return phoneRegex.matches(text)

    }
    fun resolveDateTimeText(entityText: String): String {
        val lower = entityText.lowercase()
        val today = java.time.LocalDate.now()

        return when {
            "tomorrow" in lower -> today.plusDays(1).format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"))
            "today" in lower -> today.format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"))
            else -> entityText // fallback: keep original if not relative
        }
    }
    fun processDetectedEntitiesAndExtendTable(messages: List<MessageData>): List<MessageData> {
        // Step 1: Collect original columns from first message
        val detectedTypes = mutableMapOf<String, Boolean>()

        messages.forEach { message ->
            message.detectedEntities.forEach { (type, text) ->
                detectedTypes[type] = true
            }
        }

        detectedTypes.forEach{ (type1, _) ->
            messages.forEach { message ->
                message.data[type1] = ""
            }
        }

        messages.forEach { message ->
            message.detectedEntities.forEach { (type, text) ->
                message.data[type] = text
            }
        }



        return messages
    }

    fun deleteSelectedColumns(columnsToDelete: List<String>) {
        _rawMessages.value = _rawMessages.value.map { msg ->
            msg.copy(data = msg.data.filterKeys { it !in columnsToDelete }.toMutableMap())
        }
        _processedMessages.value = _processedMessages.value.map { msg ->
            msg.copy(data = msg.data.filterKeys { it !in columnsToDelete }.toMutableMap())
        }
        _selectedColumns.value = _selectedColumns.value.filter { it !in columnsToDelete }
        _selectedColumns.value = emptyList()
    }




    private val _selectedRowIndices = mutableStateOf<Set<Int>>(emptySet())
    val selectedRowIndices: State<Set<Int>> = _selectedRowIndices

    fun toggleRowSelection(index: Int) {
        _selectedRowIndices.value = _selectedRowIndices.value.toMutableSet().apply {
            if (contains(index)) remove(index) else add(index)
        }
    }

    fun clearRowSelection() {
        _selectedRowIndices.value = emptySet()
    }

    fun deleteSelectedRows() {
        val indices = _selectedRowIndices.value
        _rawMessages.value = _rawMessages.value.filterIndexed { index, _ -> index !in indices }
        _processedMessages.value = _processedMessages.value.filterIndexed { index, _ -> index !in indices }
        clearRowSelection()
    }


    private val _searchMatches = mutableStateOf<List<Pair<Int, String>>>(emptyList())
    val searchMatches: State<List<Pair<Int, String>>> = _searchMatches

    private val _currentMatchIndex = mutableStateOf(0)
    val currentMatchIndex: State<Int> = _currentMatchIndex

    private var currentSearchText: String = ""

    fun search(query: String) {
        currentSearchText = query.trim()

        val matches = _processedMessages.value.mapIndexedNotNull { rowIndex, message ->
            val matchingCols = _selectedColumns.value.filter { columnKey ->
                message.data[columnKey]?.contains(currentSearchText, ignoreCase = true) == true
            }.map { columnKey -> rowIndex to columnKey }

            if (matchingCols.isNotEmpty()) matchingCols else null
        }.flatten()

        _searchMatches.value = matches
        _currentMatchIndex.value = 0.coerceAtMost(matches.lastIndex)
    }

    fun nextMatch() {
        val next = (_currentMatchIndex.value + 1) % _searchMatches.value.size
        _currentMatchIndex.value = next
    }

    fun previousMatch() {
        val prev = (_currentMatchIndex.value - 1 + _searchMatches.value.size) % _searchMatches.value.size
        _currentMatchIndex.value = prev
    }

    val currentMatch: Pair<Int, String>?
        get() = _searchMatches.value.getOrNull(_currentMatchIndex.value)


    fun replaceCurrentMatch(replacement: String) {
        if (replacement.isEmpty()) return  // ✅ Skip empty replacements

        val match = currentMatch ?: return
        val (rowIndex, columnKey) = match

        val currentList = _processedMessages.value.toMutableList()
        val row = currentList.getOrNull(rowIndex) ?: return

        val newData = row.data.toMutableMap()

        val oldValue = newData[columnKey]
        if (oldValue is String && oldValue.contains(currentSearchText, ignoreCase = true)) {
            val updatedValue = oldValue.replace(currentSearchText, replacement, ignoreCase = true)
            newData[columnKey] = updatedValue
            currentList[rowIndex] = row.copy(data = newData)
            _processedMessages.value = currentList

            // Re-run search to update highlights
            search(currentSearchText)
        }
    }

    fun clearSearch() {
        _searchMatches.value = emptyList()
        _currentMatchIndex.value = 0
        currentSearchText = ""
    }



}
