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

/**
 * Utility class for managing app preferences
 */
object PreferenceUtils {
    private const val TAG = "PreferenceUtils"
    private const val PREFS_NAME = "printer_preferences"
    private const val KEY_PRINTER_NAME = "printer_name"
    private const val DEFAULT_PRINTER_NAME = "Android Virtual Printer"
    
    private const val CONFIG_FILE_NAME = "printer_config.json"
    private const val KEY_CONFIG_PRINTER_NAME = "printer_name"

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
        val configName = getNameFromConfigFile(context)
        if (configName != null) {
            return configName
        }

        // 3. Fallback to default
        return DEFAULT_PRINTER_NAME
    }

    /**
     * Reads the printer name from a configuration file if it exists.
     */
    private fun getNameFromConfigFile(context: Context): String? {
        val configFile = File(context.filesDir, CONFIG_FILE_NAME)
        if (configFile.exists()) {
            Log.d(TAG, "Reading config from ${configFile.absolutePath}")
            try {
                val content = configFile.readText()
                val json = JSONObject(content)
                if (json.has(KEY_CONFIG_PRINTER_NAME)) {
                    val name = json.getString(KEY_CONFIG_PRINTER_NAME)
                    if (name.isNotEmpty()) {
                      Log.d(TAG, "Printer name: $name")
                      return name
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading config file ${configFile.absolutePath}", e)
            }
        }
        return null
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
