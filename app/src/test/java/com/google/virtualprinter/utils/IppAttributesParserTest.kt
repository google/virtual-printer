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
import com.hp.jipp.encoding.IntOrIntRange
import com.hp.jipp.encoding.IntOrIntRangeType
import com.hp.jipp.encoding.ResolutionType
import com.hp.jipp.encoding.StringType
import com.hp.jipp.encoding.Tag
import org.json.JSONObject
import org.junit.Test

class IppAttributesParserTest {

    @Test
    fun parse_emptyJson_returnsEmptyList() {
        val attributes = IppAttributesParser.parse(JSONObject("{}"))
        assertThat(attributes).isEmpty()
    }

    @Test
    fun parse_implicitAttributes_areIgnored() {
        val json =
            """
            {
                "color-supported": true,
                "copies-default": 2,
                "printer-location": "Building 42"
            }
            """
                .trimIndent()

        val attributes = IppAttributesParser.parse(JSONObject(json))
        assertThat(attributes).isEmpty()
    }

    @Test
    fun parse_explicitTypedAttributes_allRfc8011Types() {
        val json =
            """
            {
                "custom-keyword": {
                    "type": "keyword",
                    "value": ["option1", "option2"]
                },
                "custom-range": {
                    "type": "rangeOfInteger",
                    "value": [5, 50]
                },
                "custom-resolution": {
                    "type": "resolution",
                    "value": "600x600dpi"
                },
                "custom-empty": {
                    "type": "no-value"
                },
                "custom-mime": {
                    "type": "mimeMediaType",
                    "value": "application/pdf"
                },
                "custom-charset": {
                    "type": "charset",
                    "value": "utf-8"
                },
                "custom-lang": {
                    "type": "naturalLanguage",
                    "value": "en-us"
                }
            }
            """
                .trimIndent()

        val attributes = IppAttributesParser.parse(JSONObject(json))
        val attrMap = attributes.associateBy { it.name }

        assertThat((attrMap["custom-keyword"]?.type as? StringType)?.tag).isEqualTo(Tag.keyword)
        assertThat(attrMap["custom-keyword"]?.strings())
            .containsExactly("option1", "option2")
            .inOrder()

        assertThat(attrMap["custom-range"]?.type).isInstanceOf(IntOrIntRangeType::class.java)
        assertThat(attrMap["custom-range"]?.getValue()).isEqualTo(IntOrIntRange(5, 50))

        assertThat(attrMap["custom-resolution"]?.type).isInstanceOf(ResolutionType::class.java)
        assertThat(attrMap["custom-empty"]?.isNoValue()).isTrue()
        assertThat((attrMap["custom-mime"]?.type as? StringType)?.tag).isEqualTo(Tag.mimeMediaType)
        assertThat((attrMap["custom-charset"]?.type as? StringType)?.tag).isEqualTo(Tag.charset)
        assertThat((attrMap["custom-lang"]?.type as? StringType)?.tag)
            .isEqualTo(Tag.naturalLanguage)
    }

    @Test
    fun parse_strictTypes_stringIsNotCoercedToBoolean() {
        val json =
            """
            {
                "quoted-true-value": {
                    "type": "boolean",
                    "value": "true"
                },
                "quoted-number-value": {
                    "type": "integer",
                    "value": "42"
                }
            }
            """
                .trimIndent()

        // Quoted ints and bools aren't allowed (unless it's actually a string).
        val attributes = IppAttributesParser.parse(JSONObject(json))
        val attrMap = attributes.associateBy { it.name }

        assertThat(attrMap).doesNotContainKey("quoted-true-value")
        assertThat(attrMap).doesNotContainKey("quoted-number-value")
    }

    @Test
    fun validate_validStandardAttributes_returnsNoErrors() {
        val json =
            """
            {
                "color-supported": { "type": "boolean", "value": true },
                "copies-default": { "type": "integer", "value": 1 },
                "copies-supported": { "type": "rangeofinteger", "value": [1, 999] },
                "job-impressions-supported": { "type": "rangeofinteger", "value": "(1:2147483647)" },
                "printer-resolution-default": { "type": "resolution", "value": "600x600dpi" },
                "marker-levels": { "type": "integer", "value": [80, 50] }
            }
            """
                .trimIndent()

        val attributes = IppAttributesParser.parse(JSONObject(json))
        val errors = IppAttributesParser.validate(attributes)
        assertThat(errors).isEmpty()
    }

