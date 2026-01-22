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

package com.google.virtualprinter.plugins

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import com.google.virtualprinter.logging.LogCategory
import com.google.virtualprinter.logging.LogLevel
import com.google.virtualprinter.logging.PrinterLogger
import com.google.virtualprinter.queue.PrintJob
import com.google.virtualprinter.queue.PrintJobState
import com.google.virtualprinter.utils.IppAttributesUtils
import com.hp.jipp.encoding.AttributeGroup
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Plugin interface for extending printer behavior
 */
interface PrinterPlugin {
    val id: String
    val name: String
    val version: String
    val description: String
    val author: String
    
    /**
     * Called when the plugin is loaded
     */
    suspend fun onLoad(context: Context): Boolean
    
    /**
     * Called when the plugin is unloaded
     */
    suspend fun onUnload(): Boolean
    
    /**
     * Called before processing a print job
     * @return true to continue processing, false to stop
     */
    suspend fun beforeJobProcessing(job: PrintJob): Boolean = true
    
    /**
     * Called during print job processing
     * @return modified job or null to use original
     */
    suspend fun processJob(job: PrintJob, documentBytes: ByteArray): JobProcessingResult? = null
    
    /**
     * Called after processing a print job
     */
    suspend fun afterJobProcessing(job: PrintJob, success: Boolean) {}
    
    /**
     * Called when IPP attributes are requested
     * @return custom attributes or null to use defaults
     */
    suspend fun customizeIppAttributes(originalAttributes: List<AttributeGroup>): List<AttributeGroup>? = null
    
    /**
     * Called for custom IPP operations
     * @return true if handled, false to use default handling
     */
    suspend fun handleCustomIppOperation(operation: String, attributes: List<AttributeGroup>): Boolean = false
    
    /**
     * Called when an error occurs
     * @return true if error was handled, false to use default handling
     */
    suspend fun handleError(error: Throwable, context: String): Boolean = false
    
    /**
     * Get plugin configuration UI data
     */
    fun getConfigurationSchema(): PluginConfigurationSchema? = null
    
    /**
     * Update plugin configuration
     */
    suspend fun updateConfiguration(config: Map<String, Any>): Boolean = true
}

/**
 * Job processing result from plugin
 */
data class JobProcessingResult(
    val processedBytes: ByteArray?,
    val modifiedJob: PrintJob?,
    val customMetadata: Map<String, Any> = emptyMap(),
    val shouldContinue: Boolean = true,
    val customResponse: String? = null
)

/**
 * Plugin configuration schema for UI generation
 */
data class PluginConfigurationSchema(
    val fields: List<ConfigurationField>
)

/**
 * Configuration field definition
 */
data class ConfigurationField(
    val key: String,
    val label: String,
    val type: FieldType,
    val defaultValue: Any?,
    val required: Boolean = false,
    val description: String? = null,
    val options: List<String>? = null, // For SELECT type
    val min: Number? = null, // For NUMBER type
    val max: Number? = null  // For NUMBER type
)

/**
 * Configuration field types
 */
enum class FieldType {
    TEXT, NUMBER, BOOLEAN, SELECT, FILE, COLOR
}

/**
 * Plugin metadata for registration
 */
data class PluginMetadata(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val className: String,
    val enabled: Boolean = false,
    val loadOrder: Int = 100,
    val dependencies: List<String> = emptyList(),
    val permissions: List<String> = emptyList()
) {
    companion object {
        fun fromJson(json: JSONObject): PluginMetadata {
            return PluginMetadata(
                id = json.getString("id"),
                name = json.getString("name"),
                version = json.getString("version"),
                description = json.getString("description"),
                author = json.getString("author"),
                className = json.getString("className"),
                enabled = json.optBoolean("enabled", false),
                loadOrder = json.optInt("loadOrder", 100),
                dependencies = json.optJSONArray("dependencies")?.let { array ->
                    (0 until array.length()).map { array.getString(it) }
                } ?: emptyList(),
                permissions = json.optJSONArray("permissions")?.let { array ->
                    (0 until array.length()).map { array.getString(it) }
                } ?: emptyList()
            )
        }
    }
    
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("version", version)
            put("description", description)
            put("author", author)
            put("className", className)
            put("enabled", enabled)
            put("loadOrder", loadOrder)
            put("dependencies", org.json.JSONArray(dependencies))
            put("permissions", org.json.JSONArray(permissions))
        }
    }
}

/**
 * Plugin framework manager
 */
