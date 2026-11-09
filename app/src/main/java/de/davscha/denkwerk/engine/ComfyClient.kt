package de.davscha.denkwerk.engine

import de.davscha.denkwerk.data.ImageRecipe
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Spricht mit ComfyUI auf dem PC: Modelle auflisten, Workflow bauen und abschicken, Bild abholen.
 */
class ComfyClient(private val host: String, private val port: Int) {

    private fun open(path: String, timeout: Int = 8000) = (URL("http://${host.trim()}:$port$path").openConnection() as HttpURLConnection).apply {
        connectTimeout = minOf(timeout, 6000)
        readTimeout = timeout
    }

    private fun get(path: String, timeout: Int = 8000): String {
        val c = open(path, timeout)
        try {
            if (c.responseCode !in 200..299) throw IOException("ComfyUI antwortet mit ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    /** Verbindungstest: z. B. "ComfyUI 0.42.0 · NVIDIA GeForce RTX 5060" */
    fun test(): String {
        val o = JSONObject(get("/system_stats", 5000))
        val version = o.optJSONObject("system")?.optString("comfyui_version").orEmpty()
        val gpu = o.optJSONArray("devices")?.optJSONObject(0)?.optString("name").orEmpty()
            .substringAfter(':').substringBefore(":").trim()
        return listOf("ComfyUI $version".trim(), gpu).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun options(node: String, field: String): List<String> {
        val o = JSONObject(get("/object_info/$node"))
        val arr = o.optJSONObject(node)?.optJSONObject("input")?.optJSONObject("required")
            ?.optJSONArray(field)?.optJSONArray(0) ?: JSONArray()
        return List(arr.length()) { arr.optString(it) }
    }

    /** Welche der benötigten Dateien fehlen auf dem PC? (leer = alles da) */
    fun missingFiles(r: ImageRecipe): List<String> {
        fun names(node: String, field: String) = options(node, field).map { it.replace('\\', '/').substringAfterLast('/') }
        return buildList {
            if (r.unet !in names("UNETLoader", "unet_name")) add("diffusion_models/${r.unet}")
            if (r.lora !in names("LoraLoaderModelOnly", "lora_name")) add("loras/${r.lora}")
            if (r.clip !in names("CLIPLoader", "clip_name")) add("text_encoders/${r.clip}")
            if (r.vae !in names("VAELoader", "vae_name")) add("vae/${r.vae}")
        }
    }

    /** Seitenverhältnisse, die der ResolutionSelector anbietet */
    fun aspects(): List<String> = runCatching { options("ResolutionSelector", "aspect_ratio") }.getOrDefault(emptyList())

    /** Datei steht in ComfyUI evtl. mit Unterordner – den echten Namen finden */
    fun resolve(node: String, field: String, file: String): String =
        runCatching { options(node, field).firstOrNull { it.replace('\\', '/').substringAfterLast('/') == file } }.getOrNull() ?: file

    /** Ollama auf demselben PC kann parallel laufen – Schickt den Workflow und wartet auf das Bild (PNG-Bytes). */
    suspend fun run(workflow: JSONObject, onProgress: (Int) -> Unit = {}): ByteArray {
        val body = JSONObject().put("prompt", workflow).put("client_id", UUID.randomUUID().toString()).toString()
        val c = open("/prompt", 15_000).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val answer = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        c.disconnect()
        if (code !in 200..299) throw IOException("ComfyUI lehnt den Auftrag ab: " + errorText(answer))
        val id = JSONObject(answer).getString("prompt_id")

        val start = System.currentTimeMillis()
        while (true) {
            delay(1000)
            val secs = ((System.currentTimeMillis() - start) / 1000).toInt()
            onProgress(secs)
            if (secs > 600) throw IOException("ComfyUI braucht zu lange (über 10 Minuten)")
            val entry = runCatching { JSONObject(get("/history/$id")).optJSONObject(id) }.getOrNull() ?: continue
            entry.optJSONObject("status")?.let { st ->
                if (st.optString("status_str") == "error") {
                    val msg = st.optJSONArray("messages")?.toString()?.let { Regex("\"exception_message\":\"([^\"]+)").find(it)?.groupValues?.get(1) }
                    throw IOException("ComfyUI meldet einen Fehler" + (msg?.let { ": $it" } ?: ""))
                }
            }
            val outputs = entry.optJSONObject("outputs") ?: continue
            for (key in outputs.keys()) {
                val img = outputs.getJSONObject(key).optJSONArray("images")?.optJSONObject(0) ?: continue
                val q = "filename=${enc(img.optString("filename"))}&subfolder=${enc(img.optString("subfolder"))}&type=${enc(img.optString("type"))}"
                val v = open("/view?$q", 30_000)
                try {
                    return v.inputStream.use { it.readBytes() }
                } finally {
                    v.disconnect()
                }
            }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun errorText(answer: String): String = runCatching {
        val o = JSONObject(answer)
        o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: o.optJSONObject("node_errors")?.toString()?.take(300)
            ?: answer.take(300)
    }.getOrDefault(answer.take(300))

    companion object {
        /** Nur der Dateiname ohne Ordner und Endung */
        fun shortName(name: String) = name.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.')

        /** Seed aus dem Dateinamen eines gemalten Bildes (bild-<zeit>-s<seed>.png), sonst null */
        fun seedOf(path: String): Long? = Regex("-s(\\d+)\\.png$").find(path)?.groupValues?.get(1)?.toLongOrNull()

        /**
         * MOSS-Image-Workflow (API-Format), wie Davids MossImage.json:
         * UNET → LoRA (4 Schritte) → SkipLayerGuidance → KSampler; CLIP (moss_image) → Prompt, Negativ = ConditioningZeroOut;
         * ResolutionSelector (Seitenverhältnis + Megapixel) → EmptyLatentImage; Flux2-VAE → Speichern
         */
        fun buildWorkflow(r: ImageRecipe, positive: String, seed: Long, names: Map<String, String> = emptyMap()): JSONObject {
            fun link(n: String, out: Int) = JSONArray().put(n).put(out)
            fun node(type: String, inputs: JSONObject) = JSONObject().put("class_type", type).put("inputs", inputs)
            return JSONObject()
                .put("20", node("UNETLoader", JSONObject().put("unet_name", names["unet"] ?: r.unet).put("weight_dtype", "default")))
                .put("36", node("LoraLoaderModelOnly", JSONObject().put("lora_name", names["lora"] ?: r.lora).put("strength_model", 1.0).put("model", link("20", 0))))
                .put(
                    "24",
                    node(
                        "SkipLayerGuidanceDiTSimple",
                        JSONObject().put("double_layers", "").put("single_layers", "22").put("start_percent", 0.0).put("end_percent", 0.5)
                            .put("model", link("36", 0)),
                    ),
                )
                .put("13", node("CLIPLoader", JSONObject().put("clip_name", names["clip"] ?: r.clip).put("type", "moss_image").put("device", "default")))
                .put("45", node("CLIPTextEncode", JSONObject().put("text", positive).put("clip", link("13", 0))))
                .put("42", node("ConditioningZeroOut", JSONObject().put("conditioning", link("45", 0))))
                .put("22", node("ResolutionSelector", JSONObject().put("aspect_ratio", r.aspect).put("megapixels", r.megapixels.toDouble()).put("multiple", 16)))
                .put("6", node("EmptyLatentImage", JSONObject().put("width", link("22", 0)).put("height", link("22", 1)).put("batch_size", 1)))
                .put(
                    "2",
                    node(
                        "KSampler",
                        JSONObject().put("seed", seed).put("steps", r.steps).put("cfg", r.cfg.toDouble())
                            .put("sampler_name", r.sampler).put("scheduler", r.scheduler).put("denoise", 1.0)
                            .put("model", link("24", 0)).put("positive", link("45", 0)).put("negative", link("42", 0))
                            .put("latent_image", link("6", 0)),
                    ),
                )
                .put("8", node("VAELoader", JSONObject().put("vae_name", names["vae"] ?: r.vae)))
                .put("7", node("VAEDecode", JSONObject().put("samples", link("2", 0)).put("vae", link("8", 0))))
                .put("9", node("SaveImage", JSONObject().put("filename_prefix", "Denkwerk/bild").put("images", link("7", 0))))
    }
}
