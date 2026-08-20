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
import android.util.Log
import com.hp.jipp.encoding.Attribute
import com.hp.jipp.encoding.AttributeGroup
import com.hp.jipp.encoding.BooleanType
import com.hp.jipp.encoding.CollectionType
import com.hp.jipp.encoding.EnumType
import com.hp.jipp.encoding.IntOrIntRange
import com.hp.jipp.encoding.IntOrIntRangeType
import com.hp.jipp.encoding.IntType
import com.hp.jipp.encoding.IppPacket
import com.hp.jipp.encoding.KeywordOrName
import com.hp.jipp.encoding.Resolution
import com.hp.jipp.encoding.ResolutionType
import com.hp.jipp.encoding.StringType
import com.hp.jipp.encoding.Tag
import com.hp.jipp.encoding.UntypedCollection
import com.hp.jipp.encoding.UriType
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

object IppAttributesUtils {
    private const val TAG = "IppAttributesUtils"
    private const val CUSTOM_ATTRIBUTES_DIR = "ipp_attributes"

    /**
     * Converts a list of AttributeGroups into a structured JSON string matching the RFC 8011
     * schema.
     */
    fun ippAttributesToJson(attributes: List<AttributeGroup>): String {
        val root = JSONObject()
        for (group in attributes) {
            val groupKey =
                when (group.tag) {
                    Tag.operationAttributes -> "operation-attributes"
                    Tag.jobAttributes -> "job-attributes"
                    Tag.printerAttributes -> "printer-attributes"
                    else -> continue
                }
            val groupJson =
                root.optJSONObject(groupKey) ?: JSONObject().also { root.put(groupKey, it) }
            for (attr in group) {
                val attrObj = JSONObject()
                val typeName = getAttributeTypeName(attr)
                attrObj.put("type", typeName)
                if (attr.size > 1) {
                    val list = (0 until attr.size).map { extractString(attr[it]) }
                    attrObj.put("value", JSONArray(list))
                } else {
                    val value = attr.getValue()
                    when (value) {
                        is Boolean,
                        is Number -> attrObj.put("value", value)
                        else -> attrObj.put("value", extractString(value))
                    }
                }
                groupJson.put(attr.name, attrObj)
            }
        }
        return root.toString(2)
    }

    /**
     * Extracts a raw string from any IPP attribute value object (String, KeywordOrName, Name, Text,
     * etc.).
     */
    fun extractString(obj: Any?): String {
        if (obj == null) return ""
        if (obj is String) return obj
        if (obj is KeywordOrName) return obj.keyword ?: obj.name?.value ?: obj.toString()
        try {
            val field = obj.javaClass.getDeclaredField("value").apply { isAccessible = true }
            val v = field.get(obj)
            if (v != null) return v.toString()
        } catch (_: Exception) {}
        try {
            val field = obj.javaClass.getDeclaredField("name").apply { isAccessible = true }
            val v = field.get(obj)
            if (v != null) return extractString(v)
        } catch (_: Exception) {}
        return obj.toString()
    }

    private fun getAttributeTypeName(attr: Attribute<*>): String {
        val type = IppAttributesParser.nameToType[attr.name] ?: attr.type

        // Unify single and multi-valued StringType tags
        val stringTag = (type as? StringType)?.tag ?: (type as? StringType.Set)?.tag
        if (stringTag != null) {
            return when (stringTag) {
                Tag.nameWithoutLanguage -> "nameWithoutLanguage"
                Tag.nameWithLanguage -> "nameWithLanguage"
                Tag.textWithoutLanguage -> "textWithoutLanguage"
                Tag.textWithLanguage -> "textWithLanguage"
                Tag.mimeMediaType -> "mimeMediaType"
                Tag.charset -> "charset"
                Tag.naturalLanguage -> "naturalLanguage"
                Tag.uriScheme -> "urischeme"
                else -> "keyword"
            }
        }

        return when (type) {
            is BooleanType -> "boolean"
            is IntType -> "integer"
            is IntOrIntRangeType -> "rangeOfInteger"
            is ResolutionType -> "resolution"
            is UriType -> "uri"
            is EnumType<*> -> "enum"
            is CollectionType<*>,
            is UntypedCollection.Type,
            is UntypedCollection.SetType -> "collection"
            else ->
                when (attr.getValue()) {
                    is Boolean -> "boolean"
                    is Number -> "integer"
                    is IntOrIntRange -> "rangeOfInteger"
                    is Resolution -> "resolution"
                    is URI -> "uri"
                    else -> "keyword"
                }
        }
    }