class PluginFramework private constructor(private val context: Context) {
    companion object {
        private const val TAG = "PluginFramework"
        private const val PLUGINS_DIR = "plugins"
        private const val PLUGIN_CONFIG_FILE = "plugin_config.json"
        
        @Volatile
        private var INSTANCE: PluginFramework? = null
        
        fun getInstance(context: Context): PluginFramework {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PluginFramework(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val logger = PrinterLogger.getInstance(context)
    private val loadedPlugins = ConcurrentHashMap<String, PrinterPlugin>()
    private val pluginMetadata = ConcurrentHashMap<String, PluginMetadata>()
    private val pluginConfigurations = ConcurrentHashMap<String, Map<String, Any>>()
    
    private val _availablePlugins = MutableStateFlow<List<PluginMetadata>>(emptyList())
    val availablePlugins: StateFlow<List<PluginMetadata>> = _availablePlugins.asStateFlow()
    
    private val _loadedPluginIds = MutableStateFlow<Set<String>>(emptySet())
    val loadedPluginIds: StateFlow<Set<String>> = _loadedPluginIds.asStateFlow()
    
    init {
        initializeBuiltInPlugins()
        loadPluginMetadata()
    }
    
    /**
     * Initialize built-in plugins
     */
    private fun initializeBuiltInPlugins() {
        // Register all built-in plugins
        val builtInPlugins = listOf(
            PluginMetadata(
                id = "delay_simulator",
                name = "Processing Delay Simulator",
                version = "1.0.0",
                description = "Simulates processing delays for testing slow network or heavy load conditions",
                author = "Built-in",
                className = "DelaySimulatorPlugin",
                enabled = false
            ),
            PluginMetadata(
                id = "error_injection",
                name = "Error Injection",
                version = "1.0.0",
                description = "Injects various errors for testing error handling",
                author = "Built-in",
                className = "ErrorInjectionPlugin",
                enabled = false
            ),
            PluginMetadata(
                id = "document_modifier",
                name = "Document Modifier",
                version = "1.0.0",
                description = "Modifies documents during processing for testing different scenarios",
                author = "Built-in",
                className = "DocumentModifierPlugin",
                enabled = false
            ),
            PluginMetadata(
                id = "attribute_override",
                name = "Attribute Override",
                version = "1.0.0",
                description = "Overrides IPP attributes for testing different printer capabilities",
                author = "Built-in",
                className = "AttributeOverridePlugin",
                enabled = false
            ),
            PluginMetadata(
                id = "logging_enhancer",
                name = "Enhanced Logging",
                version = "1.0.0",
                description = "Adds enhanced logging capabilities for debugging",
                author = "Built-in",
                className = "LoggingEnhancerPlugin",
                enabled = false
            )
        )
        
        builtInPlugins.forEach { metadata ->
            pluginMetadata[metadata.id] = metadata
        }
        
        updateAvailablePlugins()
        
        logger.d(LogCategory.SYSTEM, TAG, "Registered ${builtInPlugins.size} built-in plugins")
    }
    
    /**
     * Load a plugin by ID
     */
    suspend fun loadPlugin(pluginId: String): Boolean {
        val metadata = pluginMetadata[pluginId]
        if (metadata == null) {
            logger.e(LogCategory.SYSTEM, TAG, "Plugin metadata not found: $pluginId")
            return false
        }
        
        if (loadedPlugins.containsKey(pluginId)) {
            logger.w(LogCategory.SYSTEM, TAG, "Plugin already loaded: $pluginId")
            return true
        }
        
        return try {
            // Check dependencies
            if (!checkDependencies(metadata)) {
                logger.e(LogCategory.SYSTEM, TAG, "Plugin dependencies not satisfied: $pluginId")
                return false
            }
            
            // Create plugin instance
            val plugin = createPluginInstance(metadata)
            if (plugin == null) {
                logger.e(LogCategory.SYSTEM, TAG, "Failed to create plugin instance: $pluginId")
                return false
            }
            
            // Load plugin
            val loaded = plugin.onLoad(context)
            if (loaded) {
                loadedPlugins[pluginId] = plugin
                
                // Initialize plugin with default configuration if none exists
                if (!pluginConfigurations.containsKey(pluginId)) {
                    val configSchema = plugin.getConfigurationSchema()
                    if (configSchema != null) {
                        val defaultConfig = mutableMapOf<String, Any>()
                        configSchema.fields.forEach { field ->
                            field.defaultValue?.let { defaultValue ->
                                defaultConfig[field.key] = defaultValue
                            }
                        }
                        if (defaultConfig.isNotEmpty()) {
                            pluginConfigurations[pluginId] = defaultConfig
                            plugin.updateConfiguration(defaultConfig)
                            savePluginConfigurations()
                            logger.d(LogCategory.SYSTEM, TAG, "Initialized plugin ${plugin.name} with default configuration")
                        }
                    }
                } else {
                    // Apply existing configuration
                    val existingConfig = pluginConfigurations[pluginId]!!
                    plugin.updateConfiguration(existingConfig)
                    logger.d(LogCategory.SYSTEM, TAG, "Applied existing configuration to plugin ${plugin.name}")
                }
                
                // Update metadata to enabled
                pluginMetadata[pluginId] = metadata.copy(enabled = true)
                
                updateLoadedPlugins()
                updateAvailablePlugins()
                
                logger.i(LogCategory.SYSTEM, TAG, "Plugin loaded successfully: ${plugin.name}")
                return true
            } else {
                logger.e(LogCategory.SYSTEM, TAG, "Plugin failed to load: ${plugin.name}")
                return false
            }
            
        } catch (e: Exception) {
            logger.e(LogCategory.SYSTEM, TAG, "Error loading plugin: $pluginId", e)
            false
        }
    }
    
    /**
     * Unload a plugin by ID
     */
    suspend fun unloadPlugin(pluginId: String): Boolean {
        val plugin = loadedPlugins[pluginId]
        if (plugin == null) {
            logger.w(LogCategory.SYSTEM, TAG, "Plugin not loaded: $pluginId")
            return false
        }
        
        return try {
            val unloaded = plugin.onUnload()
            if (unloaded) {
                loadedPlugins.remove(pluginId)
                
                // Update metadata to disabled
                pluginMetadata[pluginId]?.let { metadata ->
                    pluginMetadata[pluginId] = metadata.copy(enabled = false)
                }
                
                updateLoadedPlugins()
                updateAvailablePlugins()
                
                logger.i(LogCategory.SYSTEM, TAG, "Plugin unloaded successfully: ${plugin.name}")
                return true
            } else {
                logger.e(LogCategory.SYSTEM, TAG, "Plugin failed to unload: ${plugin.name}")
                return false
            }
            
        } catch (e: Exception) {
            logger.e(LogCategory.SYSTEM, TAG, "Error unloading plugin: $pluginId", e)
            false
        }
    }
    
    /**
     * Execute plugin hooks for job processing (before)
     */
    suspend fun executeBeforeJobProcessing(job: PrintJob): Boolean {
        val sortedPlugins = getSortedLoadedPlugins()
        
        for (plugin in sortedPlugins) {
            try {
                val shouldContinue = plugin.beforeJobProcessing(job)
                if (!shouldContinue) {
                    logger.i(LogCategory.SYSTEM, TAG, "Plugin ${plugin.name} stopped job processing for job ${job.id}")
                    return false
                }
            } catch (e: Exception) {
                logger.e(LogCategory.SYSTEM, TAG, "Error in plugin ${plugin.name} beforeJobProcessing", e)
            }
        }
        
        return true
    }
    
    /**
     * Execute plugin hooks for job processing
     */
    suspend fun executeJobProcessing(job: PrintJob, documentBytes: ByteArray): JobProcessingResult? {
        val sortedPlugins = getSortedLoadedPlugins()
        
        for (plugin in sortedPlugins) {
            try {
                val result = plugin.processJob(job, documentBytes)
                if (result != null) {
                    logger.d(LogCategory.SYSTEM, TAG, "Plugin ${plugin.name} processed job ${job.id}")
                    return result
                }
            } catch (e: Exception) {
                logger.e(LogCategory.SYSTEM, TAG, "Error in plugin ${plugin.name} processJob", e)
            }
        }
        
        return null
    }
    
    /**
     * Execute plugin hooks for job processing (after)
     */
    suspend fun executeAfterJobProcessing(job: PrintJob, success: Boolean) {
        val sortedPlugins = getSortedLoadedPlugins()
        
        for (plugin in sortedPlugins) {
            try {
                plugin.afterJobProcessing(job, success)
            } catch (e: Exception) {
                logger.e(LogCategory.SYSTEM, TAG, "Error in plugin ${plugin.name} afterJobProcessing", e)
            }
        }
    }
    
    /**
     * Execute plugin hooks for IPP attribute customization
     */
    suspend fun executeIppAttributeCustomization(originalAttributes: List<AttributeGroup>): List<AttributeGroup> {
        var currentAttributes = originalAttributes
        val sortedPlugins = getSortedLoadedPlugins()
        
        for (plugin in sortedPlugins) {
            try {
                val customAttributes = plugin.customizeIppAttributes(currentAttributes)
                if (customAttributes != null) {
                    currentAttributes = customAttributes
                    logger.d(LogCategory.SYSTEM, TAG, "Plugin ${plugin.name} customized IPP attributes")
                }
            } catch (e: Exception) {
                logger.e(LogCategory.SYSTEM, TAG, "Error in plugin ${plugin.name} customizeIppAttributes", e)
            }
        }
        
        return currentAttributes
    }
    
    /**
     * Execute plugin hooks for custom IPP operations
     */
    suspend fun executeCustomIppOperation(operation: String, attributes: List<AttributeGroup>): Boolean {
        val sortedPlugins = getSortedLoadedPlugins()
        
        for (plugin in sortedPlugins) {
            try {
                val handled = plugin.handleCustomIppOperation(operation, attributes)
                if (handled) {
                    logger.d(LogCategory.SYSTEM, TAG, "Plugin ${plugin.name} handled custom IPP operation: $operation")
                    return true
                }
            } catch (e: Exception) {
                logger.e(LogCategory.SYSTEM, TAG, "Error in plugin ${plugin.name} handleCustomIppOperation", e)
            }
        }
        
        return false
    }
    
    /**
     * Execute plugin hooks for error handling
     */
    suspend fun executeErrorHandling(error: Throwable, context: String): Boolean {
        val sortedPlugins = getSortedLoadedPlugins()
        
        for (plugin in sortedPlugins) {
            try {
                val handled = plugin.handleError(error, context)
                if (handled) {
                    logger.d(LogCategory.SYSTEM, TAG, "Plugin ${plugin.name} handled error in context: $context")
                    return true
                }
            } catch (e: Exception) {
                logger.e(LogCategory.SYSTEM, TAG, "Error in plugin ${plugin.name} handleError", e)
            }
        }
        
        return false
    }
    
    /**
     * Get plugin configuration
     */
    fun getPluginConfiguration(pluginId: String): Map<String, Any>? {
        return pluginConfigurations[pluginId]
    }
    
    /**
     * Update plugin configuration
     */
    suspend fun updatePluginConfiguration(pluginId: String, config: Map<String, Any>): Boolean {
        val plugin = loadedPlugins[pluginId]
        if (plugin == null) {
            logger.w(LogCategory.SYSTEM, TAG, "Cannot configure unloaded plugin: $pluginId")
            return false
        }
        
        return try {
            val updated = plugin.updateConfiguration(config)
            if (updated) {
                pluginConfigurations[pluginId] = config
                savePluginConfigurations()
                logger.i(LogCategory.SYSTEM, TAG, "Updated configuration for plugin: ${plugin.name}")
            }
            updated
        } catch (e: Exception) {
            logger.e(LogCategory.SYSTEM, TAG, "Error updating plugin configuration: $pluginId", e)
            false
        }
    }
    
    /**
     * Get plugin by ID
     */
    fun getPlugin(pluginId: String): PrinterPlugin? {
        return loadedPlugins[pluginId]
    }
    
    /**
     * Get all loaded plugins
     */
    fun getLoadedPlugins(): List<PrinterPlugin> {
        return loadedPlugins.values.toList()
    }
    
    // Private helper methods
    
    private fun createPluginInstance(metadata: PluginMetadata): PrinterPlugin? {
        return when (metadata.id) {
            "delay_simulator" -> DelaySimulatorPlugin()
            "error_injection" -> ErrorInjectionPlugin()
            "document_modifier" -> DocumentModifierPlugin()
            "attribute_override" -> AttributeOverridePlugin()
            "logging_enhancer" -> LoggingEnhancerPlugin()
            else -> {
                // For external plugins, would use class loading here
                logger.w(LogCategory.SYSTEM, TAG, "Unknown plugin class: ${metadata.className}")
                null
            }
        }
    }
    
    private fun checkDependencies(metadata: PluginMetadata): Boolean {
        return metadata.dependencies.all { dependency ->
            loadedPlugins.containsKey(dependency)
        }
    }
    
    private fun getSortedLoadedPlugins(): List<PrinterPlugin> {
        return loadedPlugins.values.sortedBy { plugin ->
            pluginMetadata[plugin.id]?.loadOrder ?: 100
        }
    }
    
    private fun updateAvailablePlugins() {
        _availablePlugins.value = pluginMetadata.values.sortedBy { it.name }
    }
    
    private fun updateLoadedPlugins() {
        _loadedPluginIds.value = loadedPlugins.keys.toSet()
    }
    // Force refresh of plugin availability/loaded state
    fun refreshState() {
        updateAvailablePlugins()
        updateLoadedPlugins()
        logger.d(LogCategory.SYSTEM, TAG, "Plugin state refreshed")
    }
    private fun loadPluginMetadata() {
        try {
            val configFile = File(context.filesDir, PLUGIN_CONFIG_FILE)
            if (configFile.exists()) {
                val jsonContent = configFile.readText()
                val jsonObject = JSONObject(jsonContent)
                
                // Load configurations
                val configsJson = jsonObject.optJSONObject("configurations")
                configsJson?.let { configs ->
                    configs.keys().forEach { pluginId ->
                        val configJson = configs.getJSONObject(pluginId)
                        val configMap = mutableMapOf<String, Any>()
                        configJson.keys().forEach { key ->
                            configMap[key] = configJson.get(key)
                        }
                        pluginConfigurations[pluginId] = configMap
                    }
                }
            }
        } catch (e: Exception) {
            logger.e(LogCategory.SYSTEM, TAG, "Error loading plugin metadata", e)
        }
    }
    
    private fun savePluginConfigurations() {
        try {
            val configFile = File(context.filesDir, PLUGIN_CONFIG_FILE)
            val jsonObject = JSONObject()
            
            // Save configurations
            val configsJson = JSONObject()
            pluginConfigurations.forEach { (pluginId, config) ->
                val configJson = JSONObject(config)
                configsJson.put(pluginId, configJson)
            }
            jsonObject.put("configurations", configsJson)
            
            configFile.writeText(jsonObject.toString(2))
        } catch (e: Exception) {
            logger.e(LogCategory.SYSTEM, TAG, "Error saving plugin configurations", e)
        }
    }
}

// Built-in plugin implementations

/**
 * Plugin that adds configurable delays to job processing
 */
class DelaySimulatorPlugin : PrinterPlugin {
    override val id = "delay_simulator"
    override val name = "Processing Delay Simulator"
    override val version = "1.0.0"
    override val description = "Simulates processing delays for testing slow network or heavy load conditions"
    override val author = "Built-in"
    
    private var delayMs: Long = 1000L
    private var randomDelay: Boolean = false
    private var lastCalculatedDelay: Long = 0L // Store the last delay for consistent reporting
    
    override suspend fun onLoad(context: Context): Boolean = true
    
    override suspend fun onUnload(): Boolean = true
    
    override suspend fun beforeJobProcessing(job: PrintJob): Boolean {
        // Calculate delay once and store it
        lastCalculatedDelay = if (randomDelay) {
            (delayMs * 0.5 + Math.random() * delayMs).toLong()
        } else {
            delayMs
        }
        
        kotlinx.coroutines.delay(lastCalculatedDelay)
        
        return true // Continue processing
    }
    
    override suspend fun processJob(job: PrintJob, documentBytes: ByteArray): JobProcessingResult? {
        // Return the same delay that was actually used
        return JobProcessingResult(
            processedBytes = null,
            modifiedJob = null,
            customMetadata = mapOf("simulated_delay_ms" to lastCalculatedDelay)
        )
    }
    
    override fun getConfigurationSchema(): PluginConfigurationSchema {
        return PluginConfigurationSchema(
            fields = listOf(
                ConfigurationField("delay_ms", "Delay (milliseconds)", FieldType.NUMBER, 1000L, min = 0, max = 30000),
                ConfigurationField("random_delay", "Random delay", FieldType.BOOLEAN, false)
            )
        )
    }
    
    override suspend fun updateConfiguration(config: Map<String, Any>): Boolean {
        delayMs = (config["delay_ms"] as? Number)?.toLong() ?: 1000L
        randomDelay = config["random_delay"] as? Boolean ?: false
        return true
    }
}

/**
 * Plugin that injects various errors for testing error handling and system resilience
 */
class ErrorInjectionPlugin : PrinterPlugin {
    override val id = "error_injection"
    override val name = "Error Injection"
    override val version = "1.0.0"
    override val description = "Injects various errors for testing error handling and system resilience"
    override val author = "Built-in"
    
    private var errorProbability: Float = 0.1f
    private var errorTypes: List<String> = listOf("network", "memory", "format")
    private var errorMode: String = "random" // random, sequential, specific
    private var specificErrorType: String = "network"
    private var currentErrorIndex = 0
    private var lastInjectedError: String? = null
    
    // Predefined error scenarios
    private val errorScenarios = mapOf(
        "network" to listOf(
            "Connection timeout - simulated network failure",
            "Socket connection refused - printer unreachable", 
            "Network congestion - packet loss detected",
            "DNS resolution failed - printer hostname unknown"
        ),
        "memory" to listOf(
            "Out of memory - insufficient heap space for job processing",
            "Memory allocation failed - large document processing error",
            "Buffer overflow - document size exceeds memory limits"
        ),
        "format" to listOf(
            "Unsupported document format - invalid file structure",
            "Corrupted document data - parsing failed",
            "Invalid IPP attributes - malformed request data",
            "Document compression error - decompression failed"
        ),
        "hardware" to listOf(
            "Printer jam detected - paper feed mechanism blocked",
            "Low ink/toner levels - insufficient supplies",
            "Hardware malfunction - print head alignment error",
            "Temperature sensor error - printer overheating"
        ),
        "authorization" to listOf(
            "Authentication failed - invalid credentials",
            "Access denied - insufficient permissions",
            "Session expired - re-authentication required",
            "Account locked - too many failed attempts"
        ),
        "queue" to listOf(
            "Print queue full - maximum jobs exceeded",
            "Job priority conflict - queue management error",
            "Duplicate job ID - processing conflict detected"
        )
    )
    
    override suspend fun onLoad(context: Context): Boolean = true
    
    override suspend fun onUnload(): Boolean = true
    
    override suspend fun beforeJobProcessing(job: PrintJob): Boolean {
        if (shouldInjectError()) {
            val errorDetails = getNextError()
            lastInjectedError = errorDetails.first
            
            // Log the error injection for debugging
            android.util.Log.w("ErrorInjectionPlugin", 
                "Injecting ${errorDetails.first} error: ${errorDetails.second}")
            
            throw createErrorException(errorDetails.first, errorDetails.second)
        }
        return true
    }
    
    override suspend fun processJob(job: PrintJob, documentBytes: ByteArray): JobProcessingResult? {
        // Return metadata about error injection for testing
        return JobProcessingResult(
            processedBytes = null,
            modifiedJob = null,
            customMetadata = mapOf(
                "error_probability" to errorProbability,
                "error_mode" to errorMode,
                "available_error_types" to errorTypes.joinToString(","),
                "last_injected_error" to (lastInjectedError ?: "none")
            )
        )
    }
    
    private fun shouldInjectError(): Boolean {
        return Math.random() < errorProbability
    }
    
    private fun getNextError(): Pair<String, String> {
        val errorType = when (errorMode) {
            "sequential" -> {
                val type = errorTypes[currentErrorIndex % errorTypes.size]
                currentErrorIndex++
                type
            }
            "specific" -> specificErrorType
            else -> errorTypes.random() // "random" mode
        }
        
        val scenarios = errorScenarios[errorType] ?: listOf("Generic $errorType error")
        val errorMessage = scenarios.random()
        
        return Pair(errorType, errorMessage)
    }
    
    private fun createErrorException(errorType: String, message: String): Exception {
        return when (errorType) {
            "network" -> java.net.ConnectException("Network Error: $message")
            "memory" -> RuntimeException("Memory Error: $message") // OutOfMemoryError is not Exception subclass
            "format" -> IllegalArgumentException("Format Error: $message") 
            "hardware" -> RuntimeException("Hardware Error: $message")
            "authorization" -> SecurityException("Authorization Error: $message")
            "queue" -> IllegalStateException("Queue Error: $message")
            else -> RuntimeException("$errorType Error: $message")
        }
    }
    
    override fun getConfigurationSchema(): PluginConfigurationSchema {
        return PluginConfigurationSchema(
            fields = listOf(
                ConfigurationField(
                    key = "error_probability",
                    label = "Error Probability",
                    type = FieldType.NUMBER,
                    defaultValue = 0.1,
                    min = 0.0,
                    max = 1.0,
                    description = "Probability of error injection (0.0 to 1.0)"
                ),
                ConfigurationField(
                    key = "error_mode",
                    label = "Error Mode",
                    type = FieldType.SELECT,
                    defaultValue = "random",
                    options = listOf("random", "sequential", "specific"),
                    description = "How errors are selected: random, sequential rotation, or specific type"
                ),
                ConfigurationField(
                    key = "error_types",
                    label = "Error Types",
                    type = FieldType.TEXT,
                    defaultValue = "network,memory,format",
                    description = "Comma-separated list: network, memory, format, hardware, authorization, queue"
                ),
                ConfigurationField(
                    key = "specific_error_type",
                    label = "Specific Error Type",
                    type = FieldType.SELECT,
                    defaultValue = "network",
                    options = listOf("network", "memory", "format", "hardware", "authorization", "queue"),
                    description = "Error type to use when mode is 'specific'"
                )
            )
        )
    }
    
    override suspend fun updateConfiguration(config: Map<String, Any>): Boolean {
        errorProbability = (config["error_probability"] as? Number)?.toFloat() ?: 0.1f
        errorMode = config["error_mode"] as? String ?: "random"
        specificErrorType = config["specific_error_type"] as? String ?: "network"
        errorTypes = (config["error_types"] as? String)?.split(",")?.map { it.trim() } 
            ?: listOf("network", "memory", "format")
        
        // Reset sequential counter when configuration changes
        currentErrorIndex = 0
        
        return true
    }
}

/**
 * Plugin that modifies documents during processing by adding watermarks
 */
class DocumentModifierPlugin : PrinterPlugin {
    override val id = "document_modifier"
    override val name = "Document Modifier"
    override val version = "1.0.0"
    override val description = "Adds watermarks to documents (PDF and images) for testing and identification"
    override val author = "Built-in"
    
    @Volatile private var addWatermark: Boolean = false
    @Volatile private var watermarkText: String = "TEST"
    @Volatile private var watermarkOpacity: Float = 0.3f
    @Volatile private var watermarkSize: Int = 48
    @Volatile private var pdfBoxInitialized: Boolean = false
    
    override suspend fun onLoad(context: Context): Boolean {
        // Initialize PdfBox for Android (only once)
        if (!pdfBoxInitialized) {
            try {
                PDFBoxResourceLoader.init(context)
                pdfBoxInitialized = true
                Log.d("DocumentModifierPlugin", "PdfBox initialized successfully")
            } catch (e: Exception) {
                Log.e("DocumentModifierPlugin", "Failed to initialize PdfBox", e)
                return false
            }
        }
        return true
    }
    
    override suspend fun onUnload(): Boolean = true
    
    override suspend fun processJob(job: PrintJob, documentBytes: ByteArray): JobProcessingResult? {
        if (!addWatermark) return null
        
        return try {
            // Detect document type
            val documentType = com.google.virtualprinter.utils.DocumentTypeUtils.detectDocumentType(documentBytes)
            
            val modifiedBytes = when (documentType) {
                com.google.virtualprinter.utils.DocumentType.PDF -> addWatermarkToPdf(documentBytes)
                com.google.virtualprinter.utils.DocumentType.JPEG,
                com.google.virtualprinter.utils.DocumentType.PNG -> addWatermarkToImage(documentBytes)
                else -> {
                    Log.d("DocumentModifierPlugin", "Watermarking not supported for type: $documentType")
                    null
                }
            }
            
            if (modifiedBytes != null) {
        val modifiedJob = job.copy(
                    metadata = job.metadata + mapOf(
                        "watermark" to watermarkText,
                        "watermark_applied" to true
                    )
                )
                
                JobProcessingResult(
                    processedBytes = modifiedBytes,
                    modifiedJob = modifiedJob,
                    customMetadata = mapOf(
                        "watermark_added" to true,
                        "watermark_text" to watermarkText,
                        "document_type" to documentType.name
                    )
                )
            } else {
                // Return metadata only if watermarking failed
                val modifiedJob = job.copy(
                    metadata = job.metadata + mapOf("watermark_attempted" to true)
                )
                JobProcessingResult(
            processedBytes = null,
            modifiedJob = modifiedJob,
                    customMetadata = mapOf("watermark_added" to false)
                )
            }
        } catch (e: Exception) {
            Log.e("DocumentModifierPlugin", "Error adding watermark", e)
            null
        }
    }
    
    /**
     * Add watermark to PDF document using PdfBox
     */
    private fun addWatermarkToPdf(pdfBytes: ByteArray): ByteArray? {
        // Input validation
        if (pdfBytes.isEmpty()) {
            Log.e("DocumentModifierPlugin", "PDF bytes are empty")
            return null
        }
        
        if (watermarkText.isBlank()) {
            Log.e("DocumentModifierPlugin", "Watermark text is blank")
            return null
        }
        
        // Validate configuration ranges
        val validOpacity = watermarkOpacity.coerceIn(0.1f, 1.0f)
        val validSize = watermarkSize.coerceIn(12, 144)
        
        return try {
            // Load the PDF document with automatic resource management
            val inputStream = ByteArrayInputStream(pdfBytes)
            val outputStream = ByteArrayOutputStream()
            
            PDDocument.load(inputStream).use { document ->
                // Create content stream for watermark overlay
                val font = PDType1Font.HELVETICA_BOLD
                
                // Create Extended Graphics State for opacity
                val graphicsState = PDExtendedGraphicsState()
                graphicsState.nonStrokingAlphaConstant = validOpacity
                graphicsState.strokingAlphaConstant = validOpacity
                
                // Iterate through all pages and add watermark
                for (page in document.pages) {
                    // Use try-finally to ensure contentStream is always closed
                    var contentStream: PDPageContentStream? = null
                    try {
                        contentStream = PDPageContentStream(
                            document,
                            page,
                            PDPageContentStream.AppendMode.APPEND,
                            true,
                            true
                        )
                        
                        // Get page dimensions
                        val mediaBox = page.mediaBox
                        val pageWidth = mediaBox.width
                        val pageHeight = mediaBox.height
                        
                        // Apply graphics state for opacity
                        contentStream.setGraphicsStateParameters(graphicsState)
                        
                        // Set watermark properties
                        contentStream.beginText()
                        contentStream.setFont(font, validSize.toFloat())
                        
                        // Set color (gray)
                        contentStream.setNonStrokingColor(
                            128f / 255f,
                            128f / 255f, 
                            128f / 255f
                        )
                        
                        // Calculate center position
                        val centerX = pageWidth / 2
                        val centerY = pageHeight / 2
                        
                        // Create transformation matrix for rotation and positioning
                        // 1. Translate to center
                        // 2. Rotate -45 degrees
                        // 3. The text will be drawn at the origin of this transformed coordinate system
                        val matrix = Matrix()
                        matrix.translate(centerX, centerY)
                        matrix.rotate(Math.toRadians(-45.0))
                        
                        // Adjust for text centering with proper precision
                        val textWidth = font.getStringWidth(watermarkText) / 1000.0f * validSize
                        matrix.translate(-textWidth / 2, 0f)
                        
                        contentStream.setTextMatrix(matrix)
                        
                        // Draw watermark text
                        contentStream.showText(watermarkText)
                        contentStream.endText()
                    } finally {
                        // Always close the content stream
                        contentStream?.close()
                    }
                }
                
                // Save modified PDF to byte array
                document.save(outputStream)
                
                Log.d("DocumentModifierPlugin", "Successfully watermarked PDF with ${document.numberOfPages} pages")
            } // document is automatically closed here
            
            outputStream.toByteArray()
        } catch (e: Exception) {
            Log.e("DocumentModifierPlugin", "Error watermarking PDF", e)
            null
        }
    }
    
    /**
     * Add watermark to image document
     */
    private fun addWatermarkToImage(imageBytes: ByteArray): ByteArray? {
        // Input validation
        if (imageBytes.isEmpty()) {
            Log.e("DocumentModifierPlugin", "Image bytes are empty")
            return null
        }
        
        if (watermarkText.isBlank()) {
            Log.e("DocumentModifierPlugin", "Watermark text is blank")
            return null
        }
        
        // Validate configuration ranges
        val validOpacity = watermarkOpacity.coerceIn(0.1f, 1.0f)
        val validSize = watermarkSize.coerceIn(12, 144)
        
        var originalBitmap: Bitmap? = null
        var mutableBitmap: Bitmap? = null
        
        return try {
            // Decode the image
            originalBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            if (originalBitmap == null) {
                Log.e("DocumentModifierPlugin", "Failed to decode image")
                return null
            }
            
            // Create a mutable copy
            mutableBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
            if (mutableBitmap == null) {
                Log.e("DocumentModifierPlugin", "Failed to create mutable bitmap copy")
                return null
            }
            
            val canvas = Canvas(mutableBitmap)
            
            // Create watermark paint with validated values
            val paint = Paint().apply {
                color = Color.argb((validOpacity * 255).toInt(), 255, 255, 255)
                textSize = validSize.toFloat()
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                setShadowLayer(2f, 2f, 2f, Color.BLACK)
            }
            
            // Draw watermark diagonally
            canvas.save()
            val centerX = mutableBitmap.width / 2f
            val centerY = mutableBitmap.height / 2f
            canvas.rotate(-45f, centerX, centerY)
            canvas.drawText(watermarkText, centerX, centerY, paint)
            canvas.restore()
            
            // Detect original format and preserve it
            val outputStream = ByteArrayOutputStream()
            val format = when {
                imageBytes.size >= 2 && 
                imageBytes[0] == 0xFF.toByte() && 
                imageBytes[1] == 0xD8.toByte() -> {
                    // JPEG signature
                    Bitmap.CompressFormat.JPEG
                }
                else -> {
                    // Default to PNG for PNG and other formats
                    Bitmap.CompressFormat.PNG
                }
            }
            
            val quality = if (format == Bitmap.CompressFormat.JPEG) 90 else 100
            mutableBitmap.compress(format, quality, outputStream)
            
            Log.d("DocumentModifierPlugin", "Successfully watermarked image (format: $format)")
            outputStream.toByteArray()
        } catch (e: Exception) {
            Log.e("DocumentModifierPlugin", "Error watermarking image", e)
            null
        } finally {
            // Always clean up bitmaps to prevent memory leaks
            originalBitmap?.recycle()
            mutableBitmap?.recycle()
        }
    }
    
    override fun getConfigurationSchema(): PluginConfigurationSchema {
        return PluginConfigurationSchema(
            fields = listOf(
                ConfigurationField(
                    key = "add_watermark",
                    label = "Enable Watermark",
                    type = FieldType.BOOLEAN,
                    defaultValue = false,
                    description = "Add watermark to PDF and image documents"
                ),
                ConfigurationField(
                    key = "watermark_text",
                    label = "Watermark Text",
                    type = FieldType.TEXT,
                    defaultValue = "TEST",
                    description = "Text to display as watermark"
                ),
                ConfigurationField(
                    key = "watermark_opacity",
                    label = "Watermark Opacity",
                    type = FieldType.NUMBER,
                    defaultValue = 0.3,
                    min = 0.1,
                    max = 1.0,
                    description = "Opacity of watermark (0.1 = very transparent, 1.0 = solid)"
                ),
                ConfigurationField(
                    key = "watermark_size",
                    label = "Watermark Size",
                    type = FieldType.NUMBER,
                    defaultValue = 48,
                    min = 12,
                    max = 144,
                    description = "Font size of watermark text in points"
                )
            )
        )
    }
    
    override suspend fun updateConfiguration(config: Map<String, Any>): Boolean {
        addWatermark = config["add_watermark"] as? Boolean ?: false
        watermarkText = config["watermark_text"] as? String ?: "TEST"
        watermarkOpacity = (config["watermark_opacity"] as? Number)?.toFloat() ?: 0.3f
        watermarkSize = (config["watermark_size"] as? Number)?.toInt() ?: 48
        return true
    }
}

/**
 * Plugin that overrides IPP attributes
 * 
 * This plugin allows dynamic modification of IPP printer attributes to test
 * different printer capabilities and configurations without modifying core code.
 * 
 * Features:
 * - Override printer name and model information
 * - Modify color support capabilities
 * - Add/remove duplex printing support
 * - Change maximum job queue size
 * - Customize printer location and info strings
 * - Override supported media sizes
 */
class AttributeOverridePlugin : PrinterPlugin {
    override val id = "attribute_override"
    override val name = "Attribute Override"
    override val version = "1.0.0"
    override val description = "Overrides IPP attributes for testing different printer capabilities"
    override val author = "Built-in"
    
    @Volatile private var enableOverride: Boolean = false
    
    // Basic Printer Information
    @Volatile private var printerName: String = "Custom Virtual Printer"
    @Volatile private var printerLocation: String = "Mobile Device"
    @Volatile private var printerInfo: String = "Custom Virtual Printer"
    @Volatile private var printerModel: String = "Virtual Printer v1.0"
    @Volatile private var printerContact: String = "admin@example.com"
    @Volatile private var organizationName: String = "My Organization"
    
    // Queue and Job Management
    @Volatile private var maxJobs: Int = 100
    @Volatile private var acceptingJobs: Boolean = true
    @Volatile private var printerState: String = "idle" // idle, processing, stopped
    
    // Capabilities
    @Volatile private var colorSupported: Boolean = true
    @Volatile private var duplexSupported: Boolean = false
    @Volatile private var mediaSizes: List<String> = listOf(
        "iso_a4_210x297mm",
        "na_letter_8.5x11in"
    )
    
    // Print Quality
    @Volatile private var printQualitySupported: List<String> = listOf("draft", "normal", "high")
    @Volatile private var printQualityDefault: String = "normal"
    @Volatile private var printerResolution: String = "300dpi" // 300dpi, 600dpi, 1200dpi
    
    // Job Template Attributes
    @Volatile private var copiesSupported: Pair<Int, Int> = Pair(1, 99)
    @Volatile private var copiesDefault: Int = 1
    @Volatile private var orientationSupported: List<String> = listOf("portrait", "landscape")
    @Volatile private var orientationDefault: String = "portrait"
    
    // Finishing Options
    @Volatile private var finishingsSupported: List<String> = listOf("none")
    @Volatile private var pagesPerSheet: List<Int> = listOf(1, 2, 4, 6, 9, 16)
    @Volatile private var pagesPerSheetDefault: Int = 1
    
    // Advanced Options
    @Volatile private var customAttributesJson: String = ""
    
    override suspend fun onLoad(context: Context): Boolean {
        Log.d("AttributeOverridePlugin", "Plugin loaded - IPP attribute override capabilities enabled")
        return true
    }
    
    override suspend fun onUnload(): Boolean {
        Log.d("AttributeOverridePlugin", "Plugin unloaded")
        return true
    }
    
    /**
     * Helper function to map print quality strings to PrintQuality enum values
     */
    private fun mapToPrintQuality(quality: String): com.hp.jipp.model.PrintQuality {
        return when(quality) {
            "draft" -> com.hp.jipp.model.PrintQuality.draft
            "high" -> com.hp.jipp.model.PrintQuality.high
            else -> com.hp.jipp.model.PrintQuality.normal
        }
    }
    
    /**
     * Helper function to create print-quality-supported attribute from a list of quality strings
     */
    private fun createPrintQualitySupportedAttribute(qualities: List<String>): com.hp.jipp.encoding.Attribute<*> {
        val qualityEnums = qualities.map { mapToPrintQuality(it) }
        return if (qualityEnums.size == 1) {
            com.hp.jipp.model.Types.printQualitySupported.of(qualityEnums[0])
        } else {
            com.hp.jipp.model.Types.printQualitySupported.of(qualityEnums.first(), *qualityEnums.drop(1).toTypedArray())
        }
    }
    
    override suspend fun customizeIppAttributes(originalAttributes: List<com.hp.jipp.encoding.AttributeGroup>): List<com.hp.jipp.encoding.AttributeGroup>? {
        if (!enableOverride) {
            Log.d("AttributeOverridePlugin", "Override disabled, using original attributes")
        return null
        }
        
        Log.d("AttributeOverridePlugin", "Applying attribute overrides: " +
            "name=$printerName, color=$colorSupported, duplex=$duplexSupported")
        
        return try {
            // Create modified attribute groups
            val modifiedGroups = mutableListOf<com.hp.jipp.encoding.AttributeGroup>()
            
            // Keep operation attributes unchanged
            val operationGroup = originalAttributes.firstOrNull { 
                it.tag == com.hp.jipp.encoding.Tag.operationAttributes 
            }
            if (operationGroup != null) {
                modifiedGroups.add(operationGroup)
            }
            
            // Find or create printer attributes group
            val originalPrinterGroup = originalAttributes.firstOrNull { 
                it.tag == com.hp.jipp.encoding.Tag.printerAttributes 
            }
            
            if (originalPrinterGroup != null) {
                // Build modified printer attributes
                val modifiedAttributes = mutableListOf<com.hp.jipp.encoding.Attribute<*>>()
                
                // Copy original attributes, selectively replacing ones we want to override
                val iterator = originalPrinterGroup.iterator()
                while (iterator.hasNext()) {
                    val attr = iterator.next()
                    val attrName = attr.name
                    
                    // Override specific attributes based on configuration
                    when (attrName) {
                        // Basic Information
                        "printer-name" -> {
                            modifiedAttributes.add(com.hp.jipp.model.Types.printerName.of(printerName))
                            Log.d("AttributeOverridePlugin", "Override: printer-name = $printerName")
                        }
                        "printer-location" -> {
                            modifiedAttributes.add(com.hp.jipp.model.Types.printerLocation.of(printerLocation))
                            Log.d("AttributeOverridePlugin", "Override: printer-location = $printerLocation")
                        }
                        "printer-info" -> {
                            modifiedAttributes.add(com.hp.jipp.model.Types.printerInfo.of(printerInfo))
                            Log.d("AttributeOverridePlugin", "Override: printer-info = $printerInfo")
                        }
                        "printer-make-and-model" -> {
                            modifiedAttributes.add(com.hp.jipp.model.Types.printerMakeAndModel.of(printerModel))
                            Log.d("AttributeOverridePlugin", "Override: printer-make-and-model = $printerModel")
                        }
                        "printer-organization" -> {
                            modifiedAttributes.add(com.hp.jipp.model.Types.printerOrganization.of(organizationName))
                            Log.d("AttributeOverridePlugin", "Override: printer-organization = $organizationName")
                        }
                        
                        // State and Availability
                        "printer-is-accepting-jobs" -> {
                            modifiedAttributes.add(com.hp.jipp.model.Types.printerIsAcceptingJobs.of(acceptingJobs))
                            Log.d("AttributeOverridePlugin", "Override: printer-is-accepting-jobs = $acceptingJobs")
                        }
                        "printer-state" -> {
                            val state = when (printerState) {
                                "processing" -> com.hp.jipp.model.PrinterState.processing
                                "stopped" -> com.hp.jipp.model.PrinterState.stopped
                                else -> com.hp.jipp.model.PrinterState.idle
                            }
                            modifiedAttributes.add(com.hp.jipp.model.Types.printerState.of(state))
                            Log.d("AttributeOverridePlugin", "Override: printer-state = $printerState")
                        }
                        "queued-job-count" -> {
                            val currentCount = try {
                                (attr.getValue() as? Int) ?: 0
                            } catch (e: Exception) {
                                0
                            }
                            val limitedCount = minOf(currentCount, maxJobs)
                            modifiedAttributes.add(com.hp.jipp.model.Types.queuedJobCount.of(limitedCount))
                            Log.d("AttributeOverridePlugin", "Override: queued-job-count = $limitedCount (max: $maxJobs)")
                        }
                        
                        // Capabilities
                        "color-supported" -> {
                            modifiedAttributes.add(com.hp.jipp.model.Types.colorSupported.of(colorSupported))
                            Log.d("AttributeOverridePlugin", "Override: color-supported = $colorSupported")
                        }
                        "print-quality-supported" -> {
                            if (printQualitySupported.isNotEmpty()) {
                                modifiedAttributes.add(createPrintQualitySupportedAttribute(printQualitySupported))
                                Log.d("AttributeOverridePlugin", "Override: print-quality-supported = $printQualitySupported")
                            } else {
                                modifiedAttributes.add(attr)
                            }
                        }
                        "print-quality-default" -> {
                            modifiedAttributes.add(com.hp.jipp.model.Types.printQualityDefault.of(mapToPrintQuality(printQualityDefault)))
                            Log.d("AttributeOverridePlugin", "Override: print-quality-default = $printQualityDefault")
                        }
                        "copies-supported" -> {
                            modifiedAttributes.add(com.hp.jipp.model.Types.copiesSupported.of(
                                copiesSupported.first..copiesSupported.second
                            ))
                            Log.d("AttributeOverridePlugin", "Override: copies-supported = ${copiesSupported.first}-${copiesSupported.second}")
                        }
                        "copies-default" -> {
                            modifiedAttributes.add(com.hp.jipp.model.Types.copiesDefault.of(copiesDefault))
                            Log.d("AttributeOverridePlugin", "Override: copies-default = $copiesDefault")
                        }
                        "orientation-requested-supported" -> {
                            if (orientationSupported.isNotEmpty()) {
                                val orientations = orientationSupported.map {
                                    when(it) {
                                        "landscape" -> com.hp.jipp.model.Orientation.landscape
                                        "reverse-landscape" -> com.hp.jipp.model.Orientation.reverseLandscape
                                        "reverse-portrait" -> com.hp.jipp.model.Orientation.reversePortrait
                                        else -> com.hp.jipp.model.Orientation.portrait
                                    }
                                }
                                val orientAttr = if (orientations.size == 1) {
                                    com.hp.jipp.model.Types.orientationRequestedSupported.of(orientations[0])
                                } else {
                                    com.hp.jipp.model.Types.orientationRequestedSupported.of(orientations.first(), *orientations.drop(1).toTypedArray())
                                }
                                modifiedAttributes.add(orientAttr)
                                Log.d("AttributeOverridePlugin", "Override: orientation-requested-supported = $orientationSupported")
                            } else {
                                modifiedAttributes.add(attr)
                            }
                        }
                        "number-up-supported" -> {
                            if (pagesPerSheet.isNotEmpty()) {
                                // Use raw Int values matching signature: of(Int, vararg Int)
                                when (pagesPerSheet.size) {
                                    1 -> modifiedAttributes.add(com.hp.jipp.model.Types.numberUpSupported.of(pagesPerSheet[0]))
                                    2 -> modifiedAttributes.add(com.hp.jipp.model.Types.numberUpSupported.of(pagesPerSheet[0], pagesPerSheet[1]))
                                    3 -> modifiedAttributes.add(com.hp.jipp.model.Types.numberUpSupported.of(pagesPerSheet[0], pagesPerSheet[1], pagesPerSheet[2]))
                                    4 -> modifiedAttributes.add(com.hp.jipp.model.Types.numberUpSupported.of(pagesPerSheet[0], pagesPerSheet[1], pagesPerSheet[2], pagesPerSheet[3]))
                                    5 -> modifiedAttributes.add(com.hp.jipp.model.Types.numberUpSupported.of(pagesPerSheet[0], pagesPerSheet[1], pagesPerSheet[2], pagesPerSheet[3], pagesPerSheet[4]))
                                    else -> modifiedAttributes.add(com.hp.jipp.model.Types.numberUpSupported.of(pagesPerSheet[0], pagesPerSheet[1], pagesPerSheet[2], pagesPerSheet[3], pagesPerSheet[4], pagesPerSheet[5]))
                                }
                                Log.d("AttributeOverridePlugin", "Override: number-up-supported = $pagesPerSheet")
                            } else {
                                modifiedAttributes.add(attr)
                            }
                        }
                        "number-up-default" -> {
                            modifiedAttributes.add(com.hp.jipp.model.Types.numberUpDefault.of(pagesPerSheetDefault))
                            Log.d("AttributeOverridePlugin", "Override: number-up-default = $pagesPerSheetDefault")
                        }
                        "media-supported" -> {
                            if (mediaSizes.isNotEmpty()) {
                                // mediaSupported.of expects vararg String parameters
                                // Convert List<String> to vararg array
                                val mediaArray = mediaSizes.toTypedArray()
                                val mediaAttr = when {
                                    mediaArray.size == 1 -> com.hp.jipp.model.Types.mediaSupported.of(mediaArray[0])
                                    mediaArray.size >= 2 -> {
                                        val rest = mediaArray.copyOfRange(1, mediaArray.size)
                                        com.hp.jipp.model.Types.mediaSupported.of(mediaArray[0], *rest)
                                    }
                                    else -> attr  // fallback to original
                                }
                                if (mediaArray.isNotEmpty()) {
                                    modifiedAttributes.add(mediaAttr)
                                    Log.d("AttributeOverridePlugin", "Override: media-supported = ${mediaSizes.joinToString()}")
                                } else {
                                    modifiedAttributes.add(attr)
                                }
                            } else {
                                modifiedAttributes.add(attr)
                            }
                        }
                        "sides-supported" -> {
                            // Add duplex support if enabled
                            if (duplexSupported) {
                                modifiedAttributes.add(
                                    com.hp.jipp.model.Types.sidesSupported.of(
                                        "one-sided",
                                        "two-sided-long-edge",
                                        "two-sided-short-edge"
                                    )
                                )
                                Log.d("AttributeOverridePlugin", "Override: Added duplex support")
                            } else {
                                modifiedAttributes.add(com.hp.jipp.model.Types.sidesSupported.of("one-sided"))
                                Log.d("AttributeOverridePlugin", "Override: Duplex disabled")
                            }
                        }
                        else -> {
                            // Keep original attribute
                            modifiedAttributes.add(attr)
                        }
                    }
                }
                
                // Add duplex support if enabled and not already present
                if (duplexSupported && !modifiedAttributes.any { it.name == "sides-supported" }) {
                    modifiedAttributes.add(
                        com.hp.jipp.model.Types.sidesSupported.of(
                            "one-sided",
                            "two-sided-long-edge",
                            "two-sided-short-edge"
                        )
                    )
                    modifiedAttributes.add(com.hp.jipp.model.Types.sidesDefault.of("one-sided"))
                    Log.d("AttributeOverridePlugin", "Added duplex support attributes")
                }
                
                // Add new attributes not present in defaults
                if (!modifiedAttributes.any { it.name == "printer-organization" }) {
                    modifiedAttributes.add(com.hp.jipp.model.Types.printerOrganization.of(organizationName))
                }
                if (!modifiedAttributes.any { it.name == "print-quality-supported" } && printQualitySupported.isNotEmpty()) {
                    modifiedAttributes.add(createPrintQualitySupportedAttribute(printQualitySupported))
                }
                if (!modifiedAttributes.any { it.name == "print-quality-default" }) {
                    modifiedAttributes.add(com.hp.jipp.model.Types.printQualityDefault.of(mapToPrintQuality(printQualityDefault)))
                }
                if (!modifiedAttributes.any { it.name == "copies-supported" }) {
                    modifiedAttributes.add(com.hp.jipp.model.Types.copiesSupported.of(copiesSupported.first..copiesSupported.second))
                }
                if (!modifiedAttributes.any { it.name == "copies-default" }) {
                    modifiedAttributes.add(com.hp.jipp.model.Types.copiesDefault.of(copiesDefault))
                }
                if (!modifiedAttributes.any { it.name == "orientation-requested-supported" } && orientationSupported.isNotEmpty()) {
                    val orientations = orientationSupported.map {
                        when(it) {
                            "landscape" -> com.hp.jipp.model.Orientation.landscape
                            "reverse-landscape" -> com.hp.jipp.model.Orientation.reverseLandscape
                            "reverse-portrait" -> com.hp.jipp.model.Orientation.reversePortrait
                            else -> com.hp.jipp.model.Orientation.portrait
                        }
                    }
                    val orientAttr = if (orientations.size == 1) {
                        com.hp.jipp.model.Types.orientationRequestedSupported.of(orientations[0])
                    } else {
                        com.hp.jipp.model.Types.orientationRequestedSupported.of(orientations.first(), *orientations.drop(1).toTypedArray())
                    }
                    modifiedAttributes.add(orientAttr)
                }
                if (!modifiedAttributes.any { it.name == "number-up-supported" } && pagesPerSheet.isNotEmpty()) {
                    // Skip number-up if not already present - default is sufficient
                    val firstPage = pagesPerSheet[0]
                    if (pagesPerSheet.size == 1) {
                        modifiedAttributes.add(com.hp.jipp.model.Types.numberUpSupported.of(firstPage))
                    }
                }
                if (!modifiedAttributes.any { it.name == "number-up-default" }) {
                    modifiedAttributes.add(com.hp.jipp.model.Types.numberUpDefault.of(pagesPerSheetDefault))
                }

                if (customAttributesJson.isNotBlank()) {
                    try {
                        val customJson = org.json.JSONObject(customAttributesJson.trim())
                        val customAttrNames = customJson.keys()

                        while (customAttrNames.hasNext()) {
                            val attrName = customAttrNames.next()
                            val attrValue = customJson.get(attrName)

                            // Create attribute based on value type
                            val newAttr = when (attrValue) {
                                is org.json.JSONArray -> {
                                    // Array of values (e.g., ["toner-low-warning"])
                                    val values = mutableListOf<String>()
                                    for (i in 0 until attrValue.length()) {
                                        val item = attrValue.get(i)
                                        if (item != org.json.JSONObject.NULL) {
                                            values.add(item.toString())
                                        }
                                    }

                                    if (values.isNotEmpty()) {
                                        // Try to use Types methods for known attributes
                                        when (attrName) {
                                            "printer-state-reasons" -> {
                                                if (values.size == 1) {
                                                    com.hp.jipp.model.Types.printerStateReasons.of(values[0])
                                                } else {
                                                    com.hp.jipp.model.Types.printerStateReasons.of(values[0], *values.drop(1).toTypedArray())
                                                }
                                            }
                                            else -> {

                                                Log.d("AttributeOverridePlugin", "Multi-value attribute $attrName: using first value ${values[0]}")
                                                createCustomAttribute(attrName, values[0])
                                            }
                                        }
                                    } else {
                                        null
                                    }
                                }
                                is String -> {
                                    // Single string value
                                    createCustomAttribute(attrName, attrValue)
                                }
                                is Number -> {
                                    // Single number value
                                    createCustomAttribute(attrName, attrValue)
                                }
                                is Boolean -> {
                                    // Single boolean value
                                    createCustomAttribute(attrName, attrValue)
                                }
                                else -> {
                                    // Other types - convert to string
                                    if (attrValue != org.json.JSONObject.NULL) {
                                        createCustomAttribute(attrName, attrValue.toString())
                                    } else {
                                        null
                                    }
                                }
                            }

                            if (newAttr != null) {
                                // Remove any existing attribute with this name
                                modifiedAttributes.removeAll { it.name == attrName }
                                modifiedAttributes.add(newAttr)
                                Log.d("AttributeOverridePlugin", "Added custom attribute: $attrName = $attrValue")
                            }else{
                                Log.w(
                                    "AttributeOverridePlugin",
                                    "Failed to create attribute: $attrName from value: $attrValue. Keeping existing value."
                                )
                            }
                        }

                        Log.d("AttributeOverridePlugin", "Applied ${customJson.length()} custom attributes from JSON")
                    } catch (e: Exception) {
                        Log.e("AttributeOverridePlugin", "Error parsing custom attributes JSON: ${e.message}", e)
                        // Continue with other attributes even if custom JSON fails
                    }
                }
                
                // Create new printer attributes group with modified attributes
                val modifiedPrinterGroup = com.hp.jipp.encoding.AttributeGroup.groupOf(
                    com.hp.jipp.encoding.Tag.printerAttributes,
                    *modifiedAttributes.toTypedArray()
                )
                modifiedGroups.add(modifiedPrinterGroup)
                
                Log.d("AttributeOverridePlugin", "Successfully applied ${modifiedAttributes.size} attributes")
            } else {
                Log.w("AttributeOverridePlugin", "No printer attributes group found in original attributes")
                return null
            }
            
            // Add any remaining groups (job attributes, etc.)
            originalAttributes.forEach { group ->
                if (group.tag != com.hp.jipp.encoding.Tag.operationAttributes &&
                    group.tag != com.hp.jipp.encoding.Tag.printerAttributes) {
                    modifiedGroups.add(group)
                }
            }
            
            modifiedGroups
        } catch (e: Exception) {
            Log.e("AttributeOverridePlugin", "Error applying attribute overrides", e)
            null
        }
    }

    /**
     * Helper function to create a custom attribute from a single value
     * Uses IppAttributesUtils for attribute creation
     */
    private fun createCustomAttribute(name: String, value: Any): com.hp.jipp.encoding.Attribute<*>? {
        return try {
            when (value) {
                is Int -> {
                    IppAttributesUtils.createAttribute(name, value, "INTEGER")
                }

                is Long -> {
                    IppAttributesUtils.createAttribute(name, value, "INTEGER")
                }

                is Boolean -> {
                    IppAttributesUtils.createAttribute(name, value, "BOOLEAN")
                }

                is String -> {
                    IppAttributesUtils.createAttribute(name, value, "STRING")
                }

                else -> {
                    Log.w(
                        "AttributeOverridePlugin",
                        "Unsupported value type for attribute $name: ${value::class.java.simpleName}"
                    )
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("AttributeOverridePlugin", "Error creating custom attribute $name", e)
            null
        }
    }
    override fun getConfigurationSchema(): PluginConfigurationSchema {
        return PluginConfigurationSchema(
            fields = listOf(
                // === SECTION 1: ENABLE/DISABLE ===
                ConfigurationField(
                    key = "enable_override",
                    label = "🎛️ Enable Override",
                    type = FieldType.BOOLEAN,
                    defaultValue = false,
                    description = "Master switch - Enable all IPP attribute overrides"
                ),
                
                // === SECTION 2: BASIC INFORMATION ===
                ConfigurationField(
                    key = "printer_name",
                    label = "📝 Printer Name",
                    type = FieldType.TEXT,
                    defaultValue = "Custom Virtual Printer",
                    description = "Printer name shown to clients (printer-name)"
                ),
                ConfigurationField(
                    key = "printer_location",
                    label = "📍 Location",
                    type = FieldType.TEXT,
                    defaultValue = "Mobile Device",
                    description = "Physical location (printer-location)"
                ),
                ConfigurationField(
                    key = "printer_info",
                    label = "ℹ️ Printer Info",
                    type = FieldType.TEXT,
                    defaultValue = "Custom Virtual Printer",
                    description = "Human-readable description (printer-info)"
                ),
                ConfigurationField(
                    key = "printer_model",
                    label = "🏷️ Model",
                    type = FieldType.TEXT,
                    defaultValue = "Virtual Printer v1.0",
                    description = "Make and model (printer-make-and-model)"
                ),
                ConfigurationField(
                    key = "organization_name",
                    label = "🏢 Organization",
                    type = FieldType.TEXT,
                    defaultValue = "My Organization",
                    description = "Organization name (printer-organization)"
                ),
                ConfigurationField(
                    key = "printer_contact",
                    label = "📧 Contact",
                    type = FieldType.TEXT,
                    defaultValue = "admin@example.com",
                    description = "Contact information for support"
                ),
                
                // === SECTION 3: STATE & AVAILABILITY ===
                ConfigurationField(
                    key = "accepting_jobs",
                    label = "✅ Accepting Jobs",
                    type = FieldType.BOOLEAN,
                    defaultValue = true,
                    description = "Whether printer accepts new jobs (printer-is-accepting-jobs)"
                ),
                ConfigurationField(
                    key = "printer_state",
                    label = "🔄 Printer State",
                    type = FieldType.SELECT,
                    defaultValue = "idle",
                    options = listOf("idle", "processing", "stopped"),
                    description = "Current printer state (printer-state)"
                ),
                ConfigurationField(
                    key = "max_jobs",
                    label = "📊 Max Queue Size",
                    type = FieldType.NUMBER,
                    defaultValue = 100,
                    min = 1,
                    max = 1000,
                    description = "Maximum queued jobs (affects queued-job-count)"
                ),
                
                // === SECTION 4: PRINT CAPABILITIES ===
                ConfigurationField(
                    key = "color_supported",
                    label = "🎨 Color Support",
                    type = FieldType.BOOLEAN,
                    defaultValue = true,
                    description = "Advertise color printing (color-supported)"
                ),
                ConfigurationField(
                    key = "duplex_supported",
                    label = "📄 Duplex Support",
                    type = FieldType.BOOLEAN,
                    defaultValue = false,
                    description = "Two-sided printing (sides-supported)"
                ),
                ConfigurationField(
                    key = "media_sizes",
                    label = "📏 Paper Sizes",
                    type = FieldType.TEXT,
                    defaultValue = "iso_a4_210x297mm,na_letter_8.5x11in,na_legal_8.5x14in",
                    description = "Comma-separated media sizes (media-supported)"
                ),
                
                // === SECTION 5: PRINT QUALITY ===
                ConfigurationField(
                    key = "print_quality_supported",
                    label = "⚙️ Quality Levels",
                    type = FieldType.TEXT,
                    defaultValue = "draft,normal,high",
                    description = "Comma-separated: draft, normal, high (print-quality-supported)"
                ),
                ConfigurationField(
                    key = "print_quality_default",
                    label = "🎯 Default Quality",
                    type = FieldType.SELECT,
                    defaultValue = "normal",
                    options = listOf("draft", "normal", "high"),
                    description = "Default print quality (print-quality-default)"
                ),
                ConfigurationField(
                    key = "printer_resolution",
                    label = "🔬 Resolution",
                    type = FieldType.SELECT,
                    defaultValue = "600dpi",
                    options = listOf("300dpi", "600dpi", "1200dpi", "2400dpi"),
                    description = "Printer resolution (printer-resolution-supported)"
                ),
                
                // === SECTION 6: JOB TEMPLATE ATTRIBUTES ===
                ConfigurationField(
                    key = "copies_min",
                    label = "📑 Min Copies",
                    type = FieldType.NUMBER,
                    defaultValue = 1,
                    min = 1,
                    max = 99,
                    description = "Minimum copies supported (copies-supported min)"
                ),
                ConfigurationField(
                    key = "copies_max",
                    label = "📑 Max Copies",
                    type = FieldType.NUMBER,
                    defaultValue = 99,
                    min = 1,
                    max = 999,
                    description = "Maximum copies supported (copies-supported max)"
                ),
                ConfigurationField(
                    key = "copies_default",
                    label = "📋 Default Copies",
                    type = FieldType.NUMBER,
                    defaultValue = 1,
                    min = 1,
                    max = 99,
                    description = "Default number of copies (copies-default)"
                ),
                ConfigurationField(
                    key = "orientation_supported",
                    label = "🔄 Orientations",
                    type = FieldType.TEXT,
                    defaultValue = "portrait,landscape",
                    description = "Comma-separated: portrait, landscape, reverse-portrait, reverse-landscape"
                ),
                ConfigurationField(
                    key = "pages_per_sheet_supported",
                    label = "📃 N-up Layouts",
                    type = FieldType.TEXT,
                    defaultValue = "1,2,4,6,9,16",
                    description = "Pages per sheet (number-up-supported)"
                ),
                ConfigurationField(
                    key = "pages_per_sheet_default",
                    label = "📃 Default N-up",
                    type = FieldType.SELECT,
                    defaultValue = "1",
                    options = listOf("1", "2", "4", "6", "9", "16"),
                    description = "Default pages per sheet (number-up-default)"
                ),
                
                // === SECTION 7: ADVANCED ===
                ConfigurationField(
                    key = "custom_attributes_json",
                    label = "⚡ Custom Attributes (JSON)",
                    type = FieldType.TEXT,
                    defaultValue = "",
                    description = "Advanced: Add any IPP attribute as JSON (e.g., {\"attr-name\": \"value\"})"
                )
            )
        )
    }
    
    override suspend fun updateConfiguration(config: Map<String, Any>): Boolean {
        // Master switch
        enableOverride = config["enable_override"] as? Boolean ?: false
        
        // Basic Information
        printerName = config["printer_name"] as? String ?: "Custom Virtual Printer"
        printerLocation = config["printer_location"] as? String ?: "Mobile Device"
        printerInfo = config["printer_info"] as? String ?: "Custom Virtual Printer"
        printerModel = config["printer_model"] as? String ?: "Virtual Printer v1.0"
        organizationName = config["organization_name"] as? String ?: "My Organization"
        printerContact = config["printer_contact"] as? String ?: "admin@example.com"
        
        // State & Availability
        acceptingJobs = config["accepting_jobs"] as? Boolean ?: true
        printerState = config["printer_state"] as? String ?: "idle"
        maxJobs = (config["max_jobs"] as? Number)?.toInt() ?: 100
        
        // Capabilities
        colorSupported = config["color_supported"] as? Boolean ?: true
        duplexSupported = config["duplex_supported"] as? Boolean ?: false
        
        // Media Sizes
        val mediaSizesStr = config["media_sizes"] as? String ?: "iso_a4_210x297mm,na_letter_8.5x11in"
        mediaSizes = mediaSizesStr.split(",").map { it.trim() }.filter { it.isNotBlank() }
        
        // Print Quality
        val qualityStr = config["print_quality_supported"] as? String ?: "draft,normal,high"
        printQualitySupported = qualityStr.split(",").map { it.trim() }.filter { it.isNotBlank() }
        printQualityDefault = config["print_quality_default"] as? String ?: "normal"
        printerResolution = config["printer_resolution"] as? String ?: "600dpi"
        
        // Job Template Attributes
        val copiesMin = (config["copies_min"] as? Number)?.toInt() ?: 1
        val copiesMax = (config["copies_max"] as? Number)?.toInt() ?: 99
        copiesSupported = Pair(copiesMin, copiesMax)
        copiesDefault = (config["copies_default"] as? Number)?.toInt() ?: 1
        
        val orientationStr = config["orientation_supported"] as? String ?: "portrait,landscape"
        orientationSupported = orientationStr.split(",").map { it.trim() }.filter { it.isNotBlank() }
        
        val pagesPerSheetStr = config["pages_per_sheet_supported"] as? String ?: "1,2,4,6,9,16"
        pagesPerSheet = pagesPerSheetStr.split(",").mapNotNull { it.trim().toIntOrNull() }
        pagesPerSheetDefault = (config["pages_per_sheet_default"] as? String)?.toIntOrNull() ?: 1
        
        // Advanced
        customAttributesJson = config["custom_attributes_json"] as? String ?: ""
        
        Log.d("AttributeOverridePlugin", "Configuration updated: " +
            "enabled=$enableOverride, name=$printerName, color=$colorSupported, duplex=$duplexSupported, " +
            "quality=$printQualitySupported, resolution=$printerResolution, media=${mediaSizes.size} sizes, " +
            "copies=$copiesMin-$copiesMax, n-up=${pagesPerSheet.size} options")
        
        return true
    }
}

/**
 * Plugin that enhances logging
 * 
 * This plugin provides advanced logging capabilities for debugging and monitoring:
 * - File-based logging with automatic rotation
 * - Performance metrics tracking (job processing time, throughput)
 * - Detailed job information logging
 * - Configurable log levels
 * - Log file management and export
 * 
 * Log files are stored in the app's cache directory and automatically rotated
 * when they exceed 10MB to prevent excessive storage usage.
 */
class LoggingEnhancerPlugin : PrinterPlugin {
    override val id = "logging_enhancer"
    override val name = "Enhanced Logging"
    override val version = "1.0.0"
    override val description = "Adds enhanced logging capabilities for debugging"
    override val author = "Built-in"
    
    @Volatile private var logLevel: String = "DEBUG"
    @Volatile private var logToFile: Boolean = true
    @Volatile private var logJobDetails: Boolean = true
    @Volatile private var logPerformance: Boolean = false
    
    private var logFile: java.io.File? = null
    private var logWriter: java.io.BufferedWriter? = null
    private val logLock = Any()
    
    // Performance tracking
    private val jobStartTimes = mutableMapOf<Long, Long>()
    private val performanceStats = mutableMapOf<String, MutableList<Long>>()
    
    companion object {
        private const val TAG = "LoggingEnhancerPlugin"
        private const val MAX_LOG_FILE_SIZE = 10 * 1024 * 1024 // 10MB
        private const val LOG_FILE_NAME = "printer_enhanced_log.txt"
    }
    
    override suspend fun onLoad(context: Context): Boolean {
        if (logToFile) {
            initializeLogFile(context)
        }
        writeLog("INFO", "Enhanced Logging Plugin loaded - file logging: $logToFile, performance: $logPerformance")
        return true
    }
    
    override suspend fun onUnload(): Boolean {
        writeLog("INFO", "Enhanced Logging Plugin unloading")
        closeLogFile()
        return true
    }
    
    override suspend fun beforeJobProcessing(job: PrintJob): Boolean {
        val timestamp = System.currentTimeMillis()
        
        if (logJobDetails) {
            val message = buildString {
                append("▶ Job Started: ${job.id}")
                append("\n  Name: ${job.name}")
                append("\n  State: ${job.state}")
                append("\n  Format: ${job.documentFormat}")
                append("\n  Size: ${formatBytes(job.size)}")
                append("\n  Submitted: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(job.submissionTime))}")
                append("\n  User: ${job.jobOriginatingUserName}")
                if (job.metadata.isNotEmpty()) {
                    append("\n  Metadata: ${job.metadata}")
                }
            }
            writeLog("INFO", message)
        }
        
        if (logPerformance) {
            synchronized(jobStartTimes) {
                jobStartTimes[job.id] = timestamp
            }
            writeLog("DEBUG", "Performance tracking started for job ${job.id}")
        }
        
        return true
    }
    
    override suspend fun afterJobProcessing(job: PrintJob, success: Boolean) {
        if (logJobDetails) {
            val statusIcon = if (success) "✓" else "✗"
            val message = buildString {
                append("$statusIcon Job ${if (success) "Completed" else "Failed"}: ${job.id}")
                append("\n  Name: ${job.name}")
                append("\n  State: ${job.state}")
                append("\n  Success: $success")
            }
            writeLog(if (success) "INFO" else "ERROR", message)
        }
        
        if (logPerformance) {
            val startTime = synchronized(jobStartTimes) {
                jobStartTimes.remove(job.id)
            }
            
            if (startTime != null) {
                val duration = System.currentTimeMillis() - startTime
                val durationSeconds = duration / 1000.0
                
                // Track statistics
                synchronized(performanceStats) {
                    performanceStats.getOrPut("processing_times") { mutableListOf() }.add(duration)
                }
                
                writeLog("INFO", "⏱ Performance Metrics for job ${job.id}:")
                writeLog("INFO", "  Processing time: ${durationSeconds}s (${duration}ms)")
                writeLog("INFO", "  Document size: ${formatBytes(job.size)}")
                
                if (job.size > 0) {
                    val throughput = job.size / durationSeconds
                    writeLog("INFO", "  Throughput: ${formatBytes(throughput.toLong())}/s")
                }
                
                // Log aggregate statistics every 10 jobs
                val processingTimes = synchronized(performanceStats) {
                    performanceStats["processing_times"]?.toList() ?: emptyList()
                }
                
                if (processingTimes.size % 10 == 0 && processingTimes.isNotEmpty()) {
                    val avgTime = processingTimes.average()
                    val minTime = processingTimes.minOrNull() ?: 0
                    val maxTime = processingTimes.maxOrNull() ?: 0
                    
                    writeLog("INFO", "📊 Aggregate Performance Statistics (last ${processingTimes.size} jobs):")
                    writeLog("INFO", "  Average: ${avgTime / 1000.0}s")
                    writeLog("INFO", "  Min: ${minTime / 1000.0}s")
                    writeLog("INFO", "  Max: ${maxTime / 1000.0}s")
                }
            }
        }
    }
    
    override suspend fun processJob(job: PrintJob, documentBytes: ByteArray): JobProcessingResult? {
        // This plugin doesn't modify jobs, just logs them
        return null
    }
    
    override fun getConfigurationSchema(): PluginConfigurationSchema {
        return PluginConfigurationSchema(
            fields = listOf(
                ConfigurationField(
                    key = "log_level",
                    label = "Log Level",
                    type = FieldType.SELECT,
                    defaultValue = "DEBUG",
                    description = "Minimum log level to capture",
                    options = listOf("VERBOSE", "DEBUG", "INFO", "WARN", "ERROR")
                ),
                ConfigurationField(
                    key = "log_to_file",
                    label = "Log to File",
                    type = FieldType.BOOLEAN,
                    defaultValue = true,
                    description = "Save logs to file for later analysis (stored in app cache)"
                ),
                ConfigurationField(
                    key = "log_job_details",
                    label = "Log Job Details",
                    type = FieldType.BOOLEAN,
                    defaultValue = true,
                    description = "Log detailed information about each job"
                ),
                ConfigurationField(
                    key = "log_performance",
                    label = "Log Performance Metrics",
                    type = FieldType.BOOLEAN,
                    defaultValue = false,
                    description = "Log performance timing, throughput, and statistics"
                )
            )
        )
    }
    
    override suspend fun updateConfiguration(config: Map<String, Any>): Boolean {
        val oldLogToFile = logToFile
        
        logLevel = config["log_level"] as? String ?: "DEBUG"
        logToFile = config["log_to_file"] as? Boolean ?: true
        logJobDetails = config["log_job_details"] as? Boolean ?: true
        logPerformance = config["log_performance"] as? Boolean ?: false
        
        // Reinitialize log file if setting changed
        if (logToFile != oldLogToFile) {
            if (logToFile) {
                // Will be initialized on next write
                writeLog("INFO", "File logging enabled")
            } else {
                writeLog("INFO", "File logging disabled")
                closeLogFile()
            }
        }
        
        writeLog("DEBUG", "Configuration updated: level=$logLevel, toFile=$logToFile, jobDetails=$logJobDetails, performance=$logPerformance")
        
        return true
    }
    
    /**
     * Initialize log file in app's cache directory
     */
    private fun initializeLogFile(context: Context) {
        try {
            synchronized(logLock) {
                // Close existing file if open
                logWriter?.close()
                
                // Create log file in cache directory
                val cacheDir = context.cacheDir
                logFile = java.io.File(cacheDir, LOG_FILE_NAME)
                
                // Check if rotation is needed
                if (logFile!!.exists() && logFile!!.length() > MAX_LOG_FILE_SIZE) {
                    rotateLogFile()
                }
                
                // Open writer in append mode
                logWriter = java.io.BufferedWriter(
                    java.io.FileWriter(logFile, true)
                )
                
                Log.d(TAG, "Log file initialized: ${logFile!!.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize log file", e)
            logWriter = null
            logFile = null
        }
    }
    
    /**
     * Rotate log file when it gets too large
     */
    private fun rotateLogFile() {
        try {
            val oldFile = java.io.File(logFile!!.parent, "$LOG_FILE_NAME.old")
            if (oldFile.exists()) {
                oldFile.delete()
            }
            logFile!!.renameTo(oldFile)
            Log.d(TAG, "Log file rotated")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate log file", e)
        }
    }
    
    /**
     * Write log entry to both Android log and file
     */
    private fun writeLog(level: String, message: String) {
        // Write to Android log
        when (level) {
            "VERBOSE" -> Log.v(TAG, message)
            "DEBUG" -> Log.d(TAG, message)
            "INFO" -> Log.i(TAG, message)
            "WARN" -> Log.w(TAG, message)
            "ERROR" -> Log.e(TAG, message)
        }
        
        // Write to file if enabled
        if (logToFile) {
            try {
                synchronized(logLock) {
                    val timestamp = java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss.SSS",
                        java.util.Locale.US
                    ).format(java.util.Date())
                    
                    val logEntry = "[$timestamp] [$level] $message\n"
                    
                    logWriter?.write(logEntry)
                    logWriter?.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write to log file", e)
            }
        }
    }
    
    /**
     * Close log file writer
     */
    private fun closeLogFile() {
        try {
            synchronized(logLock) {
                logWriter?.flush()
                logWriter?.close()
                logWriter = null
                logFile = null
            }
            Log.d(TAG, "Log file closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing log file", e)
        }
    }
    
    /**
     * Format bytes to human-readable string
     */
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}