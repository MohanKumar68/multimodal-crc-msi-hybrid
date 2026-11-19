package com.cancerdetection.multimodal.research.data.storage

import android.content.Context
import com.cancerdetection.multimodal.research.domain.model.ResearchCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class LocalCaseStorage(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val storageFile: File
        get() = File(context.filesDir, "research_cases_store.json")

    suspend fun loadCases(): List<ResearchCase> = withContext(Dispatchers.IO) {
        try {
            if (!storageFile.exists()) {
                return@withContext emptyList()
            }
            val content = storageFile.readText()
            if (content.isBlank()) {
                return@withContext emptyList()
            }
            json.decodeFromString<List<ResearchCase>>(content)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun saveCases(cases: List<ResearchCase>) = withContext(Dispatchers.IO) {
        try {
            val content = json.encodeToString(cases)
            storageFile.writeText(content)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