    /** Saves IPP attributes to an output stream as JSON. */
    fun saveIppAttributes(outputStream: OutputStream, attributes: List<AttributeGroup>): Boolean {
        return try {
            val jsonString = ippAttributesToJson(attributes)
            outputStream.use { it.write(jsonString.toByteArray()) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving IPP attributes to stream", e)
            false
        }
    }

    /** Saves IPP attributes in the custom attributes directory as JSON. */
    fun saveIppAttributes(
        context: Context,
        attributes: List<AttributeGroup>,
        filename: String,
    ): Boolean {
        return try {
            val jsonString = ippAttributesToJson(attributes)
            val attributesDir =
                File(context.filesDir, CUSTOM_ATTRIBUTES_DIR).apply { if (!exists()) mkdirs() }
            val file = File(attributesDir, filename)
            FileOutputStream(file).use { it.write(jsonString.toByteArray()) }
            Log.d(TAG, "Saved IPP attributes as JSON to: ${file.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving IPP attributes as JSON", e)
            false
        }
    }

    /** Loads IPP attributes from a JSON file using IppAttributesParser. */
    fun loadIppAttributes(file: File): List<AttributeGroup>? {
        if (!file.exists()) {
            Log.e(TAG, "IPP attributes file not found: ${file.absolutePath}")
            return null
        }
        return try {
            val content = file.readText().trim()
            val attributeGroups = mutableListOf<AttributeGroup>()

            val root = JSONObject(content)
            val response = root.optJSONObject("response") ?: root

            // 1. Operation attributes if present
            response.optJSONObject("operation-attributes")?.let { opJson ->
                val attrs = IppAttributesParser.parse(opJson)
                if (attrs.isNotEmpty()) {
                    attributeGroups.add(
                        AttributeGroup.groupOf(Tag.operationAttributes, *attrs.toTypedArray())
                    )
                }
            }

            // 2. Printer attributes: use "printer-attributes" object if present, or fall back
            // to using the root object directly if no section headers exist (flat attributes file).
            val prJson =
                response.optJSONObject("printer-attributes")
                    ?: if (!response.has("operation-attributes")) response else null
            prJson?.let {
                val attrs = IppAttributesParser.parse(it)
                if (attrs.isNotEmpty()) {
                    attributeGroups.add(
                        AttributeGroup.groupOf(Tag.printerAttributes, *attrs.toTypedArray())
                    )
                }
            }

            Log.d(
                TAG,
                "Loaded ${attributeGroups.size} IPP attribute groups from: ${file.absolutePath}",
            )
            if (attributeGroups.isNotEmpty()) attributeGroups else null
        } catch (e: Exception) {
            Log.e(TAG, "Error loading IPP attributes from ${file.absolutePath}", e)
            null
        }
    }

    /**
     * Loads IPP attributes by filename, checking custom_attributes directory and root files
     * directory.
     */
    fun loadIppAttributes(context: Context, filename: String): List<AttributeGroup>? {
        val customFile = File(File(context.filesDir, CUSTOM_ATTRIBUTES_DIR), filename)
        if (customFile.exists()) {
            return loadIppAttributes(customFile)
        }
        val directFile = File(context.filesDir, filename)
        if (directFile.exists()) {
            return loadIppAttributes(directFile)
        }
        Log.e(
            TAG,
            "IPP attributes file not found: $filename in $CUSTOM_ATTRIBUTES_DIR or ${context.filesDir}",
        )
        return null
    }

    /**
     * Overlays custom attributes onto a base IPP packet. Attributes in `overrides` overwrite
     * matching attributes (by name) in the `basePacket` within the same tag group.
     */
    fun overlayAttributes(basePacket: IppPacket, overrides: List<AttributeGroup>): IppPacket {
        val mergedGroups = mutableListOf<AttributeGroup>()
        val allTags =
            (basePacket.attributeGroups.map { it.tag } + overrides.map { it.tag }).distinct()

        for (tag in allTags) {
            val baseGroup = basePacket.attributeGroups.find { it.tag == tag }
            val overrideGroup = overrides.find { it.tag == tag }

            if (baseGroup != null && overrideGroup != null) {
                val mergedAttributes = (baseGroup + overrideGroup).associateBy { it.name }.values
                mergedGroups.add(AttributeGroup.groupOf(tag, *mergedAttributes.toTypedArray()))
            } else if (overrideGroup != null) {
                mergedGroups.add(overrideGroup)
            } else if (baseGroup != null) {
                mergedGroups.add(baseGroup)
            }
        }

        return IppPacket(basePacket.status, basePacket.requestId, *mergedGroups.toTypedArray())
    }

    /** Creates an attribute from name, value and type using IppAttributesParser. */
    fun createAttribute(name: String, value: Any, type: String): Attribute<*>? {
        return try {
            val json =
                JSONObject().apply {
                    put("type", type)
                    put("value", value)
                }
            IppAttributesParser.parseExplicitTypedAttribute(name, json)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating attribute: $name", e)
            null
        }
    }

    /** Gets list of available IPP attribute files in custom attributes directory. */
    fun getAvailableIppAttributeFiles(context: Context): List<String> {
        val attributesDir = File(context.filesDir, CUSTOM_ATTRIBUTES_DIR)
        if (!attributesDir.exists()) {
            return emptyList()
        }
        return attributesDir.listFiles()?.map { it.name } ?: emptyList()
    }

    /** Deletes an IPP attributes file. */
    fun deleteIppAttributes(context: Context, filename: String): Boolean {
        return try {
            val file = File(File(context.filesDir, CUSTOM_ATTRIBUTES_DIR), filename)
            if (file.exists()) file.delete() else false
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting IPP attributes file", e)
            false
        }
    }

    /** Validates IPP attributes using IppAttributesParser validation. */
    fun validateIppAttributes(attributes: List<AttributeGroup>?, strict: Boolean = false): Boolean {
        if (attributes.isNullOrEmpty()) {
            Log.w(TAG, "Validation failed: No attributes provided")
            return false
        }
        val allAttributes = attributes.flatMap { it.toList() }
        val errors = IppAttributesParser.validate(allAttributes)
        for (err in errors) {
            Log.w(TAG, "IPP attribute validation warning: $err")
        }
        return if (strict) errors.isEmpty() else true
    }
}
