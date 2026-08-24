/*
 * Copyright 2026 The Virtual Printer Authors
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
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreferenceUtilsTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        // Mock android.util.Log to avoid 'Method ... not mocked' in local JVM tests
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0

        filesDir = tempFolder.newFolder("files")
        mockContext = mockk()
        mockPrefs = mockk()
        mockEditor = mockk(relaxed = true)

        every { mockContext.filesDir } returns filesDir
        every {
            mockContext.getSharedPreferences("printer_preferences", Context.MODE_PRIVATE)
        } returns mockPrefs
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun getCustomPrinterName_returnsSharedPreferencesValue_whenSet() {
        every { mockPrefs.getString("printer_name", null) } returns "UI Printer Name"

        val printerName = PreferenceUtils.getCustomPrinterName(mockContext)

        assertThat(printerName).isEqualTo("UI Printer Name")
    }

    @Test
    fun getCustomPrinterName_returnsConfigFileValue_whenSharedPreferencesNotSet() {
        every { mockPrefs.getString("printer_name", null) } returns null

        val configFile = File(filesDir, PreferenceUtils.CONFIG_FILE_NAME)
        configFile.writeText(
            """
            {
                "printer_name": "Config File Printer"
            }
            """
                .trimIndent()
        )

        val printerName = PreferenceUtils.getCustomPrinterName(mockContext)

        assertThat(printerName).isEqualTo("Config File Printer")
    }

    @Test
    fun getCustomPrinterName_returnsDefault_whenNeitherPrefsNorConfigSet() {
        every { mockPrefs.getString("printer_name", null) } returns null

        val printerName = PreferenceUtils.getCustomPrinterName(mockContext)

        assertThat(printerName).isEqualTo("Android Virtual Printer")
    }

    @Test
    fun getPrinterUuid_returnsConfigFileValue_whenConfigExists() {
        val configFile = File(filesDir, PreferenceUtils.CONFIG_FILE_NAME)
        configFile.writeText(
            """
            {
                "printer_uuid": "11111111-2222-3333-4444-555555555555"
            }
            """
                .trimIndent()
        )

        val uuid = PreferenceUtils.getPrinterUuid(mockContext)

        assertThat(uuid).isEqualTo("11111111-2222-3333-4444-555555555555")
    }

    @Test
    fun getPrinterUuid_returnsSharedPreferencesValue_whenConfigMissing() {
        every { mockPrefs.getString("printer_uuid", null) } returns "saved-uuid-1234"

        val uuid = PreferenceUtils.getPrinterUuid(mockContext)

        assertThat(uuid).isEqualTo("saved-uuid-1234")
    }

    @Test
    fun getPrinterUuid_generatesAndSavesNewUuid_whenNeitherConfigNorPrefsSet() {
        every { mockPrefs.getString("printer_uuid", null) } returns null

        val uuid = PreferenceUtils.getPrinterUuid(mockContext)

        assertThat(uuid).isNotEmpty()
        verify { mockEditor.putString("printer_uuid", uuid) }
        verify { mockEditor.apply() }
    }

    @Test
    fun getSupportedFormats_returnsFormatsFromConfigFile() {
        val configFile = File(filesDir, PreferenceUtils.CONFIG_FILE_NAME)
        configFile.writeText(
            """
            {
                "supported_formats": [
                    "application/pdf",
                    "image/png"
                ]
            }
            """
                .trimIndent()
        )

        val formats = PreferenceUtils.getSupportedFormats(mockContext)

        assertThat(formats).containsExactly("application/pdf", "image/png").inOrder()
    }

    @Test
    fun getSupportedFormats_returnsDefault_whenConfigMissing() {
        val formats = PreferenceUtils.getSupportedFormats(mockContext)

        assertThat(formats)
            .containsExactly(
                "application/pdf",
                "image/pwg-raster",
                "application/PCLm",
                "image/jpeg",
            )
            .inOrder()
    }

    @Test
    fun getCompressionSupported_returnsCompressionFromConfigFile() {
        val configFile = File(filesDir, PreferenceUtils.CONFIG_FILE_NAME)
        configFile.writeText(
            """
            {
                "compression_supported": [
                    "none",
                    "gzip"
                ]
            }
            """
                .trimIndent()
        )

        val compressions = PreferenceUtils.getCompressionSupported(mockContext)

        assertThat(compressions).containsExactly("none", "gzip").inOrder()
    }

    @Test
    fun getCompressionSupported_returnsDefault_whenConfigMissing() {
        val compressions = PreferenceUtils.getCompressionSupported(mockContext)

        assertThat(compressions).containsExactly("none")
    }

    @Test
    fun saveCustomPrinterName_savesToSharedPreferences() {
        PreferenceUtils.saveCustomPrinterName(mockContext, "New Printer Name")

        verify { mockEditor.putString("printer_name", "New Printer Name") }
        verify { mockEditor.apply() }
    }
}
