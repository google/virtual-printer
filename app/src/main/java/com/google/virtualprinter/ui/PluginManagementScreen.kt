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

package com.google.virtualprinter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.virtualprinter.plugins.PluginFramework
import com.google.virtualprinter.plugins.PluginMetadata
import com.google.virtualprinter.plugins.ConfigurationField as PluginConfigField
import com.google.virtualprinter.plugins.FieldType
import com.google.virtualprinter.logging.LogCategory
import com.google.virtualprinter.logging.PrinterLogger
import com.google.virtualprinter.ui.ConfigurationField
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginManagementScreen(
    onBackClick: () -> Unit,
    printerService: com.google.virtualprinter.printer.PrinterService? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Get instances
    val pluginFramework = remember { PluginFramework.getInstance(context) }
    val logger = remember { PrinterLogger.getInstance(context) }
    
    // State
    val availablePlugins by pluginFramework.availablePlugins.collectAsStateWithLifecycle()
    val loadedPluginIds by pluginFramework.loadedPluginIds.collectAsStateWithLifecycle()
    
    var selectedPlugin by remember { mutableStateOf<PluginMetadata?>(null) }
    var showPluginDetails by remember { mutableStateOf(false) }
    var showConfigDialog by remember { mutableStateOf(false) }
    var pluginToConfig by remember { mutableStateOf<PluginMetadata?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Plugin Management")
                        Text(
                            text = "${loadedPluginIds.size} of ${availablePlugins.size} loaded",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            // Reload plugin state
                            coroutineScope.launch {
                                pluginFramework.refreshState()
                                refreshKey++
                                logger.i(
                                    LogCategory.SYSTEM,
                                    "PluginManagementScreen",
                                    "Manually refreshed plugin state (key=$refreshKey)"
                                )
                                android.widget.Toast.makeText(
                                    context,
                                    "Plugin state refreshed",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    ) {
                        Icon(Icons.Default.Refresh, "Reload")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Plugin Overview Card
            item {
                PluginOverviewCard(
                    totalPlugins = availablePlugins.size,
                    loadedPlugins = loadedPluginIds.size,
                    enabledPlugins = availablePlugins.count { it.enabled }
                )
            }
            
            // Loaded Plugins Section
            if (printerService != null && loadedPluginIds.isNotEmpty()) {
                item {
                    Text(
                        text = "Active Plugins",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                
                items(availablePlugins.filter { it.enabled && loadedPluginIds.contains(it.id) }) { plugin ->
                    LoadedPluginCard(
                        plugin = plugin,
                        printerService = printerService,
                        pluginFramework = pluginFramework,
                        refreshKey = refreshKey,
                        onUnload = { pluginId ->
                            coroutineScope.launch {
                                val success = printerService.unloadPlugin(pluginId)
                                android.widget.Toast.makeText(
                                    context,
                                    if (success) "Plugin unloaded: ${plugin.name}" else "Failed to unload plugin",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onConfigure = { pluginId ->
                            availablePlugins.find { it.id == pluginId }?.let { p ->
                                pluginToConfig = p
                                showConfigDialog = true
                            }
                        }
                    )
                }
            }
            
            // Available Plugins Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Available Plugins",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = if (loadedPluginIds.isNotEmpty()) 16.dp else 0.dp)
                    )
                    
                    // Bulk actions could go here
                    IconButton(onClick = { /* TODO: Bulk operations */ }) {
                        Icon(Icons.Default.MoreVert, "More Options")
                    }
                }
            }
            
            // Plugin List
            if (availablePlugins.isEmpty()) {
                item {
                    EmptyPluginsCard()
                }
            } else {
            // Plugin Cards (only show unloaded plugins)
            items(availablePlugins.filter { !loadedPluginIds.contains(it.id) }) { plugin ->
                PluginCard(
                        plugin = plugin,
                        isLoaded = plugin.id in loadedPluginIds,
                        onTogglePlugin = { metadata ->
                            coroutineScope.launch {
                                if (metadata.id in loadedPluginIds) {
                                    val success = pluginFramework.unloadPlugin(metadata.id)
                                    logger.i(LogCategory.USER_ACTION, "PluginManagementScreen", 
                                        "Unloaded plugin: ${metadata.name} - Success: $success")
                                    android.widget.Toast.makeText(
                                        context,
                                        if (success) "Unloaded ${metadata.name}" else "Failed to unload plugin",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    val success = pluginFramework.loadPlugin(metadata.id)
                                    logger.i(LogCategory.USER_ACTION, "PluginManagementScreen", 
                                        "Loaded plugin: ${metadata.name} - Success: $success")
                                    android.widget.Toast.makeText(
                                        context,
                                        if (success) "Loaded ${metadata.name}" else "Failed to load plugin",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        onShowDetails = { 
                            selectedPlugin = it
                            showPluginDetails = true
                        },
                        onConfigurePlugin = { 
                            pluginToConfig = it
                            showConfigDialog = true
                        }
                    )
                }
            }
        }
    }
    
    // Plugin Details Dialog
    if (showPluginDetails && selectedPlugin != null) {
        PluginDetailsDialog(
            plugin = selectedPlugin!!,
            isLoaded = selectedPlugin!!.id in loadedPluginIds,
            onDismiss = { 
                showPluginDetails = false
                selectedPlugin = null
            },
            onTogglePlugin = { metadata ->
                coroutineScope.launch {
                    if (metadata.id in loadedPluginIds) {
                        pluginFramework.unloadPlugin(metadata.id)
                    } else {
                        pluginFramework.loadPlugin(metadata.id)
                    }
                }
                showPluginDetails = false
                selectedPlugin = null
            }
        )
    }
    
    // Plugin Configuration Dialog
    if (showConfigDialog && pluginToConfig != null) {
        PluginConfigurationDialog(
            plugin = pluginToConfig!!,
            pluginFramework = pluginFramework,
            onDismiss = { 
                showConfigDialog = false
                pluginToConfig = null
            }
        )
    }
}

@Composable
fun PluginOverviewCard(
    totalPlugins: Int,
    loadedPlugins: Int,
    enabledPlugins: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Plugin Overview",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                OverviewStatItem("Total", totalPlugins.toString(), Icons.Default.Info)
                OverviewStatItem("Loaded", loadedPlugins.toString(), Icons.Default.Check)
                OverviewStatItem("Enabled", enabledPlugins.toString(), Icons.Default.Settings)
            }
        }
    }
}

@Composable
fun OverviewStatItem(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
fun PluginCard(
    plugin: PluginMetadata,
    isLoaded: Boolean,
    onTogglePlugin: (PluginMetadata) -> Unit,
    onShowDetails: (PluginMetadata) -> Unit,
    onConfigurePlugin: (PluginMetadata) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = plugin.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Text(
                        text = "v${plugin.version} by ${plugin.author}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                
                PluginStatusChip(isLoaded, plugin.enabled)
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = plugin.description,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            
            if (plugin.dependencies.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Dependencies: ${plugin.dependencies.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { onShowDetails(plugin) }) {
                    Text("Details")
                }
                
                if (isLoaded) {
                    TextButton(onClick = { onConfigurePlugin(plugin) }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Configure")
                    }
                }
                
                                    TextButton(
                        onClick = { onTogglePlugin(plugin) },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (isLoaded) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    ) {
                        Icon(
                            if (isLoaded) Icons.Default.Delete else Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isLoaded) "Unload" else "Load")
                    }
            }
        }
    }
}

@Composable
fun PluginStatusChip(isLoaded: Boolean, isEnabled: Boolean) {
    val (color, text) = when {
        isLoaded -> Color.Green to "Loaded"
        isEnabled -> MaterialTheme.colorScheme.primary to "Enabled"
        else -> MaterialTheme.colorScheme.outline to "Disabled"
    }
    
    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.padding(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(color, RoundedCornerShape(50))
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = color,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun EmptyPluginsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No plugins available",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                text = "Plugins will appear here when installed",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
fun PluginDetailsDialog(
    plugin: PluginMetadata,
    isLoaded: Boolean,
    onDismiss: () -> Unit,
    onTogglePlugin: (PluginMetadata) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(plugin.name)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                PluginDetailItem("Name", plugin.name)
                PluginDetailItem("ID", plugin.id)
                PluginDetailItem("Version", plugin.version)
                PluginDetailItem("Author", plugin.author)
                PluginDetailItem("Status", if (isLoaded) "Loaded" else "Not Loaded")
                PluginDetailItem("Enabled", if (plugin.enabled) "Yes" else "No")
                PluginDetailItem("Load Order", plugin.loadOrder.toString())
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "Description:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = plugin.description,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
                
                if (plugin.permissions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Permissions:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    plugin.permissions.forEach { permission ->
                        Text(
                            text = "• $permission",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
                
                if (plugin.dependencies.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Dependencies:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    plugin.dependencies.forEach { dep ->
                        Text(
                            text = "• $dep",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onTogglePlugin(plugin) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (isLoaded) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            ) {
                Text(if (isLoaded) "Unload" else "Load")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun PluginDetailItem(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(2f)
        )
    }
}

@Composable
fun PluginConfigurationDialog(
    plugin: PluginMetadata,
    pluginFramework: PluginFramework,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val pluginInstance = pluginFramework.getPlugin(plugin.id)
    val configSchema = pluginInstance?.getConfigurationSchema()
    val currentConfig = pluginFramework.getPluginConfiguration(plugin.id) ?: emptyMap()
    
    val coroutineScope = rememberCoroutineScope()
    
    // Configuration state using Compose state management
    val configValues = remember(configSchema) { 
        val map = mutableMapOf<String, MutableState<Any?>>()
        configSchema?.fields?.forEach { field ->
            val initialValue = currentConfig[field.key] ?: field.defaultValue
            map[field.key] = mutableStateOf(initialValue)
        }
        map
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Configure ${plugin.name}")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (configSchema != null && configSchema.fields.isNotEmpty()) {
                    Text(
                        text = "Configure settings for ${plugin.name}:",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    configSchema.fields.forEach { field ->
                        val fieldState = configValues[field.key]
                        if (fieldState != null) {
                            ConfigurationField(
                                field = field,
                                currentValue = fieldState.value,
                                onValueChange = { value ->
                                    fieldState.value = value
                                }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                } else {
                    Text(
                        text = "This plugin does not have configurable settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        },
        confirmButton = {
            if (configSchema != null && configSchema.fields.isNotEmpty()) {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            // Convert state values to regular map, preserving types
                            val finalConfig = mutableMapOf<String, Any>()
                            configValues.forEach { (key, state) ->
                                state.value?.let { value ->
                                    finalConfig[key] = value
                                }
                            }
                            
                            val success = pluginFramework.updatePluginConfiguration(
                                plugin.id, 
                                finalConfig
                            )
                            if (success) {
                                android.widget.Toast.makeText(
                                    context,
                                    "Configuration saved for ${plugin.name}",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                onDismiss()
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    "Failed to save configuration",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                ) {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (configSchema?.fields?.isNotEmpty() == true) "Cancel" else "Close")
            }
        }
    )
}

@Composable
fun LoadedPluginCard(
    plugin: PluginMetadata,
    printerService: com.google.virtualprinter.printer.PrinterService,
    pluginFramework: PluginFramework,
    refreshKey: Int,
    onUnload: (String) -> Unit,
    onConfigure: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var configuration by remember { mutableStateOf(mapOf<String, Any>()) }

    // Load current configuration
    LaunchedEffect(plugin.id, refreshKey) {
        val config = pluginFramework.getPluginConfiguration(plugin.id)
        if (config != null) {
            configuration = config
        }
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plugin.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Active • ${plugin.version}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            
            Text(
                text = plugin.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Show current configuration if available
            if (configuration.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Current Configuration:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        configuration.forEach { (key, value) ->
                            Text(
                                text = "• $key: $value",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
            
            // Action buttons
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Test button (specific to plugin type)
                when (plugin.id) {
                    "delay_simulator" -> {
                        var testResult by remember { mutableStateOf<String?>(null) }
                        var isRunningTest by remember { mutableStateOf(false) }
                        
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    isRunningTest = true
                                    testResult = null
                                    try {
                                        val result = printerService.testDelaySimulatorPlugin()
                                        testResult = result
                                    } catch (e: Exception) {
                                        testResult = "Test failed: ${e.message}"
                                    } finally {
                                        isRunningTest = false
                                    }
                                }
                            },
                            enabled = !isRunningTest,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary
                            )
                        ) {
                            if (isRunningTest) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onTertiary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Testing...")
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Test Delay")
                            }
                        }
                        
                        // Show test result
                        testResult?.let { result ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                )
                            ) {
                                Text(
                                    text = result,
                                    modifier = Modifier.padding(8.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    
                    "error_injection" -> {
                        var testResult by remember { mutableStateOf<String?>(null) }
                        var isRunningTest by remember { mutableStateOf(false) }
                        
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    isRunningTest = true
                                    testResult = null
                                    try {
                                        val result = printerService.testErrorInjectionPlugin()
                                        testResult = result
                                    } catch (e: Exception) {
                                        testResult = "Test failed: ${e.message}"
                                    } finally {
                                        isRunningTest = false
                                    }
                                }
                            },
                            enabled = !isRunningTest,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary
                            )
                        ) {
                            if (isRunningTest) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onTertiary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Testing...")
                            } else {
                                Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Test Errors")
                            }
                        }
                        
                        // Show test result
                        testResult?.let { result ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                )
                            ) {
                                Text(
                                    text = result,
                                    modifier = Modifier.padding(8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 10,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    
                    else -> {
                        // Generic test button for other plugins
                        Text(
                            text = "Plugin loaded and ready",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
                
                // Configure and Unload buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onConfigure(plugin.id) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Configure")
                    }
                    
                    Button(
                        onClick = { onUnload(plugin.id) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Unload")
                    }
                }
            }
        }
    }
}

