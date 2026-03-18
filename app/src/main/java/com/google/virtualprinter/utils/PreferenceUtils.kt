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

package com.google.virtualprinter.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Utility class for managing app preferences
 */
object PreferenceUtils {
    private const val TAG = "PreferenceUtils"
    private const val PREFS_NAME = "printer_preferences"
    private const val KEY_PRINTER_NAME = "printer_name"
    private const val KEY_PRINTER_UUID = "printer_uuid"
    private const val DEFAULT_PRINTER_NAME = "Android Virtual Printer"
    
    private const val CONFIG_FILE_NAME = "printer_config.json"
    private const val KEY_CONFIG_PRINTER_NAME = "printer_name"
    private const val KEY_CONFIG_PRINTER_UUID = "printer_uuid"
    private const val KEY_CONFIG_SUPPORTED_FORMATS = "supported_formats"
    private const val KEY_CONFIG_COMPRESSION_SUPPORTED = "compression_supported"

    private val DEFAULT_SUPPORTED_FORMATS = listOf(
        "application/pdf",
        "image/pwg-raster",
        "application/PCLm",
        "image/jpeg",
    )
    private val DEFAULT_COMPRESSION_SUPPORTED = listOf("none")

    /**
     * Gets the user-defined printer name or the default name if not set
     * Checks in this order:
     * 1. SharedPreferences (user-set in UI)
     * 2. Configuration file (installed on device)
     * 3. Default constant
     */
    fun getCustomPrinterName(context: Context): String {
        val prefs = getPreferences(context)

        // 1. Check SharedPreferences first (user preference in UI)
        val savedName = prefs.getString(KEY_PRINTER_NAME, null)
        if (savedName != null && savedName.isNotEmpty()) {
            return savedName
        }

        // 2. Check for configuration file
        val configName = getStringFromConfigFile(context, KEY_CONFIG_PRINTER_NAME)
        if (configName != null) {
            return configName
        }

        // 3. Fallback to default
        return DEFAULT_PRINTER_NAME
    }

    /**
     * Gets the printer UUID.
     * Checks in this order:
     * 1. Configuration file (installed on device)
     * 2. SharedPreferences (persists across app restarts)
     * 3. Generates a new UUID and saves it to SharedPreferences
     */
    fun getPrinterUuid(context: Context): String {
        // 1. Check for configuration file first
        val configUuid = getStringFromConfigFile(context, KEY_CONFIG_PRINTER_UUID)
        if (configUuid != null) {
            return configUuid
        }

        val prefs = getPreferences(context)

        // 2. Check SharedPreferences
        val savedUuid = prefs.getString(KEY_PRINTER_UUID, null)
        if (!savedUuid.isNullOrEmpty()) {
            return savedUuid
        }

        // 3. Generate a new UUID and save it
        val newUuid = UUID.randomUUID().toString()
        Log.d(TAG, "Generated new printer UUID: $newUuid")
        prefs.edit().putString(KEY_PRINTER_UUID, newUuid).apply()
        return newUuid
    }

    /**
     * Get the given 'key' from the config file, returning null if the key or file doesn't exist.
     */
    private fun getStringFromConfigFile(context: Context, key: String): String? {
        val configFile = File(context.filesDir, CONFIG_FILE_NAME)
        if (configFile.exists()) {
            Log.d(TAG, "Reading config from ${configFile.absolutePath}")
            try {
                val content = configFile.readText()
                val json = JSONObject(content)
                if (json.has(key)) {
                    val value = json.getString(key)
                    if (value.isNotEmpty()) {
                      Log.d(TAG, "$key: $value")
                      return value
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading config file ${configFile.absolutePath}", e)
            }
        }
        return null
    }

    /**
     * Get the given 'key' from the config file, returning 'default' if the key or file doesn't
     *  exist or if the list is empty.
     */
    private fun getStringListFromConfigFile(context: Context,
                                            key: String,
                                            default: List<String>): List<String> {
        val configFile = File(context.filesDir, CONFIG_FILE_NAME)
        if (configFile.exists()) {
            try {
                val content = configFile.readText()
                val json = JSONObject(content)
                if (json.has(key)) {
                    val valuesArray = json.getJSONArray(KEY_CONFIG_SUPPORTED_FORMATS)
                    val values = mutableListOf<String>()
                    for (i in 0 until valuesArray.length()) {
                        values.add(valuesArray.getString(i))
                    }
                    if (values.isNotEmpty()) {
                      Log.d(TAG, "$key: $values")
                      return values
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading config file ${configFile.absolutePath}", e)
            }
        }
        return default
    }

    /**
     * Gets the list of supported formats for the printer.
     * Checks the configuration file first, then falls back to defaults.
     */
    fun getSupportedFormats(context: Context): List<String> {
        return getStringListFromConfigFile(
            context, KEY_CONFIG_SUPPORTED_FORMATS, DEFAULT_SUPPORTED_FORMATS)
    }

    /**
     * Return the default document format (the first value from supported formats, or a hard-coded
     * default value).
     */
    fun getDefaultFormat(context: Context): String {
      return getSupportedFormats(context).firstOrNull() ?: "application/pdf"
    }

    /**
     * Gets the list of supported compression algorithms.
     * Checks the configuration file first, then falls back to defaults.
     */
    fun getCompressionSupported(context: Context): List<String> {
        return getStringListFromConfigFile(
            context, KEY_CONFIG_COMPRESSION_SUPPORTED, DEFAULT_COMPRESSION_SUPPORTED)
    }
    
    /**
     * Saves a custom printer name
     */
    fun saveCustomPrinterName(context: Context, name: String) {
        val prefs = getPreferences(context)
        prefs.edit().putString(KEY_PRINTER_NAME, name).apply()
    }
    
    /**
     * Gets the shared preferences instance
     */
    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
} 
