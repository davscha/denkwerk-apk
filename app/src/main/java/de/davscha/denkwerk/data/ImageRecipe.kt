package de.davscha.denkwerk.data

import org.json.JSONObject

/**
 * Das Bildrezept für ComfyUI – Testphase mit dem Anima-Workflow
 * (UNET AnimaPhotoTurbo + Qwen-Textencoder + Qwen-VAE, 4 Schritte, CFG 1, kein Negativ-Prompt).
 */
data class ImageRecipe(
    val unet: String = "AnimaPhotoTurbo.safetensors",
    val clip: String = "Qwen306BBase.safetensors",
    val vae: String = "QwenImageVae.safetensors",
    val width: Int = 960,
    val height: Int = 1280,
    val steps: Int = 4,
    val cfg: Float = 1f,
    val sampler: String = "euler",
    val scheduler: String = "simple",
    /** true = „safe“ vorn in den Prompt (Anima kennt Rating-Tags) */
    val sfw: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("workflow", "anima").put("unet", unet).put("clip", clip).put("vae", vae)
        .put("width", width).put("height", height).put("steps", steps).put("cfg", cfg.toDouble())
        .put("sampler", sampler).put("scheduler", scheduler).put("sfw", sfw)

    /** Breite/Höhe passend für ComfyUI: Vielfache von 16, 256–2048 */
    val safeWidth get() = fix(width)
    val safeHeight get() = fix(height)
    private fun fix(v: Int) = (v.coerceIn(256, 2048) / 16) * 16

    companion object {
        fun fromJson(s: String): ImageRecipe = runCatching {
            val o = JSONObject(s)
            // Altes NoobAI-Rezept → neue Anima-Standardwerte
            if (o.optString("workflow") != "anima") return ImageRecipe()
            val d = ImageRecipe()
            ImageRecipe(
                unet = o.optString("unet", d.unet).ifBlank { d.unet },
                clip = o.optString("clip", d.clip).ifBlank { d.clip },
                vae = o.optString("vae", d.vae).ifBlank { d.vae },
                width = o.optInt("width", d.width),
                height = o.optInt("height", d.height),
                steps = o.optInt("steps", d.steps),
                cfg = o.optDouble("cfg", d.cfg.toDouble()).toFloat(),
                sampler = o.optString("sampler", d.sampler),
                scheduler = o.optString("scheduler", d.scheduler),
                sfw = o.optBoolean("sfw", true),
            )
        }.getOrDefault(ImageRecipe())
    }
}