    @Test
    fun validate_typeMismatches_detectsErrors() {
        // Technically this acts as a check if they forcefully mapped wrong types.
        val json =
            """
            {
                "color-supported": { "type": "keyword", "value": "true" },
                "copies-default": { "type": "keyword", "value": "2" },
                "marker-levels": { "type": "keyword", "value": "not-an-int" }
            }
            """
                .trimIndent()

        val attributes = IppAttributesParser.parse(JSONObject(json))
        val errors = IppAttributesParser.validate(attributes)

        assertThat(errors).hasSize(3)
        assertThat(errors.any { it.contains("color-supported") && it.contains("boolean") }).isTrue()
        assertThat(errors.any { it.contains("copies-default") && it.contains("integer") }).isTrue()
        assertThat(errors.any { it.contains("marker-levels") && it.contains("integer") }).isTrue()
    }

    @Test
    fun validate_unknownCustomAttributes_permittedWithoutError() {
        val json =
            """
            {
                "my-custom-vendor-attribute": { "type": "keyword", "value": "custom-val" },
                "another-custom-flag": { "type": "boolean", "value": true }
            }
            """
                .trimIndent()

        val attributes = IppAttributesParser.parse(JSONObject(json))
        val errors = IppAttributesParser.validate(attributes)
        assertThat(errors).isEmpty()
    }

    @Test
    fun parse_untypedCollection_recursivelyParses() {
        // A generic collection structure that should be parsed into UntypedCollection
        val json =
            """
            {
                "custom-collection": {
                    "type": "collection",
                    "value": {
                        "nested-bool": { "type": "boolean", "value": true },
                        "nested-int": { "type": "integer", "value": 42 }
                    }
                },
                "custom-collection-list": {
                    "type": "collection",
                    "value": [
                        {
                            "nested-string": { "type": "keyword", "value": "value1" }
                        },
                        {
                            "nested-string": { "type": "keyword", "value": "value2" }
                        }
                    ]
                }
            }
            """
                .trimIndent()

        val attributes = IppAttributesParser.parse(JSONObject(json))
        val attrMap = attributes.associateBy { it.name }

        val customCol = attrMap["custom-collection"]
        assertThat(customCol).isNotNull()
        assertThat(customCol?.type?.name).isEqualTo("custom-collection")
        val colVal = customCol?.getValue() as? com.hp.jipp.encoding.UntypedCollection
        assertThat(colVal).isNotNull()
        val nestedAttrMap = colVal!!.attributes.associateBy { it.name }
        assertThat(nestedAttrMap["nested-bool"]?.type?.name).isEqualTo("nested-bool")
        assertThat(nestedAttrMap["nested-bool"]?.getValue()).isEqualTo(true)
        assertThat(nestedAttrMap["nested-int"]?.getValue()).isEqualTo(42)

        val customColList = attrMap["custom-collection-list"]
        assertThat(customColList).isNotNull()
        val listVals = customColList?.toList() as? List<com.hp.jipp.encoding.UntypedCollection>
        assertThat(listVals).isNotNull()
        assertThat(listVals).hasSize(2)
        assertThat(listVals?.get(0)?.attributes?.get(0)?.getValue()).isEqualTo("value1")
        assertThat(listVals?.get(1)?.attributes?.get(0)?.getValue()).isEqualTo("value2")
    }

    @Test
    fun validate_untypedCollection_validatesMembers() {
        val json =
            """
            {
                "custom-collection": {
                    "type": "collection",
                    "value": {
                        "color-supported": { "type": "keyword", "value": "this-should-be-bool" },
                        "copies-default": { "type": "keyword", "value": "this-should-be-int" }
                    }
                }
            }
            """
                .trimIndent()

        val attributes = IppAttributesParser.parse(JSONObject(json))
        val errors = IppAttributesParser.validate(attributes)

        assertThat(errors).hasSize(2)
        assertThat(errors.any { it.contains("color-supported") && it.contains("boolean") }).isTrue()
        assertThat(errors.any { it.contains("copies-default") && it.contains("integer") }).isTrue()
    }
}
