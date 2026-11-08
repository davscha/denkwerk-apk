package de.davscha.denkwerk.data

import org.json.JSONArray
import org.json.JSONObject

/** Eine LoRA im Bildrezept */
data class LoraSetting(
    /** Name wie ComfyUI ihn kennt, z. B. denkwerk\noobai\style\PandorasBoxStyle.safetensors */
    val name: String,
    /** "char" oder "style" (aus dem Unterordner) */
    val kind: String,
    val trigger: String = "",
    val strength: Float = 0.8f,
    val enabled: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("kind", kind).put("trigger", trigger)
        .put("strength", strength.toDouble()).put("enabled", enabled)

    companion object {
        fun fromJson(o: JSONObject) = LoraSetting(
            o.optString("name"), o.optString("kind", "style"), o.optString("trigger"),
            o.optDouble("strength", 0.8).toFloat(), o.optBoolean("enabled"),
        )
    }
}

/**
 * Das Bildrezept für ComfyUI: Familie (vorerst nur NoobAI), Checkpoint, LoRAs und Sampler-Werte.
 * Standardwerte wie Davids bewährter Workflow: 12 Schritte, CFG 4, euler/simple.
 */
data class ImageRecipe(
    val family: String = "noobai",
    val checkpoint: String = "",
    val loras: List<LoraSetting> = emptyList(),
    val steps: Int = 12,
    val cfg: Float = 4f,
    val sampler: String = "euler",
    val scheduler: String = "simple",
    val format: String = "quadrat",
    /** true = „nsfw“ in den Negativ-Prompt */
    val sfw: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("family", family).put("checkpoint", checkpoint)
        .put("loras", JSONArray(loras.map { it.toJson() })).put("steps", steps).put("cfg", cfg.toDouble())
        .put("sampler", sampler).put("scheduler", scheduler).put("format", format).put("sfw", sfw)

    companion object {
        fun fromJson(s: String): ImageRecipe = runCatching {
            val o = JSONObject(s)
            ImageRecipe(
                family = o.optString("family", "noobai"),
                checkpoint = o.optString("checkpoint"),
                loras = o.optJSONArray("loras")?.let { a -> List(a.length()) { LoraSetting.fromJson(a.getJSONObject(it)) } }.orEmpty(),
                steps = o.optInt("steps", 12),
                cfg = o.optDouble("cfg", 4.0).toFloat(),
                sampler = o.optString("sampler", "euler"),
                scheduler = o.optString("scheduler", "simple"),
                format = o.optString("format", "quadrat"),
                sfw = o.optBoolean("sfw", true),
            )
        }.getOrDefault(ImageRecipe())
    }
}
