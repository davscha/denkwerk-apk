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
 * Denkwerk benutzt nur Dateien aus den Unterordnern  checkpoints/denkwerk/<familie>/  und  loras/denkwerk/<familie>/char|style/.
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

    /** Checkpoints aus checkpoints/denkwerk/<family>/ (Namen so, wie ComfyUI sie kennt – unter Windows mit \) */
    fun checkpoints(family: String): List<String> =
        options("CheckpointLoaderSimple", "ckpt_name").filter { inFolder(it, family) }.sortedBy { it.lowercase() }

    /** LoRAs aus loras/denkwerk/<family>/… */
    fun loras(family: String): List<String> =
        options("LoraLoader", "lora_name").filter { inFolder(it, family) }.sortedBy { it.lowercase() }

    /**
     * Trigger-Wörter aus den eingebetteten Trainingsdaten (ss_tag_frequency):
     * die häufigsten Tags ohne Allerwelts-Tags wie 1girl oder solo.
     */
    fun suggestTriggers(lora: String): String = runCatching {
        val meta = JSONObject(get("/view_metadata/loras?filename=" + URLEncoder.encode(lora, "UTF-8"), 10_000))
        val freq = meta.optString("ss_tag_frequency").takeIf { it.isNotBlank() }?.let { JSONObject(it) } ?: return ""
        val counts = HashMap<String, Int>()
        freq.keys().forEach { dir ->
            val tags = freq.optJSONObject(dir) ?: return@forEach
            tags.keys().forEach { t -> counts[t.trim()] = (counts[t.trim()] ?: 0) + tags.optInt(t) }
        }
        val generic = setOf("1girl", "1boy", "solo", "looking at viewer", "simple background", "white background", "smile", "open mouth",
            "blush", "long hair", "short hair", "breasts", "highres", "absurdres", "masterpiece", "best quality")
        counts.entries.sortedByDescending { it.value }
            .map { it.key.replace('_', ' ') }
            .filter { it.lowercase() !in generic }
            .take(2).joinToString(", ")
    }.getOrDefault("")

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
        /** Liegt die Datei in denkwerk/<family>/ ? (Windows: \, sonst /) */
        fun inFolder(name: String, family: String): Boolean =
            name.replace('\\', '/').lowercase().startsWith("denkwerk/${family.lowercase()}/")

        /** char oder style – aus dem Unterordner */
        fun kindOf(name: String): String {
            val parts = name.replace('\\', '/').lowercase().split('/')
            return when {
                "char" in parts -> "char"
                "style" in parts -> "style"
                else -> "style"
            }
        }

        /** Nur der Dateiname ohne Ordner und Endung */
        fun shortName(name: String) = name.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.')

        /** Bildformate (für SDXL-Modelle wie NoobAI) */
        fun size(format: String): Pair<Int, Int> = when (format) {
            "hoch" -> 832 to 1216
            "quer" -> 1216 to 832
            "breit" -> 1344 to 768
            else -> 1024 to 1024
        }

        /** Qualitäts-Tags und Negativ-Prompt für NoobAI */
        const val NOOBAI_QUALITY = "masterpiece, best quality, newest, absurdres, highres"
        const val NOOBAI_NEGATIVE = "worst quality, low quality, lowres, old, early, bad anatomy, bad hands, extra digits, fewer digits, " +
            "jpeg artifacts, signature, watermark, username, logo, blurry, artist name"

        /**
         * Baut den Workflow (API-Format):
         * Checkpoint → (v-pred-Einstellung) → LoRA → LoRA … → Prompts → KSampler → VAE → Speichern
         */
        /** Seed aus dem Dateinamen eines gemalten Bildes (bild-<zeit>-s<seed>.png), sonst null */
        fun seedOf(path: String): Long? = Regex("-s(\\d+)\\.png$").find(path)?.groupValues?.get(1)?.toLongOrNull()

        fun buildWorkflow(recipe: ImageRecipe, positive: String, negative: String, format: String, seed: Long): JSONObject {
            val wf = JSONObject()
            var id = 1
            fun node(type: String, inputs: JSONObject): String {
                val key = (id++).toString()
                wf.put(key, JSONObject().put("class_type", type).put("inputs", inputs))
                return key
            }
            fun link(n: String, out: Int) = JSONArray().put(n).put(out)

            val ckpt = node("CheckpointLoaderSimple", JSONObject().put("ckpt_name", recipe.checkpoint))
            var model = link(ckpt, 0)
            var clip = link(ckpt, 1)
            // v-pred-Modelle (Name enthält vpred) brauchen eine andere Rauschplanung
            if (Regex("v[-_ ]?pred", RegexOption.IGNORE_CASE).containsMatchIn(recipe.checkpoint)) {
                val ms = node("ModelSamplingDiscrete", JSONObject().put("sampling", "v_prediction").put("zsnr", true).put("model", model))
                model = link(ms, 0)
            }
            for (l in recipe.loras.filter { it.enabled }) {
                val n = node(
                    "LoraLoader",
                    JSONObject().put("lora_name", l.name).put("strength_model", l.strength.toDouble())
                        .put("strength_clip", l.strength.toDouble()).put("model", model).put("clip", clip),
                )
                model = link(n, 0)
                clip = link(n, 1)
            }
            val pos = node("CLIPTextEncode", JSONObject().put("text", positive).put("clip", clip))
            val neg = node("CLIPTextEncode", JSONObject().put("text", negative).put("clip", clip))
            val (w, h) = size(format)
            val latent = node("EmptyLatentImage", JSONObject().put("width", w).put("height", h).put("batch_size", 1))
            val sampler = node(
                "KSampler",
                JSONObject().put("seed", seed).put("steps", recipe.steps).put("cfg", recipe.cfg.toDouble())
                    .put("sampler_name", recipe.sampler).put("scheduler", recipe.scheduler).put("denoise", 1.0)
                    .put("model", model).put("positive", link(pos, 0)).put("negative", link(neg, 0)).put("latent_image", link(latent, 0)),
            )
            val decode = node("VAEDecode", JSONObject().put("samples", link(sampler, 0)).put("vae", link(ckpt, 2)))
            node("SaveImage", JSONObject().put("filename_prefix", "Denkwerk/bild").put("images", link(decode, 0)))
            return wf
        }
    }
}
