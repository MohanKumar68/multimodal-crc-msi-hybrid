package com.cancerdetection.multimodal.research.data.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import com.cancerdetection.multimodal.research.domain.model.IngestionStatus
import com.cancerdetection.multimodal.research.domain.model.SpecimenCategory
import com.cancerdetection.multimodal.research.domain.model.SpecimenImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

object SpecimenIngestionManager {

    // Maximum allowed selection limit for the manual individual image picker
    const val MAX_SINGLE_SELECTION_TILES = 100

    // Retained for backward compatibility
    const val MAX_SUPPORTED_TILES = 100

    // Verified supported whole-slide image formats (retained for metadata validation)
    val SUPPORTED_WSI_EXTENSIONS = setOf("svs", "tif", "tiff", "ndpi", "mrxs", "scn")

    // Verified supported ordinary histopathology tile formats
    val SUPPORTED_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

    // Maximum practical file size for in-memory ordinary tiles on mobile (50 MB)
    const val MAX_TILE_SIZE_BYTES = 50 * 1024 * 1024L

    // Maximum practical whole-slide file size accepted for ingestion reference (8 GB)
    const val MAX_WSI_SIZE_BYTES = 8L * 1024 * 1024 * 1024

    data class IngestionReport(
        val totalScanned: Int,
        val validAccepted: Int,
        val skippedNonImages: Int,
        val unreadableFiles: List<UnreadableFile> = emptyList()
    )

    data class UnreadableFile(
        val filename: String,
        val reason: String
    )

    data class RawFileInfo(
        val name: String?,
        val isDirectory: Boolean,
        val uriString: String,
        val mimeType: String?,
        val length: Long
    )

    sealed class FolderIngestionResult {
        data class Success(
            val images: List<SpecimenImage>,
            val report: IngestionReport,
            val folderName: String,
            val exceededLimit: Boolean = false
        ) : FolderIngestionResult() {
            // Helpers for backward compatibility
            val totalFound: Int get() = report.validAccepted
            val skippedNonImages: Int get() = report.skippedNonImages
            val totalScanned: Int get() = report.totalScanned
            val unreadableFiles: List<UnreadableFile> get() = report.unreadableFiles
        }

        data class Empty(
            val folderName: String,
            val totalFilesChecked: Int
        ) : FolderIngestionResult()

        data class UnsupportedProvider(
            val message: String
        ) : FolderIngestionResult()

        data class Error(
            val message: String
        ) : FolderIngestionResult()
    }

    /**
     * Natural alphanumeric comparator for filenames (e.g. tile_1.jpg before tile_10.jpg).
     * Ensures deterministic ordering for 1:1 attention weight mapping.
     */
    fun naturalCompare(s1: String, s2: String): Int {
        var i1 = 0
        var i2 = 0
        while (i1 < s1.length && i2 < s2.length) {
            val c1 = s1[i1]
            val c2 = s2[i2]
            if (c1.isDigit() && c2.isDigit()) {
                var j1 = i1
                while (j1 < s1.length && s1[j1].isDigit()) j1++
                var j2 = i2
                while (j2 < s2.length && s2[j2].isDigit()) j2++
                val num1 = s1.substring(i1, j1)
                val num2 = s2.substring(i2, j2)
                val diff = num1.toBigIntegerOrNull()?.compareTo(num2.toBigIntegerOrNull() ?: java.math.BigInteger.ZERO)
                    ?: num1.compareTo(num2)
                if (diff != 0) return diff
                i1 = j1
                i2 = j2
            } else {
                val diff = c1.lowercaseChar().compareTo(c2.lowercaseChar())
                if (diff != 0) return diff
                i1++
                i2++
            }
        }
        return s1.length.compareTo(s2.length)
    }

