package com.cancerdetection.multimodal.research.data.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class InferenceResponseData(
    val probability: Float,
    val visionShape: List<Int>,
    val clinicalShape: List<Int>,
    val latencyMs: Long,
    val serverUrl: String,
    val attentionWeights: List<Float> = emptyList(),
    val requestId: String? = null,
    val tileCount: Int? = null,
    val reportSha256: String? = null,
    val rawLogit: Double? = null,
    val isSampleReport: Boolean? = null,
    val totalModelInferenceMs: Double? = null,
    val visionInferenceMs: Double? = null,
    val clinicalTextInferenceMs: Double? = null,
    val fusionClassificationMs: Double? = null,
    val inferenceTimingScope: String? = null
)

data class SummaryResponseData(
    val summary: String,
    val source: String, // "ollama_llm", "other_llm_provider", "deterministic_template_fallback"
    val modelUsed: String,
    val isLlm: Boolean,
    val errorDetail: String? = null
)

data class LlmHealthData(
    val status: String,
    val provider: String,
    val model: String,
    val modelAvailable: Boolean,
    val availableModels: List<String>,
    val error: String?,
    val setupInstructions: List<String>
) {
    val reachable: Boolean
        get() = (status == "ready" || status == "ok") && modelAvailable
    val message: String
        get() = if (reachable) "LLM service reachable and model verified." else (error ?: "Service unreachable")
    val setupHint: String?
        get() = setupInstructions.firstOrNull()
}

class InferenceApiClient {

