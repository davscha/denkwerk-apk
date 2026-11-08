package de.davscha.denkwerk.data

import org.json.JSONObject

/** Ein Fakt über dich, den Denkwerk sich merkt und in jeden Chat mitnimmt. */
data class MemoryFact(
    val id: String = newId(),
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("text", text).put("createdAt", createdAt)

    companion object {
        fun fromJson(o: JSONObject) = MemoryFact(
            id = o.optString("id").ifEmpty { newId() },
            text = o.optString("text"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        )
    }
}
