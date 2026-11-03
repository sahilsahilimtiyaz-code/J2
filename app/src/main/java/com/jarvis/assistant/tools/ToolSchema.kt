package com.jarvis.assistant.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun objectSchema(
    required: List<String> = emptyList(),
    properties: Map<String, JsonObject> = emptyMap(),
): JsonObject = buildJsonObject {
    put("type", "object")
    put(
        "properties",
        buildJsonObject {
            properties.forEach { (name, schema) -> put(name, schema) }
        },
    )
    if (required.isNotEmpty()) {
        put(
            "required",
            buildJsonArray { required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } },
        )
    }
}

internal fun objectSchema(
    properties: Map<String, JsonObject>,
): JsonObject = objectSchema(emptyList(), properties)

internal fun stringProperty(description: String, enum: List<String>? = null): JsonObject =
    buildJsonObject {
        put("type", "string")
        put("description", description)
        if (enum != null) {
            put(
                "enum",
                buildJsonArray { enum.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } },
            )
        }
    }

internal fun intProperty(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

internal fun integerProperty(description: String): JsonObject = intProperty(description)

internal fun doubleProperty(description: String): JsonObject = buildJsonObject {
    put("type", "number")
    put("description", description)
}

internal fun booleanProperty(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}
