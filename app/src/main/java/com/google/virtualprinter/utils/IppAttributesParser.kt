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

import android.util.Log
import com.hp.jipp.cups.Cups
import com.hp.jipp.encoding.Attribute
import com.hp.jipp.encoding.AttributeCollection
import com.hp.jipp.encoding.AttributeType
import com.hp.jipp.encoding.BooleanType
import com.hp.jipp.encoding.CollectionType
import com.hp.jipp.encoding.EnumType
import com.hp.jipp.encoding.IntOrIntRange
import com.hp.jipp.encoding.IntOrIntRangeType
import com.hp.jipp.encoding.IntType
import com.hp.jipp.encoding.KeywordOrName
import com.hp.jipp.encoding.Resolution
import com.hp.jipp.encoding.ResolutionType
import com.hp.jipp.encoding.ResolutionUnit
import com.hp.jipp.encoding.StringType
import com.hp.jipp.encoding.Tag
import com.hp.jipp.encoding.UntypedCollection
import com.hp.jipp.encoding.UntypedEnum
import com.hp.jipp.encoding.UriType
import com.hp.jipp.model.MediaColDatabase
import com.hp.jipp.model.Types
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/**
 * Generic parser for converting JSON IPP printer attributes into typed JIPP Attribute instances.
 * Supports all RFC 8011 attribute syntaxes via inferred JSON primitives and explicit typed objects.
 */
object IppAttributesParser {
    private const val TAG = "IppAttributesParser"

