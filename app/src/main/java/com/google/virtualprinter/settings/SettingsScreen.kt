/*
 * Copyright 2025 The Virtual Printer Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.virtualprinter.settings

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import com.google.virtualprinter.printer.PrinterService
import com.google.virtualprinter.utils.FileUtils
import com.google.virtualprinter.utils.IppAttributesUtils
import com.google.virtualprinter.utils.PreferenceUtils
import com.google.virtualprinter.utils.PrinterDiscoveryUtils
import com.hp.jipp.encoding.AttributeGroup
import com.hp.jipp.encoding.Tag
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    printerService: PrinterService,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var printJobs by remember { mutableStateOf(FileUtils.getSavedPrintJobs(context).size) }
    var printerName by remember { mutableStateOf(PreferenceUtils.getCustomPrinterName(context)) }
    var isEditingName by remember { mutableStateOf(false) }
    var tempPrinterName by remember { mutableStateOf(printerName) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showDiscoveryDialog by remember { mutableStateOf(false) }
    // Load saved selection from SharedPreferences
    val sharedPrefs = context.getSharedPreferences("printer_settings", Context.MODE_PRIVATE)
    var selectedAttributesFile by remember { 
        mutableStateOf<String?>(sharedPrefs.getString("selected_attributes_file", null))
    }
    var availableAttributeFiles by remember { mutableStateOf(IppAttributesUtils.getAvailableIppAttributeFiles(context)) }
    
    // Service status tracking with reactive updates
    var serviceStatus by remember { mutableStateOf(printerService.getServiceStatus()) }
    
    // Load saved custom attributes on startup
    LaunchedEffect(selectedAttributesFile) {
        selectedAttributesFile?.let { filename ->
            Log.d("SettingsScreen", "Loading saved custom attributes: $filename")
            IppAttributesUtils.loadIppAttributes(context, filename)?.let { attributes ->
                printerService.setCustomIppAttributes(attributes)
                Log.d("SettingsScreen", "Restored custom attributes: ${attributes.size} groups")
            }
        }
    }
    
    // Smart polling for service status updates
    LaunchedEffect(Unit) {
        var stableCount = 0
        
        while (coroutineContext.isActive) {
            val currentStatus = printerService.getServiceStatus()
            if (currentStatus != serviceStatus) {
                serviceStatus = currentStatus
                stableCount = 0 // Reset stability counter on change
            } else {
                stableCount++
            }
            
            // Dynamic polling: fast when changing, slow when stable
            val pollInterval = when {
                stableCount < 3 -> 500L  // 0.5s for first few stable reads
                stableCount < 10 -> 2000L // 2s for moderately stable
                else -> 5000L             // 5s for very stable
            }
            
            delay(pollInterval)
        }
    }
    
    // Printer discovery states
    var isDiscovering by remember { mutableStateOf(false) }
    var discoveredPrinters by remember { mutableStateOf<List<PrinterDiscoveryUtils.NetworkPrinter>>(emptyList()) }
    var selectedPrinter by remember { mutableStateOf<PrinterDiscoveryUtils.NetworkPrinter?>(null) }
    var showPrinterQueryingDialog by remember { mutableStateOf(false) }
    var showPrinterSaveDialog by remember { mutableStateOf(false) }
    
    // File picker launcher for importing attributes
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            handleImportAttributes(
                context = context,
                uri = selectedUri,
                printerService = printerService
            ) { savedFilename ->
                // refresh state so the new file appears and is selected
                availableAttributeFiles = IppAttributesUtils.getAvailableIppAttributeFiles(context)
                selectedAttributesFile = savedFilename
            }
        }
    }
    
    // File picker launcher for exporting attributes
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { handleExportAttributes(context, it, printerService) }
    }
    var useDefaultAttributes by remember { mutableStateOf(false) }
    val createFileLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri: Uri? ->
            if (uri != null) {
                coroutineScope.launch {
                    selectedPrinter?.let { printer ->
                        val attributes = if (useDefaultAttributes) {
                            PrinterDiscoveryUtils.queryPrinterWithAlternatives(printer)
                        } else {
                            PrinterDiscoveryUtils.queryPrinterAttributes(printer)
                                ?: PrinterDiscoveryUtils.createMinimalAttributes(printer)
                        }

                        if (attributes != null) {
                            val success =
                                PrinterDiscoveryUtils.exportPrinterAttributesToFile(
                                    context,
                                    attributes,
                                    uri
                                )

                            android.widget.Toast.makeText(
                                context,
                                if (success)
                                    "Printer attributes saved successfully"
                                else
                                    "Failed to save printer attributes",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    showPrinterSaveDialog = false
                }
            }
        }
    
    // Refresh job count when settings screen is shown
    LaunchedEffect(Unit) {
        printJobs = FileUtils.getSavedPrintJobs(context).size
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                // Printer Information Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Printer Information",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        if (isEditingName) {
                            OutlinedTextField(
                                value = tempPrinterName,
                                onValueChange = { tempPrinterName = it },
                                label = { Text("Printer Name") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = {
                                        if (tempPrinterName.isNotBlank()) {
                                            printerName = tempPrinterName
                                            PreferenceUtils.saveCustomPrinterName(context, tempPrinterName)
                                            isEditingName = false
                                        }
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Save"
                                        )
                                    }
                                }
                            )
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Name: $printerName")
                                TextButton(onClick = {
                                    tempPrinterName = printerName
                                    isEditingName = true
                                }) {
                                    Text("Edit")
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        // Dynamic status display with color indicator
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val statusColor = when (serviceStatus) {
                                PrinterService.ServiceStatus.RUNNING -> Color.Green
                                PrinterService.ServiceStatus.ERROR_SIMULATION -> Color.Yellow
                                PrinterService.ServiceStatus.STARTING -> Color.Blue
                                PrinterService.ServiceStatus.STOPPED -> Color.Red
                            }
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(statusColor, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Status: ${serviceStatus.displayName}")
                        } 
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Port: ${printerService.getPort()}")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Saved print jobs: $printJobs")
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Note: Printer name changes will take effect after restarting the app",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            
            item {
                // IPP Attributes Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "IPP Attributes",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Current attributes file selection
                        if (availableAttributeFiles.isNotEmpty()) {
                            Text("Current Attributes File:", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            availableAttributeFiles.forEach { filename ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        RadioButton(
                                            selected = filename == selectedAttributesFile,
                                            onClick = {
                                                selectedAttributesFile = filename
                                                // Save selection to SharedPreferences
                                                sharedPrefs.edit().putString("selected_attributes_file", filename).apply()
                                                Log.d("SettingsScreen", "Selected custom attributes file: $filename")
                                                
                                                IppAttributesUtils.loadIppAttributes(context, filename)?.let { attributes ->
                                                    printerService.setCustomIppAttributes(attributes)
                                                    Log.d("SettingsScreen", "Applied custom attributes: ${attributes.size} groups")
                                                }
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(filename)
                                    }
                                    
                                    IconButton(
                                        onClick = {
                                            IppAttributesUtils.deleteIppAttributes(context, filename)
                                            availableAttributeFiles = IppAttributesUtils.getAvailableIppAttributeFiles(context)
                                            if (selectedAttributesFile == filename) {
                                                selectedAttributesFile = null
                                                // Clear selection from SharedPreferences
                                                sharedPrefs.edit().remove("selected_attributes_file").apply()
                                                printerService.setCustomIppAttributes(null)
                                                Log.d("SettingsScreen", "Cleared custom attributes selection")
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete"
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                "No custom IPP attributes configured",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        
                        // Add verification section
                        if (selectedAttributesFile != null) {
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    Text(
                                        text = "Custom Attributes Verification",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    
                                    Spacer(modifier = Modifier.height(8.dp))
                                    
                                    Text(
                                        text = "Active file: $selectedAttributesFile",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    
                                    Spacer(modifier = Modifier.height(12.dp))
                                    
                                    Button(
                                        onClick = {
                                            // Test custom attributes by simulating a Get-Printer-Attributes request
                                            val currentAttributes = printerService.getCustomIppAttributes()
                                            if (currentAttributes != null) {
                                                Log.d("SettingsScreen", "=== CUSTOM ATTRIBUTES VERIFICATION ===")
                                                Log.d("SettingsScreen", "Active custom attributes: ${currentAttributes.size} groups")
                                                
                                                currentAttributes.forEachIndexed { index, group ->
                                                    Log.d("SettingsScreen", "Group $index: ${group.tag}")
                                                    try {
                                                        val iterator = group.iterator()
                                                        var attrCount = 0
                                                        while (iterator.hasNext()) {
                                                            val attr = iterator.next()
                                                            Log.d("SettingsScreen", "  ${attr.name} = ${attr.getValue()}")
                                                            attrCount++
                                                        }
                                                        Log.d("SettingsScreen", "  Total attributes in group: $attrCount")
                                                    } catch (e: Exception) {
                                                        Log.e("SettingsScreen", "Error reading group $index", e)
                                                    }
                                                }
                                                Log.d("SettingsScreen", "=== END VERIFICATION ===")
                                                
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Custom attributes are active! Check logs for details.",
                                                    android.widget.Toast.LENGTH_LONG
                                                ).show()
                                            } else {
                                                Log.w("SettingsScreen", "No custom attributes currently loaded")
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "No custom attributes currently active!",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Verify Custom Attributes")
                                    }
                                    
                                    Spacer(modifier = Modifier.height(8.dp))
                                    
                                    Text(
                                        text = "This will log all active custom attributes to help verify they are being used in IPP responses.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Import/Export/Discover buttons
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Import/Export row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { showImportDialog = true },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Send,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Text("Import")
                                }
                                
                                Button(
                                    onClick = { showExportDialog = true },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Text("Export")
                                }
                            }
                            
                            // Discover printers button
                            Button(
                                onClick = { showDiscoveryDialog = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Text("Discover Network Printers")
                            }
                        }
                    }
                }
            }
            
            item {
                // Storage Management Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Storage Management",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Button(
                            onClick = { showDeleteAllDialog = true },
                            enabled = printJobs > 0,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Clear All Print Jobs")
                        }
                    }
                }
            }
            
            item {
                // Error Simulation Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Error Simulation",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        var isSimulatingError by remember { 
                            val status = printerService.getErrorSimulationStatus()
                            mutableStateOf(status.first) 
                        }
                        var selectedErrorType by remember { 
                            val status = printerService.getErrorSimulationStatus()
                            mutableStateOf(status.second) 
                        }
                        
                        // Toggle switch for error simulation
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Simulate errors",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = isSimulatingError,
                                onCheckedChange = { checked ->
                                    isSimulatingError = checked
                                    printerService.configureErrorSimulation(checked, selectedErrorType)
                                }
                            )
                        }
                        
                        if (isSimulatingError) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Error type",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Error type radio buttons
                            Column {
                                val errorTypes = listOf(
                                    "server-error" to "Server Error",
                                    "client-error" to "Client Error",
                                    "aborted" to "Aborted Job",
                                    "unsupported-format" to "Unsupported Format"
                                )
                                
                                errorTypes.forEach { (type, label) ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                    ) {
                                        RadioButton(
                                            selected = selectedErrorType == type,
                                            onClick = {
                                                selectedErrorType = type
                                                printerService.configureErrorSimulation(isSimulatingError, type)
                                            }
                                        )
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Note: Error simulation will affect all incoming print jobs",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
            
            item {
                // About Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "About",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text("Android Virtual Printer")
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Version 1.0")
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("A virtual printer application that captures print jobs as PDF files")
                    }
                }
            }
            
            // Add extra space at the bottom to ensure all content is scrollable
            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
    
    // Dialogs
    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text("Delete All Print Jobs") },
            text = { Text("Are you sure you want to delete all print jobs? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        FileUtils.deleteAllPrintJobs(context)
                        printJobs = 0
                        showDeleteAllDialog = false
                    }
                ) {
                    Text("Delete All")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteAllDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import IPP Attributes") },
            text = { Text("Select a JSON file containing IPP attributes to import.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportDialog = false
                        importLauncher.launch("application/json")
                    }
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showImportDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export IPP Attributes") },
            text = { Text("Save the current IPP attributes to a JSON file.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportDialog = false
                        exportLauncher.launch("ipp_attributes.json")
                    }
                ) {
                    Text("Export")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showExportDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Printer Discovery Dialog
    if (showDiscoveryDialog) {
        var showTestConnectivity by remember { mutableStateOf(false) }
        var connectivityResult by remember { mutableStateOf("") }
        var isTestingConnectivity by remember { mutableStateOf(false) }
        
        AlertDialog(
            onDismissRequest = { 
                if (!isDiscovering && !isTestingConnectivity) showDiscoveryDialog = false 
            },
            title = { Text("Discover Network Printers") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isDiscovering) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Scanning for printers on your network...")
                    } else if (isTestingConnectivity) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Testing connectivity to ${selectedPrinter?.name}...")
                    } else {
                        if (discoveredPrinters.isEmpty()) {
                            Text("No printers found. Try scanning again or make sure your printers are powered on and connected to the network.")
                        } else {
                            Text("Select a printer to query its attributes:")
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // List of discovered printers
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                discoveredPrinters.forEach { printer ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = selectedPrinter == printer,
                                            onClick = { 
                                                selectedPrinter = printer
                                                // Reset connectivity test result when printer selection changes
                                                showTestConnectivity = false
                                                connectivityResult = ""
                                            }
                                        )
                                        Column(
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(printer.name)
                                            Text(
                                                "${printer.address.hostAddress}:${printer.port}",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }
                            }
                            
                            // Show test connectivity section if a printer is selected
                            if (selectedPrinter != null) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Test connectivity")
                                    Button(
                                        onClick = {
                                            isTestingConnectivity = true
                                            coroutineScope.launch {
                                                val isAvailable = selectedPrinter?.let {
                                                    PrinterDiscoveryUtils.isPrinterReachable(it)
                                                } ?: false
                                                connectivityResult =
                                                    if (isAvailable) "Printer is available"
                                                    else "Printer is not reachable"
                                                showTestConnectivity = true
                                                isTestingConnectivity = false
                                            }
                                        },
                                        enabled = !isTestingConnectivity
                                    ) {
                                        Text("Test")
                                    }
                                }
                                
                                if (showTestConnectivity && connectivityResult.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (connectivityResult.startsWith("Connected successfully")) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.errorContainer
                                            }
                                        )
                                    ) {
                                        Text(
                                            text = connectivityResult,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (isDiscovering || isTestingConnectivity) {
                    TextButton(onClick = { /* Do nothing, operation in progress */ }) {
                        Text(if (isDiscovering) "Scanning..." else "Testing...")
                    }
                } else if (discoveredPrinters.isNotEmpty() && selectedPrinter != null) {
                    TextButton(
                        onClick = {
                            showDiscoveryDialog = false
                            showPrinterQueryingDialog = true
                            
                            coroutineScope.launch {
                                // Use improved query method with alternatives
                                val attributes = selectedPrinter?.let { printer ->
                                    PrinterDiscoveryUtils.queryPrinterWithAlternatives(printer)
                                }
                                
                                showPrinterQueryingDialog = false
                                
                                if (attributes != null && attributes.isNotEmpty()) {
                                    showPrinterSaveDialog = true
                                } else {
                                    // Show improved error message
                                    android.widget.Toast.makeText(
                                        context,
                                        "Failed to query printer attributes. Check if the printer supports IPP.",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    ) {
                        Text("Query Printer")
                    }
                } else {
                    TextButton(
                        onClick = {
                            isDiscovering = true
                            discoveredPrinters = emptyList()
                            selectedPrinter = null
                            connectivityResult = ""
                            showTestConnectivity = false
                            
                            coroutineScope.launch {
                                val printers = PrinterDiscoveryUtils.discoverPrinters(context)
                                discoveredPrinters = printers
                                isDiscovering = false
                            }
                        }
                    ) {
                        Text("Scan for Printers")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { 
                        if (!isDiscovering && !isTestingConnectivity) {
                            showDiscoveryDialog = false 
                        }
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Printer Querying Dialog
    if (showPrinterQueryingDialog) {
        AlertDialog(
            onDismissRequest = { /* Non-dismissible */ },
            title = { Text("Querying Printer") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text("Querying printer attributes from ${selectedPrinter?.name}...")
                    Text(
                        "This may take a moment. Trying multiple methods to connect...",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = { }
        )
    }
    
    // Save Printer Attributes Dialog
    if (showPrinterSaveDialog) {
        var saveFilename by remember { mutableStateOf("printer_${selectedPrinter?.name?.replace(" ", "_")?.lowercase() ?: "unknown"}_${System.currentTimeMillis()}.json") }
        AlertDialog(
            onDismissRequest = { showPrinterSaveDialog = false },
            title = { Text("Save Printer Attributes") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Attributes successfully queried from ${selectedPrinter?.name}.")
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = saveFilename,
                        onValueChange = { saveFilename = it },
                        label = { Text("Filename") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        readOnly = true
                    )
                    
                    // Option to use default attributes if actual query failed
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = useDefaultAttributes,
                            onCheckedChange = { useDefaultAttributes = it }
                        )
                        Text(
                            text = "Use generic attributes if printer query failed",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        createFileLauncher.launch(saveFilename)
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showPrinterSaveDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun handleImportAttributes(
    context: android.content.Context,
    uri: Uri,
    printerService: PrinterService,
    onSaved: (String) -> Unit
) {
    try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val jsonString = inputStream.bufferedReader().use { it.readText() }

            // Create a temporary file and use IppAttributesUtils to handle both array and object formats
            val tempFilename = "temp_import_${UUID.randomUUID()}.json"
            val attributesDir = File(context.filesDir, "ipp_attributes")
            if (!attributesDir.exists()) {
                attributesDir.mkdirs()
            }

            val tempFile = File(attributesDir, tempFilename)
            var attributes: List<AttributeGroup>? = null

            try {
                // Write temp file and use loadIppAttributes which handles multiple formats
                tempFile.writeText(jsonString)
                attributes = IppAttributesUtils.loadIppAttributes(context, tempFilename)
                Log.d("SettingsScreen", "Loaded ${attributes?.size ?: 0} attribute groups from imported file")
            } finally {
                // Clean up temp file
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }

            if (attributes != null && attributes.isNotEmpty()) {
                // Perform detailed validation
                val isValid = IppAttributesUtils.validateIppAttributes(attributes)
                Log.d("SettingsScreen", "Attribute validation result: $isValid")

                if (isValid) {
                    val filename = "ipp_attributes_${System.currentTimeMillis()}.json"
                    Log.d("SettingsScreen", "Attempting to save attributes to: $filename")

                    if (IppAttributesUtils.saveIppAttributes(context, attributes, filename)) {
                        // Test if attributes can be loaded back successfully
                        val reloadedAttributes = IppAttributesUtils.loadIppAttributes(context, filename)
                        if (reloadedAttributes != null && reloadedAttributes.size == attributes.size) {
                            printerService.setCustomIppAttributes(attributes)
                            onSaved(filename)
                            Log.d("SettingsScreen", "Successfully imported and verified custom attributes")
                            android.widget.Toast.makeText(
                                context,
                                "IPP attributes imported and verified successfully (${attributes.size} groups)",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Log.e("SettingsScreen", "Attributes saved but failed verification reload")
                            android.widget.Toast.makeText(
                                context,
                                "Attributes saved but failed verification. Check logs.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        Log.e("SettingsScreen", "Failed to save IPP attributes to file")
                        android.widget.Toast.makeText(
                            context,
                            "Failed to save IPP attributes to storage",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    Log.w("SettingsScreen", "Attribute validation failed")
                    android.widget.Toast.makeText(
                        context,
                        "Invalid attributes format - validation failed. Check logs for details.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                val message = when {
                    attributes == null -> {
                        Log.e("SettingsScreen", "Failed to parse attributes file - null result")
                        "Failed to parse attributes file. Check JSON format and try again."
                    }
                    attributes.isEmpty() -> {
                        Log.w("SettingsScreen", "No valid attributes found in file")
                        "No valid attributes found in file. Ensure proper IPP attribute structure."
                    }
                    else -> {
                        Log.e("SettingsScreen", "Unknown validation error")
                        "Unknown error occurred during import."
                    }
                }
                android.widget.Toast.makeText(
                    context,
                    message,
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("SettingsScreen", "Error importing IPP attributes", e)
        android.widget.Toast.makeText(
            context,
            "Error importing IPP attributes: ${e.message}",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

private fun handleExportAttributes(context: android.content.Context, uri: Uri, printerService: PrinterService) {
    try {
        val attributes = printerService.getCustomIppAttributes()
        if (attributes != null) {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                val jsonArray = org.json.JSONArray()
                
                attributes.forEach { group ->
                    val groupObj = org.json.JSONObject().apply {
                        put("tag", group.tag.name)
                        put("attributes", org.json.JSONArray().apply {
                            IppAttributesUtils.getAttributesFromGroup(group).forEach { attr ->
                                put(org.json.JSONObject().apply {
                                    put("name", attr.name)
                                    put("value", attr.toString())
                                    put("type", "STRING") // Default type
                                })
                            }
                        })
                    }
                    jsonArray.put(groupObj)
                }
                
                outputStream.write(jsonArray.toString().toByteArray())
                android.widget.Toast.makeText(
                    context,
                    "IPP attributes exported successfully",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            android.widget.Toast.makeText(
                context,
                "No IPP attributes to export",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    } catch (e: Exception) {
        android.util.Log.e("SettingsScreen", "Error exporting IPP attributes", e)
        android.widget.Toast.makeText(
            context,
            "Error exporting IPP attributes: ${e.message}",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
} 