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

import com.google.common.truth.Truth.assertThat
import com.hp.jipp.encoding.AttributeGroup
import com.hp.jipp.encoding.IppPacket
import com.hp.jipp.encoding.Tag
import com.hp.jipp.model.Status
import com.hp.jipp.model.Types
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IppAttributesUtilsTest {

    @get:Rule val tempFolder = TemporaryFolder()

    @Test
    fun loadIppAttributes_fromFile_parsesPrinterAndOperationAttributes() {
        val file = tempFolder.newFile("test_config.json")
        file.writeText(
            """
            {
                "operation-attributes": {
                    "attributes-charset": {
                        "type": "charset",
                        "value": "utf-8"
                    }
                },
                "printer-attributes": {
                    "color-supported": {
                        "type": "boolean",
                        "value": true
                    }
                }
            }
            """
                .trimIndent()
        )

        val groups = IppAttributesUtils.loadIppAttributes(file)
        assertThat(groups).isNotNull()
        assertThat(groups).hasSize(2)

        val opGroup = groups!!.find { it.tag == Tag.operationAttributes }
        assertThat(opGroup).isNotNull()
        assertThat(opGroup!!["attributes-charset"]?.getValue()).isEqualTo("utf-8")

        val prGroup = groups.find { it.tag == Tag.printerAttributes }
        assertThat(prGroup).isNotNull()
        assertThat(prGroup!!["color-supported"]?.getValue()).isEqualTo(true)
    }

    @Test
    fun loadIppAttributes_missingFile_returnsNull() {
        val file = File(tempFolder.root, "non_existent.json")
        val groups = IppAttributesUtils.loadIppAttributes(file)
        assertThat(groups).isNull()
    }

    @Test
    fun overlayAttributes_overwritesMatchingAttributesAndPreservesOthers() {
        val basePacket =
            IppPacket(
                Status.successfulOk,
                1,
                AttributeGroup.groupOf(
                    Tag.operationAttributes,
                    Types.attributesCharset.of("utf-8"),
                    Types.attributesNaturalLanguage.of("en"),
                ),
                AttributeGroup.groupOf(
                    Tag.printerAttributes,
                    Types.printerName.of("Default Printer"),
                    Types.colorSupported.of(false),
                    Types.queuedJobCount.of(0),
                ),
            )

        val overrides =
            listOf(
                AttributeGroup.groupOf(
                    Tag.printerAttributes,
                    Types.printerName.of("Custom Printer"),
                    Types.colorSupported.of(true),
                )
            )

        val merged = IppAttributesUtils.overlayAttributes(basePacket, overrides)
        val prGroup = merged.attributeGroups.find { it.tag == Tag.printerAttributes }
        assertThat(prGroup).isNotNull()

        // Overridden values
        val printerName = IppAttributesUtils.extractString(prGroup!![Types.printerName]?.getValue())
        assertThat(printerName).isEqualTo("Custom Printer")
        assertThat(prGroup[Types.colorSupported]?.getValue()).isEqualTo(true)

        // Preserved baseline values
        assertThat(prGroup[Types.queuedJobCount]?.getValue()).isEqualTo(0)

        // Preserved operation attributes
        val opGroup = merged.attributeGroups.find { it.tag == Tag.operationAttributes }
        assertThat(opGroup).isNotNull()
        val charset =
            IppAttributesUtils.extractString(opGroup!![Types.attributesCharset]?.getValue())
        assertThat(charset).isEqualTo("utf-8")
    }

    @Test
    fun ippAttributesToJson_roundTripsWithParser() {
        val groups =
            listOf(
                AttributeGroup.groupOf(
                    Tag.printerAttributes,
                    Types.printerName.of("Test Virtual Printer"),
                    Types.colorSupported.of(true),
                    Types.documentFormatSupported.of("application/pdf", "image/pwg-raster"),
                )
            )

        val jsonString = IppAttributesUtils.ippAttributesToJson(groups)
        val root = JSONObject(jsonString)
        val prJson = root.getJSONObject("printer-attributes")

        val parsedAttributes = IppAttributesParser.parse(prJson)
        val attrMap = parsedAttributes.associateBy { it.name }

        val prName = IppAttributesUtils.extractString(attrMap[Types.printerName.name]?.getValue())
        assertThat(prName).isEqualTo("Test Virtual Printer")
        assertThat(attrMap[Types.colorSupported.name]?.getValue()).isEqualTo(true)

        val docFormats = attrMap[Types.documentFormatSupported.name]?.map {
            IppAttributesUtils.extractString(it)
        }
        assertThat(docFormats).containsExactly("application/pdf", "image/pwg-raster")
    }

    @Test
    fun saveIppAttributes_toOutputStream_writesValidJson() {
        val groups =
            listOf(AttributeGroup.groupOf(Tag.printerAttributes, Types.colorSupported.of(true)))

        val outputStream = ByteArrayOutputStream()
        val success = IppAttributesUtils.saveIppAttributes(outputStream, groups)
        assertThat(success).isTrue()

        val jsonString = outputStream.toString()
        assertThat(jsonString).contains("\"printer-attributes\"")
        assertThat(jsonString).contains("\"color-supported\"")
    }

    @Test
    fun createAttribute_createsNativeJippAttributes() {
        val intAttr = IppAttributesUtils.createAttribute("test-int", 42, "INTEGER")
        assertThat(intAttr).isNotNull()
        assertThat(intAttr!!.name).isEqualTo("test-int")
        assertThat(intAttr.getValue()).isEqualTo(42)

        val boolAttr = IppAttributesUtils.createAttribute("test-bool", true, "BOOLEAN")
        assertThat(boolAttr).isNotNull()
        assertThat(boolAttr!!.name).isEqualTo("test-bool")
        assertThat(boolAttr.getValue()).isEqualTo(true)

        val strAttr = IppAttributesUtils.createAttribute("test-str", "hello", "STRING")
        assertThat(strAttr).isNotNull()
        assertThat(strAttr!!.name).isEqualTo("test-str")
        assertThat(strAttr.getValue()).isEqualTo("hello")

        val rangeAttr =
            IppAttributesUtils.createAttribute("copies-supported", "(1:100)", "rangeOfInteger")
        assertThat(rangeAttr).isNotNull()
        assertThat(rangeAttr!!.name).isEqualTo("copies-supported")

        val resAttr =
            IppAttributesUtils.createAttribute(
                "printer-resolution-default",
                "600x600dpi",
                "resolution",
            )
        assertThat(resAttr).isNotNull()
        assertThat(resAttr!!.name).isEqualTo("printer-resolution-default")

        val mimeAttr =
            IppAttributesUtils.createAttribute(
                "document-format-supported",
                "application/pdf",
                "mimeMediaType",
            )
        assertThat(mimeAttr).isNotNull()
        assertThat(mimeAttr!!.name).isEqualTo("document-format-supported")
    }
}