    /** Map of all standard IANA IPP and CUPS attribute types indexed by attribute name. */
    internal val nameToType: Map<String, AttributeType<*>> by lazy {
        val map = mutableMapOf<String, AttributeType<*>>()
        for (field in Types::class.java.fields) {
            try {
                val value = field.get(Types)
                if (value is AttributeType<*>) {
                    map[value.name] = value
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading field ${field.name}: ${e.message}", e)
            }
        }
        for (field in Cups.Types::class.java.fields) {
            try {
                val value = field.get(Cups.Types)
                if (value is AttributeType<*>) {
                    map[value.name] = value
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading field ${field.name}: ${e.message}", e)
            }
        }
        map
    }

    /**
     * Parses a JSON object containing key-value attribute definitions into a list of JIPP
     * Attributes.
     */
    fun parse(attributesJson: JSONObject): List<Attribute<*>> {
        val result = mutableListOf<Attribute<*>>()
        for (key in attributesJson.keys()) {
            val attr = parseAttribute(key, attributesJson.get(key))
            if (attr != null) {
                result.add(attr)
            }
        }
        return result
    }

    /**
     * Validates a list of attributes against standard JIPP and CUPS specifications. Returns a list
     * of error descriptions for any attributes that have a mismatched syntax.
     */
    fun validate(attributes: List<Attribute<*>>): List<String> {
        val errors = mutableListOf<String>()
        validateInternal(attributes, errors)
        return errors
    }

    private fun validateInternal(attributes: List<Attribute<*>>, errors: MutableList<String>) {
        for (attr in attributes) {
            val expected = nameToType[attr.name]

            if (expected != null) {
                val actual = attr.type

                when {
                    expected is BooleanType && actual !is BooleanType -> {
                        errors.add(
                            "Attribute '${attr.name}' expected boolean (true/false), but was provided as ${actual::class.simpleName ?: "non-boolean"}."
                        )
                    }
                    expected is IntType && actual !is IntType && actual !is EnumType<*> -> {
                        errors.add(
                            "Attribute '${attr.name}' expected integer, but was provided as ${actual::class.simpleName}."
                        )
                    }
                    expected is IntOrIntRangeType &&
                        actual !is IntOrIntRangeType &&
                        actual !is IntType -> {
                        errors.add(
                            "Attribute '${attr.name}' expected integer or range, but was provided as ${actual::class.simpleName}."
                        )
                    }
                    expected is ResolutionType && actual !is ResolutionType -> {
                        errors.add(
                            "Attribute '${attr.name}' expected resolution (e.g. '600x600dpi'), but was provided as ${actual::class.simpleName}."
                        )
                    }
                    expected is UriType && actual !is UriType -> {
                        errors.add(
                            "Attribute '${attr.name}' expected URI (e.g. 'ipp://...'), but was provided as ${actual::class.simpleName}."
                        )
                    }
                    expected is CollectionType<*> && actual !is CollectionType<*> -> {
                        errors.add(
                            "Attribute '${attr.name}' expected collection object, but was provided as ${actual::class.simpleName}."
                        )
                    }
                }
            }

            // Recurse into collections to validate their members
            for (value in attr) {
                if (value is AttributeCollection) {
                    validateInternal(value.attributes, errors)
                }
            }
        }
    }

    /** Parses a single attribute entry from its name and raw JSON value. */
    private fun parseAttribute(name: String, rawValue: Any): Attribute<*>? {
        return try {
            // This needs to be an explicitly typed object: { "type": "...", "value": ... }
            if (rawValue is JSONObject && rawValue.has("type")) {
                parseExplicitTypedAttribute(name, rawValue)
            } else {
                Log.w(
                    TAG,
                    "Unsupported format for attribute '$name'. Explicit type required: { \"type\": \"...\", \"value\": ... }",
                )
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse attribute $name: ${e.message}", e)
            null
        }
    }

    internal fun parseExplicitTypedAttribute(name: String, obj: JSONObject): Attribute<*>? {
        val type = obj.optString("type", "").lowercase()
        val value = obj.opt("value") ?: JSONObject.NULL

        return when (type) {
            "keyword" -> {
                val list = toStringList(value)
                when {
                    list.isEmpty() -> null
                    list.size == 1 -> StringType(Tag.keyword, name).of(list[0])
                    else -> StringType.Set(Tag.keyword, name).of(list)
                }
            }
            "string",
            "name",
            "namewithoutlanguage" -> {
                val list = toStringList(value)
                when {
                    list.isEmpty() -> null
                    list.size == 1 -> StringType(Tag.nameWithoutLanguage, name).of(list[0])
                    else -> StringType.Set(Tag.nameWithoutLanguage, name).of(list)
                }
            }
            "text",
            "textwithoutlanguage" -> {
                val list = toStringList(value)
                when {
                    list.isEmpty() -> null
                    list.size == 1 -> StringType(Tag.textWithoutLanguage, name).of(list[0])
                    else -> StringType.Set(Tag.textWithoutLanguage, name).of(list)
                }
            }
            "mimemediatype" -> {
                val list = toStringList(value)
                when {
                    list.isEmpty() -> null
                    list.size == 1 -> StringType(Tag.mimeMediaType, name).of(list[0])
                    else -> StringType.Set(Tag.mimeMediaType, name).of(list)
                }
            }
            "charset" -> {
                val list = toStringList(value)
                when {
                    list.isEmpty() -> null
                    list.size == 1 -> StringType(Tag.charset, name).of(list[0])
                    else -> StringType.Set(Tag.charset, name).of(list)
                }
            }
            "naturallanguage" -> {
                val list = toStringList(value)
                when {
                    list.isEmpty() -> null
                    list.size == 1 -> StringType(Tag.naturalLanguage, name).of(list[0])
                    else -> StringType.Set(Tag.naturalLanguage, name).of(list)
                }
            }
            "urischeme" -> {
                val list = toStringList(value)
                when {
                    list.isEmpty() -> null
                    list.size == 1 -> StringType(Tag.uriScheme, name).of(list[0])
                    else -> StringType.Set(Tag.uriScheme, name).of(list)
                }
            }
            "uri" -> {
                val list =
                    toStringList(value).mapNotNull {
                        try {
                            URI.create(it)
                        } catch (e: Exception) {
                            null
                        }
                    }
                when {
                    list.isEmpty() -> null
                    list.size == 1 -> UriType(name).of(list[0])
                    else -> UriType.Set(name).of(list)
                }
            }
            "boolean" -> {
                when (value) {
                    is Boolean -> BooleanType(name).of(value)
                    is JSONArray -> {
                        val bools =
                            (0 until value.length()).mapNotNull { value.get(it) as? Boolean }
                        when {
                            bools.isEmpty() -> null
                            bools.size == 1 -> BooleanType(name).of(bools[0])
                            else -> BooleanType.Set(name).of(bools)
                        }
                    }
                    else -> null
                }
            }
            "integer" -> {
                val list = toIntList(value)
                when {
                    list.isEmpty() -> null
                    list.size == 1 -> IntType(name).of(list[0])
                    else -> IntType.Set(name).of(list)
                }
            }
            "enum" -> {
                val list = toIntList(value)
                when {
                    list.isEmpty() -> null
                    list.size == 1 -> EnumType(name) { UntypedEnum(it) }.of(list[0])
                    else -> EnumType.Set(name) { UntypedEnum(it) }.of(list.map { UntypedEnum(it) })
                }
            }
            "rangeofinteger" -> {
                val range = parseRange(value)
                if (range != null) IntOrIntRangeType(name).of(range) else null
            }
            "resolution" -> {
                val resolutions = parseResolutions(value)
                when {
                    resolutions.isEmpty() -> null
                    resolutions.size == 1 -> ResolutionType(name).of(resolutions[0])
                    else -> ResolutionType.Set(name).of(resolutions)
                }
            }
            "no-value" -> StringType(Tag.noValue, name).noValue()
            "unknown" -> StringType(Tag.unknown, name).unknown()
            "collection" -> {
                if (name == "media-col-database") {
                    val collections = parseMediaColDatabaseList(value)
                    if (collections.isNotEmpty()) Types.mediaColDatabase.of(collections) else null
                } else {
                    when (value) {
                        is JSONArray ->
                            UntypedCollection.SetType(name).of(parseUntypedCollectionList(value))
                        is JSONObject ->
                            UntypedCollection.Type(name).of(parseUntypedCollection(value))
                        else -> null
                    }
                }
            }
            else -> {
                Log.w(TAG, "Unsupported explicit type '$type' for attribute $name")
                null
            }
        }
    }

    private fun toStringList(value: Any): List<String> {
        return when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { value.get(it) as? String }
            is String -> listOf(value)
            else -> emptyList()
        }
    }

    private fun toIntList(value: Any): List<Int> {
        return when (value) {
            is JSONArray ->
                (0 until value.length()).mapNotNull { (value.get(it) as? Number)?.toInt() }
            is Number -> listOf(value.toInt())
            else -> emptyList()
        }
    }

    private fun parseRange(value: Any): IntOrIntRange? {
        return when (value) {
            is JSONArray -> {
                if (value.length() == 2) {
                    val min = value.optInt(0, 1)
                    val max = value.optInt(1, min)
                    IntOrIntRange(min, max)
                } else if (value.length() == 1) {
                    IntOrIntRange(value.optInt(0, 1))
                } else {
                    null
                }
            }
            is Number -> IntOrIntRange(value.toInt())
            is String -> {
                val cleaned = value.trim().removeSurrounding("(", ")").trim()

                // Though unlikely, this handles negative boundary cases like -50--10.
                val match = Regex("""^(-?\d+)\s*(:|(?:\.\.)|-)\s*(-?\d+)$""").find(cleaned)
                if (match != null) {
                    val min = match.groupValues[1].toInt()
                    val max = match.groupValues[3].toInt()
                    return IntOrIntRange(min, max)
                }

                // If it's just a single number
                val singleNum = cleaned.toIntOrNull()
                if (singleNum != null) {
                    return IntOrIntRange(singleNum)
                }
                null
            }
            else -> null
        }
    }

    private fun parseResolution(value: String): Resolution? {
        val regex = Regex("""(\d+)\s*x\s*(\d+)\s*(dpi|dpcm)""", RegexOption.IGNORE_CASE)
        val match = regex.find(value.trim()) ?: return null
        val x = match.groupValues[1].toIntOrNull() ?: return null
        val y = match.groupValues[2].toIntOrNull() ?: return null
        val unitStr = match.groupValues[3].lowercase()
        val unit =
            if (unitStr == "dpcm") ResolutionUnit.dotsPerCentimeter else ResolutionUnit.dotsPerInch
        return Resolution(x, y, unit)
    }

    private fun parseResolutions(value: Any): List<Resolution> {
        return when (value) {
            is JSONArray ->
                (0 until value.length()).mapNotNull { parseResolution(value.optString(it, "")) }
            is String -> parseResolution(value)?.let { listOf(it) } ?: emptyList()
            else -> emptyList()
        }
    }

    private fun parseMediaColDatabaseList(value: Any): List<MediaColDatabase> {
        return when (value) {
            is JSONArray ->
                (0 until value.length()).mapNotNull {
                    value.optJSONObject(it)?.let { obj -> parseMediaColDatabaseItem(obj) }
                }
            is JSONObject -> parseMediaColDatabaseItem(value)?.let { listOf(it) } ?: emptyList()
            else -> emptyList()
        }
    }

    private fun extractValue(obj: JSONObject, key: String): Any? {
        // Returns the raw data for this key. If the data is wrapped in an explicit
        // type/value JSON object, it unboxes and returns the inner 'value' contents.
        val raw = obj.opt(key) ?: return null
        return if (raw is JSONObject && raw.has("type")) raw.opt("value") else raw
    }

    private fun parseMediaColDatabaseItem(obj: JSONObject): MediaColDatabase? {
        // Unbox "media-size" collection if explicitly typed
        val rawSizeObj = extractValue(obj, "media-size") as? JSONObject ?: return null

        // Unbox inner dimensions
        val xDim = (extractValue(rawSizeObj, "x-dimension") as? Number)?.toInt() ?: -1
        val yDim = (extractValue(rawSizeObj, "y-dimension") as? Number)?.toInt() ?: -1

        if (xDim <= 0 || yDim <= 0) return null

        val mediaSize = MediaColDatabase.MediaSize(IntOrIntRange(xDim), IntOrIntRange(yDim))

        // Unbox boundaries and use Elvis operator for concise null safety
        val bottomMargin = (extractValue(obj, "media-bottom-margin") as? Number)?.toInt()
        val topMargin = (extractValue(obj, "media-top-margin") as? Number)?.toInt()
        val leftMargin = (extractValue(obj, "media-left-margin") as? Number)?.toInt()
        val rightMargin = (extractValue(obj, "media-right-margin") as? Number)?.toInt()
        val source = (extractValue(obj, "media-source") as? String)?.let { KeywordOrName(it) }
        val type = (extractValue(obj, "media-type") as? String)?.let { KeywordOrName(it) }

        return MediaColDatabase(
            mediaBottomMargin = bottomMargin,
            mediaLeftMargin = leftMargin,
            mediaRightMargin = rightMargin,
            mediaSize = mediaSize,
            mediaSource = source,
            mediaTopMargin = topMargin,
            mediaType = type,
        )
    }

    private fun parseUntypedCollectionList(value: JSONArray): List<UntypedCollection> {
        return (0 until value.length()).mapNotNull {
            value.optJSONObject(it)?.let { obj -> parseUntypedCollection(obj) }
        }
    }

    private fun parseUntypedCollection(value: JSONObject): UntypedCollection {
        return UntypedCollection(parse(value))
    }
}