    /**
     * Incrementally processes an iterable/sequence of raw file information into valid SpecimenImage
     * objects without loading raw bitmaps into memory (lightweight metadata only).
     * No fixed 100-tile cap is enforced unless maxTiles is explicitly specified.
     */
    fun processRawFiles(
        files: Sequence<RawFileInfo>,
        maxTiles: Int? = null
    ): Pair<List<SpecimenImage>, IngestionReport> {
        var totalScanned = 0
        var skippedNonImages = 0
        val unreadableFiles = mutableListOf<UnreadableFile>()
        val validTiles = mutableListOf<SpecimenImage>()
        val seenFilenames = mutableSetOf<String>()

        for (file in files) {
            totalScanned++
            if (file.isDirectory) {
                skippedNonImages++
                continue
            }

            val name = file.name
            if (name.isNullOrBlank()) {
                unreadableFiles.add(UnreadableFile("unknown", "Missing or unreadable filename"))
                continue
            }

            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (!SUPPORTED_IMAGE_EXTENSIONS.contains(ext)) {
                skippedNonImages++
                continue
            }

            if (seenFilenames.contains(name)) {
                // Deduplicate tiles with identical filenames within the same folder
                continue
            }
            seenFilenames.add(name)

            val sizeBytes = file.length
            if (sizeBytes <= 0) {
                unreadableFiles.add(UnreadableFile(name, "File is empty (0 bytes)"))
                continue
            }

            if (sizeBytes > MAX_TILE_SIZE_BYTES) {
                unreadableFiles.add(UnreadableFile(name, "File size (${formatFileSize(sizeBytes)}) exceeds 50MB limit"))
                continue
            }

            validTiles.add(
                SpecimenImage(
                    uriString = file.uriString,
                    displayName = name,
                    fileSizeBytes = sizeBytes,
                    mimeType = file.mimeType ?: "image/$ext",
                    extension = ext,
                    category = SpecimenCategory.ORDINARY_IMAGE,
                    ingestionStatus = IngestionStatus.READY,
                    statusMessage = "Pre-extracted patch tile (${formatFileSize(sizeBytes)})"
                )
            )
        }

        // Natural alphanumeric sort by displayName for deterministic 1:1 attention mapping
        validTiles.sortWith { a, b -> naturalCompare(a.displayName, b.displayName) }

        val finalTiles = if (maxTiles != null && maxTiles > 0 && validTiles.size > maxTiles) {
            validTiles.take(maxTiles)
        } else {
            validTiles
        }

        val report = IngestionReport(
            totalScanned = totalScanned,
            validAccepted = finalTiles.size,
            skippedNonImages = skippedNonImages,
            unreadableFiles = unreadableFiles
        )

        return Pair(finalTiles, report)
    }

    /**
     * Ingests pre-extracted histopathology tiles from an Android Document Tree (Folder).
     * Validates provider compatibility, streams file entries without decoding bitmaps,
     * rejects non-images/corrupt files, deduplicates, and deterministically sorts tiles.
     * Unlike manual single-image selection, folder ingestion does NOT truncate at 100 tiles.
     */
    fun ingestFolder(
        context: Context,
        treeUri: Uri,
        maxTiles: Int? = null
    ): FolderIngestionResult {
        return try {
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            if (rootDoc == null || !rootDoc.canRead()) {
                return FolderIngestionResult.UnsupportedProvider(
                    "The selected storage provider does not permit folder browsing. Please use 'Select Images' to pick files directly."
                )
            }

            val folderName = rootDoc.name ?: "Selected Folder"
            val allFiles = try {
                rootDoc.listFiles()
            } catch (e: Exception) {
                return FolderIngestionResult.UnsupportedProvider(
                    "Unable to read folder contents with this provider (${e.localizedMessage ?: "access restricted"}). Please use individual tile selection."
                )
            }

            if (allFiles.isEmpty()) {
                return FolderIngestionResult.Empty(folderName, 0)
            }

            val fileSequence = allFiles.asSequence().map { file ->
                RawFileInfo(
                    name = file.name,
                    isDirectory = file.isDirectory,
                    uriString = file.uri.toString(),
                    mimeType = file.type,
                    length = try { file.length() } catch (_: Exception) { -1L }
                )
            }

            val (validTiles, report) = processRawFiles(fileSequence, maxTiles)

            if (validTiles.isEmpty()) {
                return FolderIngestionResult.Empty(folderName, report.totalScanned)
            }

            FolderIngestionResult.Success(
                images = validTiles,
                report = report,
                folderName = folderName,
                exceededLimit = maxTiles != null && report.validAccepted > maxTiles
            )
        } catch (se: SecurityException) {
            FolderIngestionResult.Error("Storage permission denied: ${se.localizedMessage}")
        } catch (e: Exception) {
            FolderIngestionResult.Error("Failed to ingest folder: ${e.localizedMessage}")
        }
    }

