package de.davscha.denkwerk.data

import org.json.JSONObject

/**
 * Das Bildrezept für ComfyUI – MOSS-Image-Workflow wie Davids MossImage.json:
 * UNET RealisticFreedom + 4-Step-LoRA + SkipLayerGuidance, Qwen-Textencoder (moss_image), Flux2-VAE,
 * Größe über den ResolutionSelector (Seitenverhältnis + Megapixel).
 */
data class ImageRecipe(
    val unet: String = "RealisticFreedom.safetensors",
    val lora: String = "moss_image_sft_4step_v2.0.safetensors",
    val clip: String = "Qwen359B.safetensors",
    val vae: String = "Flux2.safetensors",
    val aspect: String = "9:16 (Portrait Widescreen)",
    val megapixels: Float = 2f,
    val steps: Int = 6,
    val cfg: Float = 1f,
    val sampler: String = "er_sde",
    val scheduler: String = "simple",
) {
    fun toJson(): JSONObject = JSONObject().put("workflow", "moss").put("unet", unet).put("lora", lora).put("clip", clip).put("vae", vae)
        .put("aspect", aspect).put("megapixels", megapixels.toDouble()).put("steps", steps).put("cfg", cfg.toDouble())
        .put("sampler", sampler).put("scheduler", scheduler)

    companion object {
        /** Seitenverhältnisse des ResolutionSelector (werden beim Verbinden aus ComfyUI aktualisiert) */
        val DEFAULT_ASPECTS = listOf(
            "1:1 (Square)", "2:3 (Portrait Photo)", "3:2 (Photo)", "3:4 (Portrait Standard)", "4:3 (Standard)",
            "9:16 (Portrait Widescreen)", "16:9 (Widescreen)", "21:9 (Ultrawide)",
        )

        fun fromJson(s: String): ImageRecipe = runCatching {
            val o = JSONObject(s)
            // Ältere Rezepte (NoobAI, Anima) → MOSS-Standardwerte
            if (o.optString("workflow") != "moss") return ImageRecipe()
            val d = ImageRecipe()
            ImageRecipe(
                unet = o.optString("unet").ifBlank { d.unet },
                lora = o.optString("lora").ifBlank { d.lora },
                clip = o.optString("clip").ifBlank { d.clip },
                vae = o.optString("vae").ifBlank { d.vae },
                aspect = o.optString("aspect").ifBlank { d.aspect },
                megapixels = o.optDouble("megapixels", d.megapixels.toDouble()).toFloat(),
                steps = o.optInt("steps", d.steps),
                cfg = o.optDouble("cfg", d.cfg.toDouble()).toFloat(),
                sampler = o.optString("sampler").ifBlank { d.sampler },
                scheduler = o.optString("scheduler").ifBlank { d.scheduler },
            )
        }.getOrDefault(ImageRecipe())
    }
}