    /**
     * Safely parses a finite, positive double field from a JSONObject.
     * Handles Numbers, Strings, and primitive doubles.
     * Returns null if the field is missing, null, NaN, infinite, or <= 0.0.
     */
    fun optValidDouble(json: JSONObject, key: String): Double? {
        if (!json.has(key) || json.isNull(key)) return null
        val raw = json.opt(key) ?: return null
        val v = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull() ?: Double.NaN
            else -> json.optDouble(key, Double.NaN)
        }
        return if (v.isFinite() && v > 0.0) v else null
    }

    /**
     * Sends non-blocking diagnostic telemetry to the Flask backend to record the exact
     * 6-stage runtime pipeline trace for live auditability.
     */
    fun sendDiagnosticTrace(baseUrl: String, stage: Int, stageName: String, data: Map<String, Any?>) {
        Thread {
            try {
                val cleanBase = baseUrl.trim().removeSuffix("/").removeSuffix("/predict").removeSuffix("/embed_tiles").removeSuffix("/fuse").removeSuffix("/health").removeSuffix("/")
                val url = URL("$cleanBase/diagnostic_trace")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    doOutput = true
                    connectTimeout = 3000
                    readTimeout = 3000
                }
                val payload = JSONObject().apply {
                    put("stage", stage)
                    put("name", stageName)
                    val d = JSONObject()
                    data.forEach { (k, v) -> d.put(k, v ?: JSONObject.NULL) }
                    put("data", d)
                }
                OutputStreamWriter(conn.outputStream, "UTF-8").use {
                    it.write(payload.toString())
                    it.flush()
                }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {
                // Non-fatal telemetry helper
            }
        }.start()
    }

    /**
     * Checks if the Python Flask inference server is reachable and healthy.
     */
    suspend fun checkHealth(baseUrl: String): Result<Boolean> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val cleanBase = baseUrl.trim().removeSuffix("/").removeSuffix("/predict").removeSuffix("/embed_tiles").removeSuffix("/fuse").removeSuffix("/health").removeSuffix("/")
            val url = URL("$cleanBase/health")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                val isOk = json.optString("status") == "ok"
                Result.success(isOk)
            } else {
                Result.failure(Exception("Server returned HTTP $responseCode on health check"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Calls the real backend /predict endpoint with base64 encoded histology patches
     * and unstructured clinical report text.
     */
    suspend fun runPredict(
        baseUrl: String,
        clinicalReport: String,
        patchesBase64: List<String>,
        clientRequestId: String? = null
    ): Result<InferenceResponseData> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        val startTime = System.currentTimeMillis()
        val requestId = clientRequestId ?: java.util.UUID.randomUUID().toString()

        try {
            val cleanBase = baseUrl.trim().removeSuffix("/").removeSuffix("/predict").removeSuffix("/embed_tiles").removeSuffix("/fuse").removeSuffix("/health").removeSuffix("/")
            val endpoint = "$cleanBase/predict"
            val url = URL(endpoint)

            // Construct JSON request body
            val payload = JSONObject().apply {
                put("request_id", requestId)
                put("clinical_report", clinicalReport)
                val patchesArray = JSONArray()
                patchesBase64.forEach { patchesArray.put(it) }
                put("patches", patchesArray)
            }

            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 180000 // Allow up to 3 minutes for larger CPU runs
            }

            // Write payload
            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            val latencyMs = System.currentTimeMillis() - startTime

            if (responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                Log.i("DIAGNOSTIC_TRACE", "[STAGE 1 - Raw HTTP Response] endpoint=$endpoint, code=$responseCode, len=${responseText.length}, bodyPrefix=${responseText.take(200)}")
                sendDiagnosticTrace(cleanBase, 1, "Raw HTTP Response", mapOf("endpoint" to endpoint, "code" to responseCode, "len" to responseText.length, "has_total_timing" to responseText.contains("total_model_inference_ms")))

                val json = JSONObject(responseText)

                val prob = json.getDouble("probability").toFloat()
                if (!prob.isFinite()) {
                    return@withContext Result.failure(Exception("Inference returned non-finite probability value"))
                }

                val respRequestId = json.optString("request_id", requestId)
                val rawLogit = if (json.has("raw_logit")) json.optDouble("raw_logit") else null
                val reportSha256 = json.optString("report_sha256").takeIf { it.isNotBlank() }
                val isSampleReport = if (json.has("is_sample_report")) json.optBoolean("is_sample_report") else null
                val tileCount = if (json.has("tile_count")) json.optInt("tile_count") else patchesBase64.size

                val parsedTotal = optValidDouble(json, "total_model_inference_ms")
                    ?: optValidDouble(json, "inference_latency_ms")
                    ?: optValidDouble(json, "model_inference_ms")
                val visionInferenceMs = optValidDouble(json, "vision_inference_ms")
                val clinicalTextInferenceMs = optValidDouble(json, "clinical_text_inference_ms")
                val fusionClassificationMs = optValidDouble(json, "fusion_classification_ms")
                val inferenceTimingScope = json.optString("inference_timing_scope").takeIf { it.isNotBlank() }

                var totalModelInferenceMs = parsedTotal
                if (totalModelInferenceMs == null) {
                    val componentSum = (visionInferenceMs ?: 0.0) + (clinicalTextInferenceMs ?: 0.0) + (fusionClassificationMs ?: 0.0)
                    if (componentSum > 0.0) {
                        totalModelInferenceMs = componentSum
                    }
                }

                Log.i(
                    "DIAGNOSTIC_TRACE",
                    "[STAGE 2 - JSON Timing Extracted] raw_total=${json.opt("total_model_inference_ms")}, parsedTotal=$parsedTotal, visionInferenceMs=$visionInferenceMs, clinicalTextInferenceMs=$clinicalTextInferenceMs, fusionClassificationMs=$fusionClassificationMs, totalModelInferenceMs=$totalModelInferenceMs"
                )
                sendDiagnosticTrace(cleanBase, 2, "JSON Timing Extracted", mapOf("endpoint" to endpoint, "raw_total" to json.opt("total_model_inference_ms"), "parsedTotal" to parsedTotal, "visionInferenceMs" to visionInferenceMs, "clinicalTextInferenceMs" to clinicalTextInferenceMs, "fusionClassificationMs" to fusionClassificationMs, "totalModelInferenceMs" to totalModelInferenceMs))

                val visionShape = mutableListOf<Int>()
                json.optJSONArray("vision_shape")?.let { arr ->
                    for (i in 0 until arr.length()) visionShape.add(arr.getInt(i))
                }

                val clinicalShape = mutableListOf<Int>()
                json.optJSONArray("clinical_shape")?.let { arr ->
                    for (i in 0 until arr.length()) clinicalShape.add(arr.getInt(i))
                }

                val attentionWeights = mutableListOf<Float>()
                json.optJSONArray("attention_weights")?.let { arr ->
                    var allValid = true
                    val temp = mutableListOf<Float>()
                    for (i in 0 until arr.length()) {
                        val w = arr.optDouble(i, Double.NaN).toFloat()
                        if (w.isFinite() && w >= 0f) {
                            temp.add(w)
                        } else {
                            allValid = false
                            break
                        }
                    }
                    if (allValid && temp.size == arr.length()) {
                        attentionWeights.addAll(temp)
                    }
                }

                Log.i(
                    "DIAGNOSTIC_TRACE",
                    "[STAGE 3 - Kotlin Response Model Populated] totalModelInferenceMs=$totalModelInferenceMs, visionInferenceMs=$visionInferenceMs, clinicalTextInferenceMs=$clinicalTextInferenceMs, fusionClassificationMs=$fusionClassificationMs, latencyMs=$latencyMs"
                )
                sendDiagnosticTrace(cleanBase, 3, "Kotlin Response Model Populated", mapOf("endpoint" to endpoint, "totalModelInferenceMs" to totalModelInferenceMs, "visionInferenceMs" to visionInferenceMs, "clinicalTextInferenceMs" to clinicalTextInferenceMs, "fusionClassificationMs" to fusionClassificationMs, "latencyMs" to latencyMs))

                Log.i(
                    "InferenceApiClient",
                    "[INFERENCE_CLIENT_DIAGNOSTIC] endpoint=$endpoint, requestId=$respRequestId, tiles=$tileCount, prob=$prob, rawLogit=$rawLogit, latencyMs=$latencyMs, totalModelInferenceMs=$totalModelInferenceMs"
                )

                Result.success(
                    InferenceResponseData(
                        probability = prob,
                        visionShape = visionShape,
                        clinicalShape = clinicalShape,
                        latencyMs = latencyMs,
                        serverUrl = endpoint,
                        attentionWeights = attentionWeights,
                        requestId = respRequestId,
                        tileCount = tileCount,
                        reportSha256 = reportSha256,
                        rawLogit = rawLogit,
                        isSampleReport = isSampleReport,
                        totalModelInferenceMs = totalModelInferenceMs,
                        visionInferenceMs = visionInferenceMs,
                        clinicalTextInferenceMs = clinicalTextInferenceMs,
                        fusionClassificationMs = fusionClassificationMs,
                        inferenceTimingScope = inferenceTimingScope
                    )
                )
            } else {
                val errorStream = connection.errorStream
                val errorBody = errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                val parsedError = try {
                    JSONObject(errorBody).optString("error", errorBody)
                } catch (_: Exception) {
                    errorBody
                }
                Result.failure(Exception("Inference API error (HTTP $responseCode): $parsedError"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Executes robust multimodal inference supporting >100 tiles.
     * Uses two-stage processing when tile count > chunkSize (e.g. 25):
     *   Stage 1: Sends tiles in bounded chunks of [chunkSize] to /embed_tiles with retry and progress updates.
     *   Stage 2: Sends aggregated tile embeddings and clinical report to /fuse for sequence cross-attention.
     * If the server does not support two-stage endpoints (e.g. HTTP 404), seamlessly falls back to direct /predict.
     */
    suspend fun runBatchedMultimodalInference(
        baseUrl: String,
        clinicalReport: String,
        patchesBase64: List<String>,
        chunkSize: Int = 25,
        maxRetriesPerChunk: Int = 3,
        clientRequestId: String? = null,
        onProgress: ((current: Int, total: Int, message: String) -> Unit)? = null
    ): Result<InferenceResponseData> = withContext(Dispatchers.IO) {
        val requestId = clientRequestId ?: java.util.UUID.randomUUID().toString()
        val totalTiles = patchesBase64.size
        if (totalTiles == 0) {
            return@withContext Result.failure(Exception("No image patches provided for inference."))
        }

        val cleanBase = baseUrl.trim().removeSuffix("/").removeSuffix("/predict").removeSuffix("/embed_tiles").removeSuffix("/fuse").removeSuffix("/health").removeSuffix("/")

        // For small patch counts (<= chunkSize), direct /predict is fastest and supported everywhere
        if (totalTiles <= chunkSize) {
            onProgress?.invoke(0, totalTiles, "Extracting features & computing cross-attention (1–$totalTiles tiles)...")
            val res = runPredict(cleanBase, clinicalReport, patchesBase64, clientRequestId = requestId)
            if (res.isSuccess) {
                onProgress?.invoke(totalTiles, totalTiles, "Inference completed successfully.")
            }
            return@withContext res
        }

        // Two-stage execution for larger sets (> chunkSize)
        val chunks = patchesBase64.chunked(chunkSize)
        val allEmbeddings = mutableListOf<List<Double>>()
        var completedTiles = 0
        var accumulatedVisionMs = 0.0
        val startTime = System.currentTimeMillis()

        for ((chunkIdx, chunk) in chunks.withIndex()) {
            val chunkStartTile = completedTiles + 1
            val chunkEndTile = completedTiles + chunk.size
            onProgress?.invoke(
                completedTiles,
                totalTiles,
                "Embedding tiles $chunkStartTile–$chunkEndTile of $totalTiles (${(completedTiles * 100) / totalTiles}%)..."
            )

            var chunkSuccess = false
            var lastChunkError: Exception? = null

            for (attempt in 1..maxRetriesPerChunk) {
                try {
                    val embedUrl = URL("$cleanBase/embed_tiles")
                    val conn = (embedUrl.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("Accept", "application/json")
                        doOutput = true
                        connectTimeout = 15000
                        readTimeout = 90000 // 90s per 25-tile chunk
                    }

                    val payload = JSONObject().apply {
                        put("request_id", "$requestId-chunk-${chunkIdx + 1}")
                        put("session_id", requestId)
                        val arr = JSONArray()
                        chunk.forEach { arr.put(it) }
                        put("patches", arr)
                        put("batch_size", chunkSize)
                    }

                    OutputStreamWriter(conn.outputStream, "UTF-8").use {
                        it.write(payload.toString())
                        it.flush()
                    }

                    val code = conn.responseCode
                    if (code == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        conn.disconnect()
                        val json = JSONObject(body)
                        val embsJson = json.getJSONArray("embeddings")
                        for (i in 0 until embsJson.length()) {
                            val vecJson = embsJson.getJSONArray(i)
                            val vec = ArrayList<Double>(vecJson.length())
                            for (j in 0 until vecJson.length()) {
                                vec.add(vecJson.getDouble(j))
                            }
                            allEmbeddings.add(vec)
                        }

                        val chunkVisionMs = optValidDouble(json, "vision_inference_ms")
                            ?: optValidDouble(json, "total_model_inference_ms")
                            ?: optValidDouble(json, "inference_latency_ms")
                        if (chunkVisionMs != null && chunkVisionMs > 0.0) {
                            accumulatedVisionMs += chunkVisionMs
                        }

                        completedTiles += chunk.size
                        chunkSuccess = true
                        break
                    } else if (code == 404) {
                        // Server does not support /embed_tiles endpoint; fall back to direct /predict
                        conn.disconnect()
                        onProgress?.invoke(
                            0,
                            totalTiles,
                            "Two-stage /embed_tiles endpoint returned HTTP 404; falling back to direct /predict ($totalTiles tiles)..."
                        )
                        return@withContext runPredict(cleanBase, clinicalReport, patchesBase64, clientRequestId = requestId)
                    } else {
                        val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                        conn.disconnect()
                        lastChunkError = Exception("Server error HTTP $code: $errBody")
                    }
                } catch (e: Exception) {
                    lastChunkError = e
                }

                if (attempt < maxRetriesPerChunk) {
                    onProgress?.invoke(
                        completedTiles,
                        totalTiles,
                        "Chunk retry $attempt/$maxRetriesPerChunk at tiles $chunkStartTile–$chunkEndTile..."
                    )
                    delay(800L * attempt)
                }
            }

            if (!chunkSuccess) {
                return@withContext Result.failure(
                    Exception(
                        "Tile embedding stalled at $completedTiles/$totalTiles tiles after $maxRetriesPerChunk attempts: ${lastChunkError?.localizedMessage}. Please verify server connection and retry."
                    )
                )
            }
        }

        // Stage 2: Sequence Cross-Attention Fusion
        onProgress?.invoke(
            totalTiles,
            totalTiles,
            "Fusing multimodal sequence ($totalTiles patches × ClinicalBERT)..."
        )

        var fuseConnection: HttpURLConnection? = null
        try {
            val fuseUrl = URL("$cleanBase/fuse")
            fuseConnection = (fuseUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 60000
            }

            val fusePayload = JSONObject().apply {
                put("request_id", requestId)
                put("session_id", requestId)
                val visionArray = JSONArray()
                for (emb in allEmbeddings) {
                    val vecArr = JSONArray()
                    for (v in emb) vecArr.put(v)
                    visionArray.put(vecArr)
                }
                put("vision_embeddings", visionArray)
                put("clinical_report", clinicalReport)
                if (accumulatedVisionMs > 0.0) {
                    put("accumulated_vision_inference_ms", accumulatedVisionMs)
                    put("vision_inference_ms", accumulatedVisionMs)
                }
            }

            OutputStreamWriter(fuseConnection.outputStream, "UTF-8").use {
                it.write(fusePayload.toString())
                it.flush()
            }

            val fuseCode = fuseConnection.responseCode
            val totalLatencyMs = System.currentTimeMillis() - startTime

            if (fuseCode == 200) {
                val responseText = fuseConnection.inputStream.bufferedReader().use { it.readText() }
                Log.i("DIAGNOSTIC_TRACE", "[STAGE 1 - Raw HTTP Response] endpoint=$cleanBase/fuse, code=$fuseCode, len=${responseText.length}, bodyPrefix=${responseText.take(200)}")
                sendDiagnosticTrace(cleanBase, 1, "Raw HTTP Response", mapOf("endpoint" to "$cleanBase/fuse", "code" to fuseCode, "len" to responseText.length, "has_total_timing" to responseText.contains("total_model_inference_ms"), "has_inf_lat" to responseText.contains("inference_latency_ms")))

                val json = JSONObject(responseText)
                val prob = json.getDouble("probability").toFloat()
                if (!prob.isFinite()) {
                    return@withContext Result.failure(Exception("Inference returned non-finite probability value"))
                }

                val respRequestId = json.optString("request_id", requestId)
                val rawLogit = if (json.has("raw_logit")) json.optDouble("raw_logit") else null
                val reportSha256 = json.optString("report_sha256").takeIf { it.isNotBlank() }
                val isSampleReport = if (json.has("is_sample_report")) json.optBoolean("is_sample_report") else null
                val tileCount = if (json.has("tile_count")) json.optInt("tile_count") else totalTiles

                val parsedTotal = optValidDouble(json, "total_model_inference_ms")
                    ?: optValidDouble(json, "inference_latency_ms")
                    ?: optValidDouble(json, "model_inference_ms")
                val visionInferenceMs = optValidDouble(json, "vision_inference_ms")
                    ?: if (accumulatedVisionMs > 0.0) accumulatedVisionMs else null
                val clinicalTextInferenceMs = optValidDouble(json, "clinical_text_inference_ms")
                val fusionClassificationMs = optValidDouble(json, "fusion_classification_ms")
                val inferenceTimingScope = json.optString("inference_timing_scope").takeIf { it.isNotBlank() }

                var totalModelInferenceMs = parsedTotal
                if (totalModelInferenceMs == null) {
                    val stageMs = (clinicalTextInferenceMs ?: 0.0) + (fusionClassificationMs ?: 0.0)
                    if (accumulatedVisionMs > 0.0) {
                        totalModelInferenceMs = accumulatedVisionMs + stageMs
                    } else if (stageMs > 0.0) {
                        totalModelInferenceMs = stageMs
                    }
                }

                Log.i(
                    "DIAGNOSTIC_TRACE",
                    "[STAGE 2 - JSON Timing Extracted] raw_total=${json.opt("total_model_inference_ms")}, parsedTotal=$parsedTotal, visionInferenceMs=$visionInferenceMs, clinicalTextInferenceMs=$clinicalTextInferenceMs, fusionClassificationMs=$fusionClassificationMs, accumulatedVisionMs=$accumulatedVisionMs, computedTotalModelInferenceMs=$totalModelInferenceMs"
                )
                sendDiagnosticTrace(cleanBase, 2, "JSON Timing Extracted", mapOf("endpoint" to "$cleanBase/fuse", "raw_total" to json.opt("total_model_inference_ms"), "parsedTotal" to parsedTotal, "visionInferenceMs" to visionInferenceMs, "clinicalTextInferenceMs" to clinicalTextInferenceMs, "fusionClassificationMs" to fusionClassificationMs, "accumulatedVisionMs" to accumulatedVisionMs, "computedTotalModelInferenceMs" to totalModelInferenceMs))

                val visionShape = mutableListOf<Int>()
                json.optJSONArray("vision_shape")?.let { arr ->
                    for (i in 0 until arr.length()) visionShape.add(arr.getInt(i))
                }

                val clinicalShape = mutableListOf<Int>()
                json.optJSONArray("clinical_shape")?.let { arr ->
                    for (i in 0 until arr.length()) clinicalShape.add(arr.getInt(i))
                }

                val attentionWeights = mutableListOf<Float>()
                json.optJSONArray("attention_weights")?.let { arr ->
                    var allValid = true
                    val temp = mutableListOf<Float>()
                    for (i in 0 until arr.length()) {
                        val w = arr.optDouble(i, Double.NaN).toFloat()
                        if (w.isFinite() && w >= 0f) {
                            temp.add(w)
                        } else {
                            allValid = false
                            break
                        }
                    }
                    if (allValid && temp.size == arr.length()) {
                        attentionWeights.addAll(temp)
                    }
                }

                Log.i(
                    "DIAGNOSTIC_TRACE",
                    "[STAGE 3 - Kotlin Response Model Populated] totalModelInferenceMs=$totalModelInferenceMs, visionInferenceMs=$visionInferenceMs, clinicalTextInferenceMs=$clinicalTextInferenceMs, fusionClassificationMs=$fusionClassificationMs, latencyMs=$totalLatencyMs"
                )
                sendDiagnosticTrace(cleanBase, 3, "Kotlin Response Model Populated", mapOf("endpoint" to "$cleanBase/fuse", "totalModelInferenceMs" to totalModelInferenceMs, "visionInferenceMs" to visionInferenceMs, "clinicalTextInferenceMs" to clinicalTextInferenceMs, "fusionClassificationMs" to fusionClassificationMs, "latencyMs" to totalLatencyMs))

                Log.i(
                    "InferenceApiClient",
                    "[INFERENCE_CLIENT_DIAGNOSTIC] endpoint=$cleanBase/fuse, requestId=$respRequestId, tiles=$tileCount, prob=$prob, rawLogit=$rawLogit, latencyMs=$totalLatencyMs, totalModelInferenceMs=$totalModelInferenceMs"
                )

                onProgress?.invoke(totalTiles, totalTiles, "Multimodal inference complete.")
                Result.success(
                    InferenceResponseData(
                        probability = prob,
                        visionShape = visionShape,
                        clinicalShape = clinicalShape,
                        latencyMs = totalLatencyMs,
                        serverUrl = "$cleanBase/fuse",
                        attentionWeights = attentionWeights,
                        requestId = respRequestId,
                        tileCount = tileCount,
                        reportSha256 = reportSha256,
                        rawLogit = rawLogit,
                        isSampleReport = isSampleReport,
                        totalModelInferenceMs = totalModelInferenceMs,
                        visionInferenceMs = visionInferenceMs,
                        clinicalTextInferenceMs = clinicalTextInferenceMs,
                        fusionClassificationMs = fusionClassificationMs,
                        inferenceTimingScope = inferenceTimingScope
                    )
                )
            } else if (fuseCode == 404) {
                // If /fuse is missing (e.g. server only implements /predict), fall back to direct /predict
                onProgress?.invoke(
                    0,
                    totalTiles,
                    "Two-stage /fuse endpoint returned HTTP 404; falling back to direct /predict ($totalTiles tiles)..."
                )
                return@withContext runPredict(cleanBase, clinicalReport, patchesBase64, clientRequestId = requestId)
            } else {
                val errorStream = fuseConnection.errorStream
                val errorBody = errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $fuseCode"
                Result.failure(Exception("Fusion error (HTTP $fuseCode): $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            fuseConnection?.disconnect()
        }
    }

    /**
     * Generates a genuine Phikon-v2 ViT attention rollout heatmap for a candidate tile image.
     * Returns a base64-encoded PNG string.
     */
    suspend fun generateHeatmap(
        baseUrl: String,
        patchBase64: String
    ): Result<String> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val cleanBase = baseUrl.trim().removeSuffix("/").removeSuffix("/predict").removeSuffix("/generate_heatmap").removeSuffix("/generate_summary").removeSuffix("/embed_tiles").removeSuffix("/fuse").removeSuffix("/health").removeSuffix("/")
            val url = URL("$cleanBase/generate_heatmap")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 60000
            }

            val payload = JSONObject().apply {
                put("patch", patchBase64)
            }

            OutputStreamWriter(connection.outputStream, "UTF-8").use {
                it.write(payload.toString())
                it.flush()
            }

            val code = connection.responseCode
            if (code == 200) {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val heatmapB64 = json.getString("heatmap_base64")
                Result.success(heatmapB64)
            } else {
                val errBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                Result.failure(Exception("Heatmap generation error ($code): $errBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Generates an AI diagnostic clinical summary (Ollama LLM or senior deterministic scribe).
     * Returns SummaryResponseData with source and model details.
     */
    suspend fun generateSummary(
        baseUrl: String,
        confidence: Float,
        isMsi: Boolean,
        clinicalReport: String,
        topScores: List<Float> = emptyList(),
        allowFallback: Boolean = true,
        caseId: String? = null,
        specimenSource: String? = null,
        timeoutMs: Int = 120_000
    ): Result<SummaryResponseData> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        val startTime = System.currentTimeMillis()
        Log.d("InferenceApiClient", "generateSummary started: caseId=${caseId ?: "N/A"}, isMsi=$isMsi, prob=$confidence, topScoresCount=${topScores.size}, allowFallback=$allowFallback, timeoutMs=$timeoutMs")

        try {
            val cleanBase = baseUrl.trim().removeSuffix("/").removeSuffix("/predict").removeSuffix("/generate_heatmap").removeSuffix("/generate_summary").removeSuffix("/llm_status").removeSuffix("/embed_tiles").removeSuffix("/fuse").removeSuffix("/health").removeSuffix("/")
            val url = URL("$cleanBase/generate_summary")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                doOutput = true
                connectTimeout = 15000
                readTimeout = timeoutMs
            }

            val payload = JSONObject().apply {
                put("confidence", confidence.toDouble())
                put("is_msi", isMsi)
                put("clinical_report", clinicalReport)
                put("allow_fallback", allowFallback)
                if (!caseId.isNullOrBlank()) {
                    put("case_id", caseId)
                }
                if (!specimenSource.isNullOrBlank()) {
                    put("specimen_source", specimenSource)
                }
                val arr = JSONArray()
                topScores.forEach { arr.put(it.toDouble()) }
                put("top_scores", arr)
            }

            OutputStreamWriter(connection.outputStream, "UTF-8").use {
                it.write(payload.toString())
                it.flush()
            }

            val code = connection.responseCode
            val elapsed = System.currentTimeMillis() - startTime
            if (code == 200) {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val summary = json.getString("summary")
                val modelUsed = json.optString("model_used", "AI Clinical Scribe")
                val source = json.optString("source", "deterministic_template_fallback")
                val isLlm = json.optBoolean("is_llm", false)
                val errorDetail = if (json.has("error_detail") && !json.isNull("error_detail")) json.getString("error_detail") else null
                Log.d("InferenceApiClient", "generateSummary succeeded in ${elapsed}ms: source=$source, model=$modelUsed, isLlm=$isLlm")
                Result.success(
                    SummaryResponseData(
                        summary = summary,
                        source = source,
                        modelUsed = modelUsed,
                        isLlm = isLlm,
                        errorDetail = errorDetail
                    )
                )
            } else {
                val errBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                Log.w("InferenceApiClient", "generateSummary HTTP $code in ${elapsed}ms: $errBody")
                Result.failure(Exception("Clinical summary error ($code): $errBody"))
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.w("InferenceApiClient", "generateSummary failed after ${elapsed}ms: ${e.javaClass.simpleName} - ${e.message}")
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Checks LLM scribe service health, loaded models, and diagnostic setup instructions.
     */
    suspend fun checkLlmHealth(baseUrl: String): Result<LlmHealthData> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val cleanBase = baseUrl.trim().removeSuffix("/").removeSuffix("/predict").removeSuffix("/generate_heatmap").removeSuffix("/generate_summary").removeSuffix("/llm_status").removeSuffix("/embed_tiles").removeSuffix("/fuse").removeSuffix("/health").removeSuffix("/")
            val url = URL("$cleanBase/llm_status")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            val code = connection.responseCode
            if (code == 200) {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val instructions = mutableListOf<String>()
                json.optJSONArray("setup_instructions")?.let { arr ->
                    for (i in 0 until arr.length()) instructions.add(arr.getString(i))
                }
                val models = mutableListOf<String>()
                json.optJSONArray("available_models")?.let { arr ->
                    for (i in 0 until arr.length()) models.add(arr.getString(i))
                }
                Result.success(
                    LlmHealthData(
                        status = json.optString("status", "unknown"),
                        provider = json.optString("provider", "ollama"),
                        model = json.optString("model", "gemma3:1b"),
                        modelAvailable = json.optBoolean("model_available", false),
                        availableModels = models,
                        error = if (json.has("error") && !json.isNull("error")) json.getString("error") else null,
                        setupInstructions = instructions
                    )
                )
            } else {
                Result.failure(Exception("LLM diagnostic returned HTTP $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }
}