    /**
     * Inspects a selected Uri via ContentResolver, extracts technical metadata,
     * and categorizes the specimen into Ordinary Image or Whole-Slide Image.
     */
    fun inspectUri(context: Context, uri: Uri): SpecimenImage {
        var displayName = "Specimen_${System.currentTimeMillis() % 100000}"
        var sizeBytes = 0L
        val mimeType = context.contentResolver.getType(uri)

        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)

                if (nameIndex != -1 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)?.let { displayName = it }
                }
                if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                    sizeBytes = cursor.getLong(sizeIndex)
                }
            }
        } catch (_: Exception) {
            // Fallback to last path segment if query fails
            uri.lastPathSegment?.let { displayName = it }
        }

        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)

        val (category, status, message) = when {
            SUPPORTED_IMAGE_EXTENSIONS.contains(extension) -> {
                if (sizeBytes > MAX_TILE_SIZE_BYTES) {
                    Triple(
                        SpecimenCategory.ORDINARY_IMAGE,
                        IngestionStatus.FILE_TOO_LARGE,
                        "Tile size (${formatFileSize(sizeBytes)}) exceeds 50MB limit"
                    )
                } else {
                    Triple(
                        SpecimenCategory.ORDINARY_IMAGE,
                        IngestionStatus.READY,
                        "Ready for patch embedding (224×224 Phikon-v2)"
                    )
                }
            }
            SUPPORTED_WSI_EXTENSIONS.contains(extension) -> {
                if (sizeBytes > MAX_WSI_SIZE_BYTES) {
                    Triple(
                        SpecimenCategory.WHOLE_SLIDE_IMAGE,
                        IngestionStatus.FILE_TOO_LARGE,
                        "Slide file exceeds 8GB maximum"
                    )
                } else {
                    val formatLabel = when (extension) {
                        "svs" -> "Aperio WSI"
                        "ndpi" -> "Hamamatsu WSI"
                        "tif", "tiff" -> "BigTIFF / TIF WSI"
                        "mrxs" -> "3DHISTECH WSI"
                        "scn" -> "Leica WSI"
                        else -> "Whole-Slide Image"
                    }
                    Triple(
                        SpecimenCategory.WHOLE_SLIDE_IMAGE,
                        IngestionStatus.READY,
                        "$formatLabel (${formatFileSize(sizeBytes)}) — Dispatched to backend OpenSlide pipeline"
                    )
                }
            }
            else -> {
                Triple(
                    SpecimenCategory.UNSUPPORTED,
                    IngestionStatus.UNSUPPORTED_FORMAT,
                    "Unsupported format: .$extension (Allowed: JPG, PNG, WEBP, SVS, NDPI, TIF, MRXS, SCN)"
                )
            }
        }

        return SpecimenImage(
            uriString = uri.toString(),
            displayName = displayName,
            fileSizeBytes = sizeBytes,
            mimeType = mimeType,
            extension = extension,
            category = category,
            ingestionStatus = status,
            statusMessage = message
        )
    }

    /**
     * Safely reads an ordinary image tile from Uri, downsamples to 224x224 (Phikon-v2 input size),
     * and encodes to Base64 without loading excessive raw memory into the Android heap.
     */
    suspend fun encodeTileToBase64(context: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            // First decode bounds only to calculate inSampleSize
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            // Downsample power-of-two to avoid large heap allocation
            val targetSize = 224
            var inSampleSize = 1
            if (options.outHeight > targetSize || options.outWidth > targetSize) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while ((halfHeight / inSampleSize) >= targetSize && (halfWidth / inSampleSize) >= targetSize) {
                    inSampleSize *= 2
                }
            }

            // Decode actual downsampled bitmap with full 32-bit ARGB color fidelity
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888 // 4 bytes per pixel preserving full 24-bit histology color depth
            }
            val originalBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return@withContext Result.failure(Exception("Could not decode image stream"))

            // Scale precisely to 224x224 for Phikon-v2 model input contract
            val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, 224, 224, true)
            if (scaledBitmap != originalBitmap) {
                originalBitmap.recycle()
            }

            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            val bytes = outputStream.toByteArray()
            scaledBitmap.recycle()

            val base64String = Base64.encodeToString(bytes, Base64.NO_WRAP)
            Result.success(base64String)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes <= 0 -> "0 B"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    data class TileMetadataAnalysis(
        val detectedCaseId: String? = null,
        val detectedSlideId: String? = null,
        val uniqueCaseIds: Set<String> = emptySet(),
        val uniqueSlideIds: Set<String> = emptySet(),
        val tileCount: Int = 0,
        val isConsistent: Boolean = true,
        val identityStatus: String = "No specimen tiles selected"
    )

    private val TCGA_CASE_REGEX = Regex("""\b(TCGA-[A-Z0-9]{2}-[A-Z0-9]{4})\b""", RegexOption.IGNORE_CASE)
    private val TCGA_SLIDE_REGEX = Regex("""\b(TCGA-[A-Z0-9]{2}-[A-Z0-9]{4}-[A-Z0-9]{2}[A-Z]-[A-Z0-9]{2}-[A-Z0-9]{3})\b""", RegexOption.IGNORE_CASE)

    fun extractTileMetadataFromNames(displayNames: List<String>): TileMetadataAnalysis {
        if (displayNames.isEmpty()) {
            return TileMetadataAnalysis(
                tileCount = 0,
                identityStatus = "No specimen tiles selected"
            )
        }

        val caseIds = mutableSetOf<String>()
        val slideIds = mutableSetOf<String>()

        for (name in displayNames) {
            val slideMatch = TCGA_SLIDE_REGEX.find(name)
            if (slideMatch != null) {
                slideIds.add(slideMatch.value.uppercase())
            }
            val caseMatch = TCGA_CASE_REGEX.find(name)
            if (caseMatch != null) {
                caseIds.add(caseMatch.value.uppercase())
            }
        }

        return when {
            caseIds.size == 1 -> {
                val caseId = caseIds.first()
                val slideId = slideIds.firstOrNull()
                TileMetadataAnalysis(
                    detectedCaseId = caseId,
                    detectedSlideId = slideId,
                    uniqueCaseIds = caseIds,
                    uniqueSlideIds = slideIds,
                    tileCount = displayNames.size,
                    isConsistent = true,
                    identityStatus = if (slideId != null) {
                        "Verified from tile metadata: $caseId (Slide: $slideId)"
                    } else {
                        "Verified from tile metadata: $caseId"
                    }
                )
            }
            caseIds.size > 1 -> {
                TileMetadataAnalysis(
                    detectedCaseId = null,
                    detectedSlideId = null,
                    uniqueCaseIds = caseIds,
                    uniqueSlideIds = slideIds,
                    tileCount = displayNames.size,
                    isConsistent = false,
                    identityStatus = "Conflicting metadata: Multiple case IDs found in tiles (${caseIds.sorted().joinToString()})"
                )
            }
            else -> {
                TileMetadataAnalysis(
                    detectedCaseId = null,
                    detectedSlideId = null,
                    uniqueCaseIds = emptySet(),
                    uniqueSlideIds = emptySet(),
                    tileCount = displayNames.size,
                    isConsistent = true,
                    identityStatus = "User-supplied image tiles (Case identity unverified)"
                )
            }
        }
    }

    fun extractTileMetadata(images: List<SpecimenImage>): TileMetadataAnalysis {
        return extractTileMetadataFromNames(images.map { it.displayName })
    }
}
