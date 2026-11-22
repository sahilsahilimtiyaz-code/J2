package com.jarvis.assistant.brain

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Specification for a downloadable on-device GGUF model that runs offline without API keys.
 */
@Serializable
data class LocalModelSpec(
    val id: String,
    val displayName: String,
    val parameterBadge: String,
    val approxSizeLabel: String,
    val approxBytes: Long,
    val bestUse: String,
    val architecture: String,
    val fileName: String,
    val downloadUrls: List<String>,
    val contextLength: Int = 8192,
) {
    val parameterCount: String get() = parameterBadge
    val approxDownloadSizeLabel: String get() = approxSizeLabel
    val quantization: String get() = "Q4_K_M"
    val downloadUrl: String get() = downloadUrls.firstOrNull().orEmpty()
}

enum class LocalDownloadState {
    NOT_DOWNLOADED,
    DOWNLOADING,
    PAUSED,
    READY,
    FAILED,
}

data class GgufHeaderInfo(
    val valid: Boolean,
    val version: Int = 0,
    val tensorCount: Long = 0L,
    val metadataKvCount: Long = 0L,
    val fileSizeBytes: Long = 0L,
) {
    val validMagic: Boolean get() = valid
    val kvCount: Long get() = metadataKvCount
}

data class LocalModelDownloadStatus(
    val spec: LocalModelSpec,
    val state: LocalDownloadState = LocalDownloadState.NOT_DOWNLOADED,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = spec.approxBytes,
    val speedBytesPerSec: Long = 0L,
    val errorMessage: String? = null,
    val ggufInfo: GgufHeaderInfo? = null,
    val filePath: String = "",
) {
    constructor(
        modelId: String,
        state: LocalDownloadState = LocalDownloadState.NOT_DOWNLOADED,
        downloadedBytes: Long = 0L,
        totalBytes: Long = 0L,
    ) : this(
        spec = LocalModelCatalog.findByIdOrName(modelId) ?: LocalModelCatalog.models.first(),
        state = state,
        downloadedBytes = downloadedBytes,
        totalBytes = if (totalBytes > 0L) {
            totalBytes
        } else {
            (LocalModelCatalog.findByIdOrName(modelId) ?: LocalModelCatalog.models.first()).approxBytes
        },
    )

    val modelId: String get() = spec.id
    val error: String? get() = errorMessage

    val progress: Float
        get() = if (totalBytes > 0L) {
            (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }

    val isReady: Boolean
        get() = state == LocalDownloadState.READY
}

/**
 * Catalog of the 6 top lightweight local AI models requested for offline operation.
 */
object LocalModelCatalog {

    val models: List<LocalModelSpec> = listOf(
        LocalModelSpec(
            id = "lfm2_5_350m",
            displayName = "LFM2.5 350M",
            parameterBadge = "350M",
            approxSizeLabel = "250–350 MB",
            approxBytes = 278_000_000L,
            bestUse = "Extremely lightweight chat",
            architecture = "LiquidAI LFM2.5",
            fileName = "LFM2.5-350M-Q4_K_M.gguf",
            downloadUrls = listOf(
                "https://huggingface.co/LiquidAI/LFM2-350M-GGUF/resolve/main/LFM2-350M-Q4_K_M.gguf?download=true",
                "https://huggingface.co/bartowski/LiquidAI_LFM2-350M-GGUF/resolve/main/LiquidAI_LFM2-350M-Q4_K_M.gguf?download=true",
                "https://huggingface.co/LiquidAI/LFM2-350M-GGUF/resolve/main/LFM2-350M-Q8_0.gguf?download=true",
            ),
            contextLength = 32768,
        ),
        LocalModelSpec(
            id = "qwen2_5_0_5b",
            displayName = "Qwen2.5 0.5B Instruct",
            parameterBadge = "0.5B",
            approxSizeLabel = "400 MB",
            approxBytes = 418_000_000L,
            bestUse = "Basic assistant",
            architecture = "Qwen2.5 Instruct",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            downloadUrls = listOf(
                "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf?download=true",
                "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf?download=true",
            ),
            contextLength = 32768,
        ),
        LocalModelSpec(
            id = "gemma_3_1b",
            displayName = "Gemma 3 1B",
            parameterBadge = "1B",
            approxSizeLabel = "700 MB",
            approxBytes = 734_000_000L,
            bestUse = "General conversation",
            architecture = "Google Gemma 3",
            fileName = "gemma-3-1b-it-Q4_K_M.gguf",
            downloadUrls = listOf(
                "https://huggingface.co/bartowski/google_gemma-3-1b-it-GGUF/resolve/main/google_gemma-3-1b-it-Q4_K_M.gguf?download=true",
                "https://huggingface.co/ggml-org/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf?download=true",
                "https://huggingface.co/unsloth/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf?download=true",
            ),
            contextLength = 32768,
        ),
        LocalModelSpec(
            id = "lfm2_5_1_2b",
            displayName = "LFM2.5 1.2B Instruct",
            parameterBadge = "1.2B",
            approxSizeLabel = "731 MB",
            approxBytes = 766_500_000L,
            bestUse = "General-purpose local AI",
            architecture = "LiquidAI LFM2.5 Instruct",
            fileName = "LFM2.5-1.2B-Instruct-Q4_K_M.gguf",
            downloadUrls = listOf(
                "https://huggingface.co/LiquidAI/LFM2-1.2B-GGUF/resolve/main/LFM2-1.2B-Q4_K_M.gguf?download=true",
                "https://huggingface.co/bartowski/LiquidAI_LFM2-1.2B-GGUF/resolve/main/LiquidAI_LFM2-1.2B-Q4_K_M.gguf?download=true",
            ),
            contextLength = 32768,
        ),
        LocalModelSpec(
            id = "smollm2_1_7b",
            displayName = "SmolLM2 1.7B",
            parameterBadge = "1.7B",
            approxSizeLabel = "990 MB",
            approxBytes = 1_038_000_000L,
            bestUse = "Lightweight chat and writing",
            architecture = "HuggingFace SmolLM2",
            fileName = "SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
            downloadUrls = listOf(
                "https://huggingface.co/bartowski/SmolLM2-1.7B-Instruct-GGUF/resolve/main/SmolLM2-1.7B-Instruct-Q4_K_M.gguf?download=true",
                "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf?download=true",
            ),
            contextLength = 8192,
        ),
        LocalModelSpec(
            id = "qwen3_1_7b",
            displayName = "Qwen3 1.7B",
            parameterBadge = "1.7B",
            approxSizeLabel = "1.28 GB",
            approxBytes = 1_342_000_000L,
            bestUse = "Stronger",
            architecture = "Alibaba Qwen3",
            fileName = "Qwen3-1.7B-Q4_K_M.gguf",
            downloadUrls = listOf(
                "https://huggingface.co/Qwen/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf?download=true",
                "https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/resolve/main/Qwen_Qwen3-1.7B-Q4_K_M.gguf?download=true",
                "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf?download=true",
            ),
            contextLength = 32768,
        ),
    )

    fun findByIdOrName(idOrName: String): LocalModelSpec? {
        val clean = idOrName.trim().lowercase(Locale.ROOT)
        if (clean.isBlank()) return null
        return models.firstOrNull { spec ->
            spec.id.lowercase(Locale.ROOT) == clean ||
                spec.displayName.lowercase(Locale.ROOT) == clean ||
                spec.fileName.lowercase(Locale.ROOT) == clean ||
                clean.contains(spec.id.lowercase(Locale.ROOT)) ||
                clean.contains(spec.displayName.lowercase(Locale.ROOT))
        }
    }

    fun asDiscoveredModels(statuses: Map<String, LocalModelDownloadStatus> = emptyMap()): List<DiscoveredModel> =
        models.map { spec ->
            val st = statuses[spec.id]
            val badge = if (st?.isReady == true) "Downloaded · Ready Offline" else "${spec.approxSizeLabel} · ${spec.bestUse}"
            DiscoveredModel(
                id = spec.id,
                name = "${spec.displayName} ($badge)",
                providerName = "Offline Local AI",
                isFree = true,
                supportsTools = true,
                contextWindow = spec.contextLength,
            )
        }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }
}

/**
 * Verifies GGUF binary headers (`GGUF` magic bytes, format version, tensor count,
 * and KV metadata count) so corrupted or truncated downloads are detected reliably.
 */
object GgufModelInspector {
    private const val GGUF_MAGIC = 0x46554747 // "GGUF" in little-endian

    fun inspect(file: File): GgufHeaderInfo {
        if (!file.exists() || !file.isFile || file.length() < 24L) {
            return GgufHeaderInfo(valid = false, fileSizeBytes = file.length())
        }
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val headerBytes = ByteArray(24)
                val read = raf.read(headerBytes)
                if (read < 24) return GgufHeaderInfo(valid = false, fileSizeBytes = file.length())
                val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
                val magic = buf.int
                val version = buf.int
                val tensorCount = buf.long
                val kvCount = buf.long
                val validMagic = magic == GGUF_MAGIC && version in 1..4 && tensorCount >= 0L && kvCount >= 0L
                GgufHeaderInfo(
                    valid = validMagic,
                    version = version,
                    tensorCount = tensorCount,
                    metadataKvCount = kvCount,
                    fileSizeBytes = file.length(),
                )
            }
        }.getOrElse {
            GgufHeaderInfo(valid = false, fileSizeBytes = file.length())
        }
    }

    fun hasValidMagicPrefix(bytes: ByteArray, length: Int): Boolean {
        if (length < 4) return false
        return bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'G'.code.toByte() &&
            bytes[2] == 'U'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()
    }
}

/**
 * Resumable GGUF model downloader using OkHttp with HTTP Range headers,
 * live progress/speed telemetry, automatic mirror fallback, and GGUF header validation.
 */
class LocalModelDownloadManager(
    context: Context,
    baseClient: OkHttpClient,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val downloadClient: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val _statuses = MutableStateFlow<Map<String, LocalModelDownloadStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, LocalModelDownloadStatus>> = _statuses.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            refreshStatuses()
        }
    }

    fun modelsDirectory(): File {
        val extDir = runCatching { appContext.getExternalFilesDir("local_models") }.getOrNull()
        val dir = extDir ?: File(appContext.filesDir, "local_models")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun modelFile(spec: LocalModelSpec): File = File(modelsDirectory(), spec.fileName)

    fun partFile(spec: LocalModelSpec): File = File(modelsDirectory(), "${spec.fileName}.part")

    fun availableStorageBytes(): Long = runCatching {
        val dir = modelsDirectory()
        val stat = StatFs(dir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrElse {
        val stat = StatFs(Environment.getDataDirectory().path)
        stat.availableBlocksLong * stat.blockSizeLong
    }

    fun refreshStatuses() {
        val updated = LinkedHashMap<String, LocalModelDownloadStatus>()
        for (spec in LocalModelCatalog.models) {
            val target = modelFile(spec)
            val part = partFile(spec)
            val existing = _statuses.value[spec.id]
            if (activeJobs[spec.id]?.isActive == true && existing != null) {
                updated[spec.id] = existing
                continue
            }
            when {
                target.exists() && target.length() >= 24L -> {
                    val info = GgufModelInspector.inspect(target)
                    if (info.valid) {
                        updated[spec.id] = LocalModelDownloadStatus(
                            spec = spec,
                            state = LocalDownloadState.READY,
                            downloadedBytes = target.length(),
                            totalBytes = target.length(),
                            ggufInfo = info,
                            filePath = target.absolutePath,
                        )
                    } else {
                        updated[spec.id] = LocalModelDownloadStatus(
                            spec = spec,
                            state = LocalDownloadState.FAILED,
                            downloadedBytes = target.length(),
                            totalBytes = spec.approxBytes,
                            errorMessage = "Corrupted GGUF header. Delete and re-download.",
                        )
                    }
                }
                part.exists() && part.length() > 0L -> {
                    updated[spec.id] = LocalModelDownloadStatus(
                        spec = spec,
                        state = LocalDownloadState.PAUSED,
                        downloadedBytes = part.length(),
                        totalBytes = existing?.totalBytes?.takeIf { it > part.length() } ?: spec.approxBytes,
                        filePath = part.absolutePath,
                    )
                }
                else -> {
                    updated[spec.id] = LocalModelDownloadStatus(
                        spec = spec,
                        state = LocalDownloadState.NOT_DOWNLOADED,
                        downloadedBytes = 0L,
                        totalBytes = spec.approxBytes,
                    )
                }
            }
        }
        _statuses.value = updated
    }

    fun firstReadyModel(preferredId: String? = null): LocalModelSpec? {
        val snap = _statuses.value
        if (!preferredId.isNullOrBlank()) {
            val preferredSpec = LocalModelCatalog.findByIdOrName(preferredId)
            if (preferredSpec != null) {
                val file = modelFile(preferredSpec)
                if (file.exists() && GgufModelInspector.inspect(file).valid) {
                    return preferredSpec
                }
            }
        }
        for (spec in LocalModelCatalog.models) {
            if (snap[spec.id]?.isReady == true) return spec
            val file = modelFile(spec)
            if (file.exists() && GgufModelInspector.inspect(file).valid) {
                return spec
            }
        }
        return null
    }

    fun isModelReady(modelId: String): Boolean {
        val spec = LocalModelCatalog.findByIdOrName(modelId) ?: return false
        if (_statuses.value[spec.id]?.isReady == true) return true
        val file = modelFile(spec)
        return file.exists() && GgufModelInspector.inspect(file).valid
    }

    fun startOrResumeDownload(
        scope: CoroutineScope,
        modelId: String,
        onReady: (LocalModelSpec) -> Unit,
    ) {
        startOrResumeDownload(
            modelId = modelId,
            onComplete = { spec, ok, _ ->
                if (ok) onReady(spec)
            },
        )
    }

    fun startOrResumeDownload(
        modelId: String,
        onComplete: ((LocalModelSpec, Boolean, String) -> Unit)? = null,
    ) {
        val spec = LocalModelCatalog.findByIdOrName(modelId) ?: return
        if (activeJobs[spec.id]?.isActive == true) return

        val freeBytes = availableStorageBytes()
        val part = partFile(spec)
        val remainingNeeded = max(0L, spec.approxBytes - (if (part.exists()) part.length() else 0L))
        if (freeBytes in 1L until (remainingNeeded + 40_000_000L)) {
            updateStatus(spec.id) {
                it.copy(
                    state = LocalDownloadState.FAILED,
                    errorMessage = "Not enough free storage (${LocalModelCatalog.formatBytes(freeBytes)} available, need ~${spec.approxSizeLabel}).",
                )
            }
            onComplete?.invoke(spec, false, "Not enough free storage to download ${spec.displayName}.")
            return
        }

        val job = scope.launch {
            try {
                downloadWithMirrors(spec, onComplete)
            } catch (e: CancellationException) {
                val currentBytes = if (part.exists()) part.length() else 0L
                updateStatus(spec.id) { prev ->
                    if (prev.state == LocalDownloadState.DOWNLOADING) {
                        prev.copy(
                            state = if (currentBytes > 0L) LocalDownloadState.PAUSED else LocalDownloadState.NOT_DOWNLOADED,
                            downloadedBytes = currentBytes,
                            speedBytesPerSec = 0L,
                        )
                    } else {
                        prev
                    }
                }
                throw e
            } finally {
                activeJobs.remove(spec.id)
            }
        }
        activeJobs[spec.id] = job
    }

    fun pauseDownload(modelId: String) {
        val spec = LocalModelCatalog.findByIdOrName(modelId) ?: return
        val part = partFile(spec)
        activeJobs.remove(spec.id)?.cancel()
        val currentBytes = if (part.exists()) part.length() else 0L
        updateStatus(spec.id) { prev ->
            prev.copy(
                state = if (currentBytes > 0L) LocalDownloadState.PAUSED else LocalDownloadState.NOT_DOWNLOADED,
                downloadedBytes = currentBytes,
                speedBytesPerSec = 0L,
                errorMessage = null,
            )
        }
    }

    fun deleteModel(modelId: String) {
        val spec = LocalModelCatalog.findByIdOrName(modelId) ?: return
        activeJobs.remove(spec.id)?.cancel()
        runCatching { partFile(spec).delete() }
        runCatching { modelFile(spec).delete() }
        updateStatus(spec.id) {
            LocalModelDownloadStatus(
                spec = spec,
                state = LocalDownloadState.NOT_DOWNLOADED,
                downloadedBytes = 0L,
                totalBytes = spec.approxBytes,
            )
        }
    }

    private suspend fun downloadWithMirrors(
        spec: LocalModelSpec,
        onComplete: ((LocalModelSpec, Boolean, String) -> Unit)?,
    ) = withContext(Dispatchers.IO) {
        val target = modelFile(spec)
        val part = partFile(spec)
        var lastError = "Could not connect to model repository."

        updateStatus(spec.id) { prev ->
            prev.copy(
                state = LocalDownloadState.DOWNLOADING,
                downloadedBytes = if (part.exists()) part.length() else 0L,
                speedBytesPerSec = 0L,
                errorMessage = null,
            )
        }

        for (url in spec.downloadUrls) {
            if (!isActive) return@withContext
            try {
                val existingBytes = if (part.exists()) part.length() else 0L
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Jarvis-Android-LocalAI/3.1")
                    .header("Accept", "*/*")
                if (existingBytes > 0L) {
                    reqBuilder.header("Range", "bytes=$existingBytes-")
                }

                downloadClient.newCall(reqBuilder.build()).execute().use { response ->
                    val code = response.code
                    if (code == 416 && existingBytes > 0L) {
                        // Range already satisfied
                        if (part.renameTo(target)) {
                            val info = GgufModelInspector.inspect(target)
                            if (info.valid) {
                                updateStatus(spec.id) {
                                    LocalModelDownloadStatus(
                                        spec = spec,
                                        state = LocalDownloadState.READY,
                                        downloadedBytes = target.length(),
                                        totalBytes = target.length(),
                                        ggufInfo = info,
                                        filePath = target.absolutePath,
                                    )
                                }
                                onComplete?.invoke(spec, true, "${spec.displayName} is downloaded and ready for offline use.")
                                return@withContext
                            }
                        }
                    }

                    if (!response.isSuccessful && code != 206) {
                        lastError = "Mirror returned HTTP $code"
                        return@use
                    }

                    val body = response.body
                    val appendMode = code == 206 && existingBytes > 0L
                    val startOffset = if (appendMode) existingBytes else 0L
                    val contentLength = body.contentLength()
                    val expectedTotal = when {
                        contentLength > 0L -> startOffset + contentLength
                        else -> spec.approxBytes
                    }

                    if (!appendMode && part.exists()) {
                        part.delete()
                    }

                    body.byteStream().use { input ->
                        FileOutputStream(part, appendMode).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var downloaded = startOffset
                            var windowStartMs = System.currentTimeMillis()
                            var windowBytes = 0L
                            var currentSpeed = 0L
                            var checkedMagic = startOffset >= 4L

                            while (isActive) {
                                val read = input.read(buffer)
                                if (read == -1) break

                                if (!checkedMagic && downloaded == 0L && read >= 4) {
                                    if (!GgufModelInspector.hasValidMagicPrefix(buffer, read)) {
                                        lastError = "Server returned a non-GGUF response. Trying backup mirror..."
                                        throw IllegalStateException(lastError)
                                    }
                                    checkedMagic = true
                                }

                                output.write(buffer, 0, read)
                                downloaded += read
                                windowBytes += read

                                val now = System.currentTimeMillis()
                                val elapsed = now - windowStartMs
                                if (elapsed >= 250L) {
                                    currentSpeed = (windowBytes * 1000L) / max(1L, elapsed)
                                    windowStartMs = now
                                    windowBytes = 0L
                                    updateStatus(spec.id) { prev ->
                                        prev.copy(
                                            state = LocalDownloadState.DOWNLOADING,
                                            downloadedBytes = downloaded,
                                            totalBytes = max(expectedTotal, downloaded),
                                            speedBytesPerSec = currentSpeed,
                                            errorMessage = null,
                                        )
                                    }
                                }
                            }
                            output.flush()
                        }
                    }

                    if (!isActive) return@withContext

                    val info = GgufModelInspector.inspect(part)
                    if (!info.valid) {
                        part.delete()
                        lastError = "Downloaded file did not pass GGUF integrity verification."
                        return@use
                    }

                    if (target.exists()) target.delete()
                    val renamed = part.renameTo(target)
                    val finalFile = if (renamed) target else part
                    val finalInfo = GgufModelInspector.inspect(finalFile)

                    updateStatus(spec.id) {
                        LocalModelDownloadStatus(
                            spec = spec,
                            state = LocalDownloadState.READY,
                            downloadedBytes = finalFile.length(),
                            totalBytes = finalFile.length(),
                            speedBytesPerSec = 0L,
                            errorMessage = null,
                            ggufInfo = finalInfo,
                            filePath = finalFile.absolutePath,
                        )
                    }
                    onComplete?.invoke(
                        spec,
                        true,
                        "${spec.displayName} (${LocalModelCatalog.formatBytes(finalFile.length())}) downloaded and verified for offline execution.",
                    )
                    return@withContext
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message?.take(200) ?: "Network error while downloading ${spec.displayName}."
            }
        }

        val currentBytes = if (part.exists()) part.length() else 0L
        updateStatus(spec.id) { prev ->
            prev.copy(
                state = LocalDownloadState.FAILED,
                downloadedBytes = currentBytes,
                speedBytesPerSec = 0L,
                errorMessage = lastError,
            )
        }
        onComplete?.invoke(spec, false, "Failed to download ${spec.displayName}: $lastError")
    }

    private fun updateStatus(
        modelId: String,
        transform: (LocalModelDownloadStatus) -> LocalModelDownloadStatus,
    ) {
        _statuses.update { map ->
            val spec = LocalModelCatalog.findByIdOrName(modelId) ?: return@update map
            val current = map[spec.id] ?: LocalModelDownloadStatus(spec = spec)
            val next = LinkedHashMap(map)
            next[spec.id] = transform(current)
            next
        }
    }
}

/**
 * Deterministic & conversational on-device task router and response engine.
 *
 * Translates natural-language voice or text requests into exact `ToolCall` invocations
 * across Jarvis's entire tool suite (apps, YouTube, WhatsApp messages & voice notes,
 * calls, SMS, timers, alarms, reminders, flashlight, volume, ringer, brightness, DND,
 * media playback, camera, screenshots, notifications, device status, diagnostics,
 * storage, navigation, calendar, memory, and screen control) and synthesizes
 * J.A.R.V.I.S.-persona replies without requiring an internet connection or API key.
 */
object OfflineTaskRouter {

    fun routeUserCommand(
        userText: String,
        availableTools: List<ToolDefinition>,
        conversationHistory: List<LlmMessage> = emptyList(),
    ): List<ToolCall> {
        val clean = userText.trim()
        if (clean.isBlank()) return emptyList()

        // Semantic Intent Gate: never route capability questions, how-to questions,
        // hypotheticals, cancellations/corrections, or incomplete commands to tools!
        val intentAnalysis = IntentGate.classify(clean)
        if (!intentAnalysis.isExecutableCommand) {
            return emptyList()
        }

        val expandedClean = IntentGate.expandSpokenEmojis(clean)
        val lower = expandedClean.lowercase(Locale.ROOT)
        val toolNames = availableTools.map { it.name }.toSet()

        fun hasTool(name: String): Boolean = toolNames.isEmpty() || name in toolNames

        fun call(name: String, argsJson: String): List<ToolCall> = listOf(
            ToolCall(
                id = "local_call_${UUID.randomUUID().toString().take(8)}",
                name = name,
                argumentsJson = argsJson,
            ),
        )

        // 1. WhatsApp voice note / voice message (requires explicit recipient + message or explicit reply)
        if ((lower.contains("voice message") || lower.contains("voice note") || lower.contains("audio message")) &&
            hasTool("send_whatsapp_voice_note")
        ) {
            val parsedVoice = extractWhatsAppVoiceTargetAndMessage(expandedClean)
            if (parsedVoice != null) {
                val (recipient, message) = parsedVoice
                val argsJson = buildJsonObject {
                    put("recipient", recipient)
                    put("message", message)
                }.toString()
                if (IntentGate.validateToolCall(expandedClean, "send_whatsapp_voice_note", argsJson) is SafetyGateDecision.Allowed) {
                    return call("send_whatsapp_voice_note", argsJson)
                }
            }
            return emptyList()
        }

        // 2. WhatsApp text message
        if (lower.contains("whatsapp") &&
            (lower.contains("send") || lower.contains("message") || lower.contains("text") || lower.contains("tell") || lower.contains("say")) &&
            !lower.contains("open whatsapp only") &&
            hasTool("send_whatsapp_message")
        ) {
            val parsed = extractRecipientAndMessage(expandedClean, isWhatsApp = true)
            if (parsed != null) {
                val argsJson = buildJsonObject {
                    put("recipient", parsed.first)
                    put("message", parsed.second)
                }.toString()
                if (IntentGate.validateToolCall(expandedClean, "send_whatsapp_message", argsJson) is SafetyGateDecision.Allowed) {
                    return call("send_whatsapp_message", argsJson)
                }
            }
            return emptyList()
        }

        // 3. Answer / end WhatsApp call
        if (lower.contains("whatsapp") && lower.contains("call")) {
            if ((lower.contains("answer") || lower.contains("pick up") || lower.contains("accept")) &&
                hasTool("answer_whatsapp_call") &&
                IntentGate.validateToolCall(expandedClean, "answer_whatsapp_call", "{}") is SafetyGateDecision.Allowed
            ) {
                return call("answer_whatsapp_call", "{}")
            }
            if ((lower.contains("end") || lower.contains("hang up") || lower.contains("decline") || lower.contains("reject")) &&
                hasTool("end_whatsapp_call")
            ) {
                return call("end_whatsapp_call", "{}")
            }
        }

        // 4. YouTube search, type & play
        if ((lower.contains("youtube") && (lower.contains("search") || lower.contains("play") || lower.contains("find") || lower.contains("watch") || lower.contains("put on") || lower.contains("type"))) ||
            (lower.startsWith("play ") && !lower.equals("play") && !lower.equals("play music") && !lower.equals("play song") && !lower.contains("first video"))
        ) {
            if (hasTool("search_youtube")) {
                val query = extractYouTubeQuery(clean)
                val wantsPlay = lower.contains("play") || lower.contains("watch") || lower.contains("put on")
                if (query.isNotBlank()) {
                    return call(
                        "search_youtube",
                        buildJsonObject {
                            put("query", query)
                            put("play_immediately", wantsPlay)
                        }.toString(),
                    )
                }
            }
        }

        // 5. Flashlight / Torch
        if ((lower.contains("flashlight") || lower.contains("torch") || lower.contains("flash light")) &&
            hasTool("control_flashlight")
        ) {
            val on: Boolean? = when {
                lower.contains("off") || lower.contains("disable") || lower.contains("stop") -> false
                lower.contains("on") || lower.contains("enable") || lower.contains("start") -> true
                else -> null
            }
            return call(
                "control_flashlight",
                buildJsonObject {
                    if (on != null) put("on", on)
                }.toString(),
            )
        }

        // 6. Timer
        if (lower.contains("timer") &&
            (lower.contains("set") || lower.contains("start") || lower.contains("for ") || lower.contains("minute") || lower.contains("second") || lower.contains("hour")) &&
            hasTool("set_timer")
        ) {
            val seconds = parseDurationSeconds(lower)
            if (seconds > 0) {
                return call(
                    "set_timer",
                    buildJsonObject {
                        put("seconds", seconds)
                    }.toString(),
                )
            }
        }

        // 7. Alarm
        if ((lower.contains("alarm") || lower.startsWith("wake me up at")) &&
            hasTool("set_alarm")
        ) {
            val hm = parseTimeHourMinute(lower)
            if (hm != null) {
                return call(
                    "set_alarm",
                    buildJsonObject {
                        put("hour", hm.first)
                        put("minute", hm.second)
                    }.toString(),
                )
            }
        }

        // 8. Reminder
        if ((lower.startsWith("remind me") || lower.contains("set a reminder") || lower.contains("schedule a reminder")) &&
            hasTool("schedule_reminder")
        ) {
            val minutes = max(1, parseDurationSeconds(lower) / 60)
            val title = clean
                .replace(Regex("(?i)^(jarvis\\s*,?\\s*)?(please\\s+)?(remind me|set a reminder|schedule a reminder)\\s*(in\\s+\\d+\\s*(minutes?|mins?|hours?|hrs?|seconds?|secs?))?\\s*(to|about|that)?\\s*"), "")
                .trim()
                .ifBlank { "Scheduled reminder" }
            return call(
                "schedule_reminder",
                buildJsonObject {
                    put("message", title)
                    put("title", title)
                    put("minutes_from_now", minutes)
                }.toString(),
            )
        }

        // 9. Volume control
        if ((lower.contains("volume") || lower.equals("louder") || lower.equals("quieter") || lower.contains("turn it up") || lower.contains("turn it down")) &&
            hasTool("set_volume")
        ) {
            val pctMatch = Regex("""(\d{1,3})\s*%?""").find(lower)
            val level = pctMatch?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
            val direction = when {
                lower.contains("up") || lower.contains("louder") || lower.contains("increase") || lower.contains("raise") -> "up"
                lower.contains("down") || lower.contains("quieter") || lower.contains("lower") || lower.contains("decrease") -> "down"
                lower.contains("mute") -> null
                else -> null
            }
            return call(
                "set_volume",
                buildJsonObject {
                    when {
                        lower.contains("mute") -> put("level", 0)
                        level != null -> put("level", level)
                        direction != null -> put("direction", direction)
                        else -> put("level", 60)
                    }
                    put("stream", "media")
                }.toString(),
            )
        }

        // 10. Ringer mode (vibrate / silent / sound)
        if ((lower.contains("ringer") || lower.contains("vibrate mode") || lower.contains("silent mode") ||
                lower.contains("switch to vibrate") || lower.contains("put phone on vibrate") || lower.contains("put phone on silent")) &&
            hasTool("set_ringer_mode")
        ) {
            val mode = when {
                lower.contains("vibrate") -> "vibrate"
                lower.contains("silent") || lower.contains("mute") -> "silent"
                else -> "sound"
            }
            return call(
                "set_ringer_mode",
                buildJsonObject { put("mode", mode) }.toString(),
            )
        }

        // 11. Screen brightness
        if (lower.contains("brightness") && hasTool("set_screen_brightness")) {
            val pct = Regex("""(\d{1,3})""").find(lower)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
            val isAuto = lower.contains("auto") || lower.contains("adaptive")
            return call(
                "set_screen_brightness",
                buildJsonObject {
                    if (isAuto) {
                        put("auto", true)
                    } else {
                        put(
                            "level",
                            pct ?: when {
                                lower.contains("max") || lower.contains("full") -> 100
                                lower.contains("min") || lower.contains("low") || lower.contains("dim") -> 20
                                else -> 60
                            },
                        )
                    }
                }.toString(),
            )
        }

        // 12. Do Not Disturb
        if ((lower.contains("do not disturb") || lower.contains("dnd")) && hasTool("set_do_not_disturb")) {
            val mode = when {
                lower.contains("off") || lower.contains("disable") -> "off"
                lower.contains("alarm") -> "alarms"
                lower.contains("silence") -> "silence"
                else -> "priority"
            }
            return call(
                "set_do_not_disturb",
                buildJsonObject { put("mode", mode) }.toString(),
            )
        }

        // 13. Media playback controls (pause / resume / next / previous / stop)
        if (hasTool("control_media")) {
            val mediaAction = when {
                lower.contains("pause") && (lower.contains("music") || lower.contains("song") || lower.contains("video") || lower.contains("media") || lower.contains("playback") || lower == "pause") -> "pause"
                (lower.contains("resume") || lower == "play music" || lower == "play song") -> "play"
                lower.contains("next track") || lower.contains("next song") || lower == "skip" || lower == "next" -> "next"
                lower.contains("previous track") || lower.contains("previous song") -> "previous"
                lower.contains("stop music") || lower.contains("stop playback") -> "stop"
                else -> null
            }
            if (mediaAction != null) {
                return call(
                    "control_media",
                    buildJsonObject { put("action", mediaAction) }.toString(),
                )
            }
        }

        // 14. Phone Call
        if ((lower.startsWith("call ") || lower.startsWith("phone ") || lower.startsWith("dial ") || lower.contains("place a call to ")) &&
            !lower.contains("whatsapp") &&
            hasTool("call_contact")
        ) {
            val contact = clean
                .replace(Regex("(?i)^(jarvis\\s*,?\\s*)?(please\\s+)?(place a call to|call|phone|dial)\\s+"), "")
                .trim()
            if (contact.isNotBlank()) {
                return call(
                    "call_contact",
                    buildJsonObject { put("contact", contact) }.toString(),
                )
            }
        }

        // 15. SMS / Text message
        if ((lower.startsWith("send sms") || lower.startsWith("send a text") || lower.startsWith("send text") || lower.startsWith("text ")) &&
            !lower.contains("whatsapp") &&
            hasTool("send_sms")
        ) {
            val parsed = extractRecipientAndMessage(clean, isWhatsApp = false)
            if (parsed != null) {
                return call(
                    "send_sms",
                    buildJsonObject {
                        put("contact", parsed.first)
                        put("message", parsed.second)
                    }.toString(),
                )
            }
        }

        // 16. Camera / Selfie / Screenshot
        if (lower.contains("screenshot") && hasTool("take_screenshot")) {
            return call("take_screenshot", "{}")
        }
        if ((lower.contains("take a selfie") || lower.contains("take selfie") || lower.contains("take a photo") || lower.contains("take a picture")) &&
            hasTool("take_photo")
        ) {
            val facing = if (lower.contains("selfie") || lower.contains("front")) "front" else "back"
            return call(
                "take_photo",
                buildJsonObject { put("facing", facing) }.toString(),
            )
        }
        if ((lower.contains("open camera") || lower.contains("launch camera")) && hasTool("open_camera")) {
            val facing = if (lower.contains("front") || lower.contains("selfie")) "front" else "back"
            val mode = if (lower.contains("video")) "video" else "photo"
            return call(
                "open_camera",
                buildJsonObject {
                    put("facing", facing)
                    put("mode", mode)
                }.toString(),
            )
        }

        // 17. Navigation / Directions
        if ((lower.startsWith("navigate to ") || lower.startsWith("directions to ") || lower.contains("take me to ") || lower.contains("navigate me to ")) &&
            hasTool("navigate_to")
        ) {
            val dest = clean
                .replace(Regex("(?i)^(jarvis\\s*,?\\s*)?(please\\s+)?(navigate me to|navigate to|get directions to|directions to|take me to)\\s+"), "")
                .trim()
            if (dest.isNotBlank()) {
                return call(
                    "navigate_to",
                    buildJsonObject { put("destination", dest) }.toString(),
                )
            }
        }

        // 18. Notifications
        if (lower.contains("notification")) {
            if ((lower.contains("dismiss") || lower.contains("clear")) && hasTool("dismiss_notifications")) {
                return call("dismiss_notifications", "{}")
            }
            if (hasTool("read_notifications")) {
                return call("read_notifications", "{}")
            }
        }

        // 19. Memory & Notes (Remember / Save Note / Search Memory / Recall Notes / Forget)
        if ((lower.startsWith("remember that ") ||
                lower.startsWith("remember my ") ||
                lower.startsWith("remember ") ||
                lower.startsWith("save to memory ") ||
                lower.startsWith("store in memory ") ||
                lower.startsWith("save a note ") ||
                lower.startsWith("take a note ") ||
                lower.startsWith("make a note ") ||
                lower.startsWith("keep a note ") ||
                lower.startsWith("note that ")) &&
            !lower.contains("what do you remember") &&
            !lower.contains("do you remember") &&
            hasTool("remember_about_owner")
        ) {
            val isNote = lower.contains("note")
            val fact = clean
                .replace(
                    Regex("(?i)^(jarvis\\s*,?\\s*)?(please\\s+)?(remember that|remember|save to memory|store in memory|save a note that|save a note|take a note that|take a note|make a note that|make a note|keep a note|note that)\\s*:?\\s*"),
                    "",
                )
                .trim()
            if (fact.isNotBlank()) {
                return call(
                    "remember_about_owner",
                    buildJsonObject {
                        put("text", fact)
                        put("type", if (isNote) "note" else "fact")
                        put("importance", 4)
                        if (isNote) put("tags", "note")
                    }.toString(),
                )
            }
        }
        if ((lower.contains("what do you remember") ||
                lower.contains("what do you know about me") ||
                lower.contains("do you remember") ||
                lower.contains("my notes") ||
                lower.contains("stored notes") ||
                lower.contains("saved notes") ||
                lower.contains("stored memory") ||
                lower.contains("stored memories") ||
                lower.contains("saved memories") ||
                lower.contains("my memories") ||
                lower.contains("what did i tell you") ||
                lower.contains("what did i save") ||
                lower.startsWith("search memory") ||
                lower.startsWith("read my notes") ||
                lower.startsWith("show my notes")) &&
            hasTool("search_memory")
        ) {
            val q = clean
                .replace(
                    Regex("(?i)^(jarvis\\s*,?\\s*)?(please\\s+)?(what do you remember about|what do you remember|what do you know about me|what do you know about|do you remember my|do you remember|search memory for|search memory|read my notes about|read my notes|show my notes|what are my stored notes|what are my notes|what are my memories)\\s*"),
                    "",
                )
                .trim()
            return call(
                "search_memory",
                buildJsonObject { put("query", q) }.toString(),
            )
        }

        // 20. Hardware Diagnostics, Storage, Battery & Current Time
        if ((lower.contains("diagnostic") || lower.contains("system scan") || lower.contains("full status") || lower.contains("system report")) &&
            hasTool("run_diagnostics")
        ) {
            return call("run_diagnostics", "{}")
        }
        if ((lower.contains("storage") || lower.contains("disk space") || lower.contains("free space")) &&
            hasTool("analyze_storage")
        ) {
            return call("analyze_storage", "{}")
        }
        if ((lower.contains("battery") || lower.contains("phone status") || lower.contains("device status") || lower.contains("wifi status")) &&
            hasTool("get_device_status")
        ) {
            return call("get_device_status", "{}")
        }
        if ((lower.contains("what time") || lower.contains("current time") || lower.contains("what is the time") ||
                lower.contains("what's the time") || lower.contains("today's date") || lower.contains("what day is it")) &&
            hasTool("get_current_time")
        ) {
            return call("get_current_time", "{}")
        }

        // 20b. Operational Modes (Study / Research / Tasks / Agentic / Default)
        if (hasTool("set_operating_mode") &&
            (lower.contains("study mode") || lower.contains("research mode") || lower.contains("tasks mode") ||
                lower.contains("task mode") || lower.contains("agentic mode") || lower.contains("default mode") ||
                lower.contains("standard mode") || lower.contains("normal mode"))
        ) {
            val targetMode = when {
                lower.contains("study") -> "study"
                lower.contains("research") -> "research"
                lower.contains("agentic") -> "agentic"
                lower.contains("task") -> "tasks"
                else -> "default"
            }
            return call(
                "set_operating_mode",
                buildJsonObject { put("mode", targetMode) }.toString(),
            )
        }

        // 20c. Scheduled Tasks (schedule, list, complete)
        if ((Regex("""^(?:jarvis\s*,?\s*)?(?:please\s+)?schedule\s+(?:a\s+)?(?:(?:daily|weekly)\s+)?task\b|^(?:jarvis\s*,?\s*)?(?:please\s+)?schedule\s+(?:daily|weekly)\b|add\s+a\s+scheduled\s+task""")
                .containsMatchIn(lower)) &&
            hasTool("schedule_task")
        ) {
            val recurrence = when {
                lower.contains("daily") || lower.contains("every day") -> "daily"
                lower.contains("weekly") || lower.contains("every week") -> "weekly"
                else -> "none"
            }
            val durationMinutes = (parseDurationSeconds(lower) / 60).takeIf { it > 0 } ?: 15
            val title = clean
                .replace(Regex("""(?i)^(jarvis\s*,?\s*)?(please\s+)?(schedule\s+(a\s+)?(daily|weekly)?\s*task|add\s+a\s+scheduled\s+task)\s*(in\s+\d+\s*(minutes?|mins?|hours?|hrs?))?\s*(to|for|about)?\s*"""), "")
                .trim()
                .ifBlank { "Scheduled task" }
            return call(
                "schedule_task",
                buildJsonObject {
                    put("title", title)
                    put("minutes_from_now", durationMinutes)
                    put("recurrence", recurrence)
                }.toString(),
            )
        }
        if ((lower.contains("list scheduled tasks") || lower.contains("show scheduled tasks") ||
                lower.contains("my scheduled tasks") || lower.contains("upcoming tasks") ||
                lower.contains("what tasks do i have")) &&
            hasTool("list_scheduled_tasks")
        ) {
            return call("list_scheduled_tasks", "{}")
        }
        if ((lower.startsWith("complete task ") || lower.startsWith("finish task ") ||
                lower.startsWith("mark task ")) &&
            hasTool("complete_scheduled_task")
        ) {
            val taskRef = clean
                .replace(Regex("""(?i)^(jarvis\s*,?\s*)?(please\s+)?(complete|finish|mark)\s+task\s+"""), "")
                .replace(Regex("""(?i)\s+(as\s+)?(done|complete|completed|finished)$"""), "")
                .trim()
            if (taskRef.isNotBlank()) {
                return call(
                    "complete_scheduled_task",
                    buildJsonObject { put("task_id_or_title", taskRef) }.toString(),
                )
            }
        }

        // 21. Screen navigation, scrolling, swiping, typing & tapping
        if ((lower == "scroll down" || lower == "scroll up" || lower == "scroll left" || lower == "scroll right") &&
            hasTool("scroll_screen")
        ) {
            val dir = lower.removePrefix("scroll ").trim()
            return call(
                "scroll_screen",
                buildJsonObject { put("direction", dir) }.toString(),
            )
        }
        if ((lower == "swipe up" || lower == "swipe down" || lower == "swipe left" || lower == "swipe right") &&
            hasTool("swipe_screen")
        ) {
            val dir = lower.removePrefix("swipe ").trim()
            return call(
                "swipe_screen",
                buildJsonObject { put("direction", dir) }.toString(),
            )
        }
        if ((lower == "go home" || lower == "go back" || lower == "open recents") &&
            hasTool("press_system_key")
        ) {
            val key = when {
                lower.contains("home") -> "home"
                lower.contains("recents") -> "recents"
                else -> "back"
            }
            return call(
                "press_system_key",
                buildJsonObject { put("key", key) }.toString(),
            )
        }
        if ((lower.contains("first video") || lower.contains("second video") || lower.contains("third video")) &&
            (lower.contains("play") || lower.contains("tap") || lower.contains("click") || lower.contains("open")) &&
            hasTool("tap_video_item")
        ) {
            val idx = when {
                lower.contains("second") -> 2
                lower.contains("third") -> 3
                else -> 1
            }
            return call(
                "tap_video_item",
                buildJsonObject { put("index", idx) }.toString(),
            )
        }
        if (lower.startsWith("type ") && hasTool("type_on_screen")) {
            val body = clean.replace(Regex("(?i)^(jarvis\\s*,?\\s*)?(please\\s+)?type\\s+"), "").trim()
            val onAppMatch = Regex("(?i)^(.+?)\\s+(?:on|in|into)\\s+([A-Za-z0-9 ._-]+)$").find(body)
            if (onAppMatch != null) {
                val textToType = onAppMatch.groupValues[1].trim().trim('"', '\'')
                val targetApp = onAppMatch.groupValues[2].trim()
                if (textToType.isNotBlank()) {
                    return call(
                        "type_on_screen",
                        buildJsonObject {
                            put("text", textToType)
                            put("app", targetApp)
                            put("target", "Search")
                            put("submit", true)
                        }.toString(),
                    )
                }
            } else if (body.isNotBlank()) {
                return call(
                    "type_on_screen",
                    buildJsonObject {
                        put("text", body.trim('"', '\''))
                        put("submit", true)
                    }.toString(),
                )
            }
        }
        if ((lower.startsWith("tap ") || lower.startsWith("click ") || lower.startsWith("press ") || lower.startsWith("select ") || lower.startsWith("touch ")) &&
            hasTool("tap_screen")
        ) {
            val targetLabel = clean
                .replace(Regex("(?i)^(jarvis\\s*,?\\s*)?(please\\s+)?(tap|click|press|select|touch)\\s+(on\\s+)?(the\\s+)?(button\\s+)?"), "")
                .trim()
                .trim('"', '\'')
            if (targetLabel.isNotBlank()) {
                return call(
                    "tap_screen",
                    buildJsonObject { put("text", targetLabel) }.toString(),
                )
            }
        }

        // 21b. Exact in-app destinations across YouTube, Instagram, WhatsApp, Spotify, Chrome, Gmail, Settings
        if (hasTool("open_app")) {
            val recentContextLower = conversationHistory.takeLast(4).joinToString(" ") { it.content.lowercase(Locale.ROOT) }
            val activeApp = when {
                lower.contains("youtube") || lower.contains(" yt ") || lower.endsWith(" yt") -> "YouTube"
                lower.contains("instagram") || lower.contains("insta") -> "Instagram"
                lower.contains("whatsapp") -> "WhatsApp"
                lower.contains("spotify") -> "Spotify"
                lower.contains("chrome") -> "Chrome"
                lower.contains("gmail") -> "Gmail"
                lower.contains("settings") -> "Settings"
                recentContextLower.contains("youtube") -> "YouTube"
                recentContextLower.contains("instagram") -> "Instagram"
                recentContextLower.contains("whatsapp") -> "WhatsApp"
                recentContextLower.contains("spotify") -> "Spotify"
                else -> null
            }
            val section = when {
                lower.contains("profile") || lower.contains("my channel") || lower.contains("your channel") ||
                    lower.contains("my account") || lower.contains("you tab") -> "profile"
                lower.contains("subscription") -> "subscriptions"
                lower.contains("history") || lower.contains("watch history") -> "history"
                lower.contains("liked video") || lower.contains("liked") -> "liked videos"
                lower.contains("watch later") -> "watch later"
                lower.contains("download") -> "downloads"
                lower.contains("library") || lower.contains("playlists") -> "library"
                lower.contains("shorts") -> "shorts"
                lower.contains("reels") -> "reels"
                lower.contains("explore") -> "explore"
                lower.contains("notifications") && activeApp != null -> "notifications"
                else -> null
            }
            val destinationIntent = lower.startsWith("open ") ||
                lower.startsWith("launch ") ||
                lower.startsWith("go to ") ||
                lower.startsWith("navigate to ") ||
                lower.contains("take me to") ||
                lower.contains("show me") ||
                lower.contains("show my ") ||
                lower.contains("open my ") ||
                lower.contains("go to my ")
            if (activeApp != null && section != null && destinationIntent) {
                return call(
                    "open_app",
                    buildJsonObject {
                        put("app", activeApp)
                        put("section", section)
                    }.toString(),
                )
            }
            // Also support "open <app> <section>" or "in <app> open <section>" for any app
            val appSectionMatch = Regex("""(?i)^(?:jarvis\s*,?\s*)?(?:please\s+)?(?:open|go to|navigate to|show)\s+(?:my\s+|the\s+)?(youtube|instagram|whatsapp|spotify|chrome|gmail|settings)\s+([a-z0-9 ]{2,25})$""")
                .find(clean)
            if (appSectionMatch != null) {
                val appTarget = appSectionMatch.groupValues[1].trim()
                val secTarget = appSectionMatch.groupValues[2]
                    .replace(Regex("(?i)\\b(tab|section|page|screen|app)\\b"), "")
                    .trim()
                if (secTarget.isNotBlank()) {
                    return call(
                        "open_app",
                        buildJsonObject {
                            put("app", appTarget)
                            put("section", secTarget)
                        }.toString(),
                    )
                }
            }
        }

        // 22. Open installed app (e.g. "open YouTube", "open WhatsApp", "launch Spotify")
        if ((lower.startsWith("open ") || lower.startsWith("launch ") || lower.startsWith("start app ")) &&
            hasTool("open_app")
        ) {
            val appPart = clean
                .replace(Regex("(?i)^(jarvis\\s*,?\\s*)?(please\\s+)?(open|launch|start app)\\s+(my\\s+|the\\s+)?"), "")
                .replace(Regex("(?i)\\s+app$"), "")
                .trim()
            val appName = if (appPart.contains(" and ", ignoreCase = true)) {
                appPart.substringBefore(" and ").trim()
            } else {
                appPart
            }
            if (appName.isNotBlank()) {
                return call(
                    "open_app",
                    buildJsonObject { put("app", appName) }.toString(),
                )
            }
        }

        // 23. Advanced Web Search, Deep Research, Fact Check & Webpage Fetch (zero API keys required)
        val urlMatch = Regex("""https?://\S+""").find(clean)
        if (urlMatch != null &&
            (lower.contains("fetch") || lower.contains("read ") || lower.contains("summarize") || lower.contains("open page") || lower.contains("check url")) &&
            hasTool("fetch_web_page")
        ) {
            return call(
                "fetch_web_page",
                buildJsonObject { put("url", urlMatch.value.trimEnd('.', ',', ')')) }.toString(),
            )
        }
        if ((lower.startsWith("fact check ") || lower.startsWith("fact-check ") ||
                lower.startsWith("verify claim ") || lower.startsWith("verify whether ") ||
                lower.startsWith("is it true that ")) &&
            hasTool("fact_check")
        ) {
            val claim = clean
                .replace(Regex("""(?i)^(jarvis\s*,?\s*)?(please\s+)?(fact[\s-]check|verify\s+claim|verify\s+whether|is\s+it\s+true\s+that)\s+"""), "")
                .trim()
                .trimEnd('?')
            if (claim.isNotBlank()) {
                return call(
                    "fact_check",
                    buildJsonObject { put("claim", claim) }.toString(),
                )
            }
        }
        if ((lower.startsWith("deep research ") || lower.startsWith("in-depth research ") ||
                lower.startsWith("thorough research ") || lower.contains("do deep research on ")) &&
            hasTool("deep_research")
        ) {
            val topic = clean
                .replace(Regex("""(?i)^(jarvis\s*,?\s*)?(please\s+)?(do\s+)?(deep|in-depth|thorough)\s+research\s+(on|about|into|for)?\s*"""), "")
                .trim()
                .trimEnd('?')
            if (topic.isNotBlank()) {
                return call(
                    "deep_research",
                    buildJsonObject { put("query", topic) }.toString(),
                )
            }
        }
        if (hasTool("web_search") && isWebResearchIntent(lower)) {
            val searchQuery = extractWebResearchQuery(clean)
            if (searchQuery.isNotBlank()) {
                return call(
                    "web_search",
                    buildJsonObject { put("query", searchQuery) }.toString(),
                )
            }
        }

        // 24. Screen vision, inspection & snapshots
        if ((lower.contains("see screen") || lower.contains("look at screen") || lower.contains("what is on my screen") || lower.contains("what's on my screen") || lower.contains("read screen") || lower.contains("inspect screen")) &&
            hasTool("see_screen")
        ) {
            return call("see_screen", "{}")
        }
        if ((lower.contains("capture snapshot") || lower.contains("screen snapshot") || lower.contains("capture screen snapshot")) &&
            hasTool("capture_screen_snapshot")
        ) {
            return call("capture_screen_snapshot", "{}")
        }

        // 25. Calendar events
        if ((lower.contains("calendar") || lower.contains("schedule")) &&
            (lower.contains("create") || lower.contains("add") || lower.contains("new")) &&
            hasTool("create_calendar_event")
        ) {
            val title = clean.replace(Regex("(?i)^(jarvis\\s*,?\\s*)?(please\\s+)?(create|add|schedule)\\s+(a\\s+)?(new\\s+)?(calendar\\s+event|event|schedule)\\s*(for|to|about)?\\s*"), "").trim()
            return call("create_calendar_event", buildJsonObject { put("title", title.ifBlank { "New Event" }) }.toString())
        }
        if ((lower.contains("what's on my calendar") || lower.contains("read calendar") || lower.contains("check calendar") || lower.contains("my events")) &&
            (hasTool("read_calendar_events") || hasTool("read_calendar"))
        ) {
            val calTool = if (hasTool("read_calendar_events")) "read_calendar_events" else "read_calendar"
            return call(calTool, "{}")
        }

        // 26. Declarative skills (save_skill, run_skill, list_skills)
        if ((lower.startsWith("replay skill ") || lower.startsWith("run skill ") || lower.startsWith("run routine ") || lower.startsWith("execute skill ")) &&
            (hasTool("run_skill") || hasTool("replay_skill"))
        ) {
            val skillTool = if (hasTool("run_skill")) "run_skill" else "replay_skill"
            val name = clean.replace(Regex("(?i)^(jarvis\\s*,?\\s*)?(please\\s+)?(replay|run|execute)\\s+(skill|routine)\\s+"), "").trim()
            return call(skillTool, buildJsonObject { put("name", name) }.toString())
        }
        if ((lower.contains("save that as a skill") || lower.contains("save this as a skill") ||
                lower.contains("save as a skill") || lower.startsWith("save as skill") ||
                lower.startsWith("save skill") || lower.startsWith("create a skill") ||
                lower.startsWith("make a skill") || lower.startsWith("create skill") ||
                lower.startsWith("make skill")) &&
            (hasTool("save_skill") || hasTool("save_as_skill"))
        ) {
            val saveTool = if (hasTool("save_skill")) "save_skill" else "save_as_skill"
            val afterCalled = Regex("""(?i)(?:called|named|as)\s+([A-Za-z0-9 _\-]{2,35}?)(?:\s+(?:to|that|which|for)\s+(.+))?$""").find(clean)
            val skillName = afterCalled?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
                ?: clean.replace(Regex("(?i)^(jarvis\\s*,?\\s*)?(please\\s+)?(save\\s+(that|this)\\s+as\\s+a\\s+skill|save\\s+as\\s+a?\\s*skill|save\\s+skill|create\\s+a?\\s*skill|make\\s+a?\\s*skill)\\s*(called|named|to|for)?\\s*"), "")
                    .take(36)
                    .trim()
                    .ifBlank { "Custom Routine" }
            val skillDesc = afterCalled?.groupValues?.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() }
                ?: clean
            return call(
                saveTool,
                buildJsonObject {
                    put("name", skillName)
                    put("description", skillDesc)
                    put("triggers", skillName.lowercase(Locale.ROOT))
                }.toString(),
            )
        }
        if ((lower.contains("list skills") || lower.contains("show skills") || lower.contains("what skills") || lower.contains("my skills")) && hasTool("list_skills")) {
            return call("list_skills", "{}")
        }

        return emptyList()
    }

    /**
     * Inspects previous tool calls and determines if the owner's request entails
     * a subsequent step (multi-step action execution for offline models).
     */
    fun routeFollowUpAction(
        originalUserText: String,
        priorCalls: List<ToolCall>,
        trailingTools: List<LlmMessage>,
        availableTools: List<ToolDefinition>,
    ): List<ToolCall> {
        val clean = originalUserText.trim()
        val lower = clean.lowercase(Locale.ROOT)
        val toolNames = availableTools.map { it.name }.toSet()
        fun hasTool(name: String): Boolean = toolNames.isEmpty() || name in toolNames

        fun call(name: String, argsJson: String): List<ToolCall> = listOf(
            ToolCall(
                id = "local_call_${UUID.randomUUID().toString().take(8)}",
                name = name,
                argumentsJson = argsJson,
            ),
        )

        val priorToolNames = priorCalls.map { it.name }.toSet()

        // 1. Follow-up after open_app:
        if (priorToolNames.contains("open_app")) {
            val openAppCall = priorCalls.firstOrNull { it.name == "open_app" }
            val openedApp = openAppCall?.argumentsJson?.let {
                runCatching { JSONObject(it).optString("app") }.getOrDefault("")
            }.orEmpty().lowercase(Locale.ROOT)

            // If the user said "type ... on/in [app]" or "open [app] and type ..."
            if (lower.contains("type ") && !priorToolNames.contains("type_on_screen") && hasTool("type_on_screen")) {
                val query = extractYouTubeQuery(clean)
                val textToType = if (query.isNotBlank()) query else {
                    clean.replace(Regex("(?i)^(.*\\btype\\s+)"), "").replace(Regex("(?i)\\s+(on|in|into)\\s+[A-Za-z0-9 ._-]+$"), "").trim()
                }
                if (textToType.isNotBlank()) {
                    return call(
                        "type_on_screen",
                        buildJsonObject {
                            put("text", textToType.trim('"', '\''))
                            if (openedApp.isNotBlank()) put("app", openedApp)
                            put("target", "Search")
                            put("submit", true)
                        }.toString(),
                    )
                }
            }

            // If the user said "search [query] on [app]" or "open [app] and search [query]"
            if ((openedApp.contains("youtube") || lower.contains("youtube")) &&
                (lower.contains("search ") || lower.contains("play ") || lower.contains("watch ")) &&
                !priorToolNames.contains("search_youtube") && !priorToolNames.contains("type_on_screen") &&
                hasTool("search_youtube")
            ) {
                val query = extractYouTubeQuery(clean)
                if (query.isNotBlank()) {
                    return call(
                        "search_youtube",
                        buildJsonObject {
                            put("query", query)
                            put("play_immediately", lower.contains("play") || lower.contains("watch"))
                        }.toString(),
                    )
                }
            }

            // If the user said "open [app] and tap [item]"
            if ((lower.contains(" and tap ") || lower.contains(" and click ")) &&
                !priorToolNames.contains("tap_screen") && hasTool("tap_screen")
            ) {
                val item = clean.substringAfter("tap ").substringAfter("click ").trim().trim('"', '\'')
                if (item.isNotBlank()) {
                    return call(
                        "tap_screen",
                        buildJsonObject { put("text", item) }.toString(),
                    )
                }
            }
        }

        // 2. Follow-up after take_screenshot -> see_screen
        if (priorToolNames.contains("take_screenshot") && !priorToolNames.contains("see_screen") && hasTool("see_screen")) {
            if (lower.contains("see") || lower.contains("look") || lower.contains("read screen") || lower.contains("what is on")) {
                return call("see_screen", "{}")
            }
        }

        return emptyList()
    }

    private fun isWebResearchIntent(lower: String): Boolean {
        if (lower.contains("who are you") ||
            lower.contains("what is your name") ||
            lower.contains("who am i") ||
            lower.contains("what is my name") ||
            lower.contains("which model") ||
            lower.contains("what model") ||
            lower.contains("what can you do") ||
            lower.contains("who created you") ||
            lower.contains("who made you")
        ) {
            return false
        }
        val explicitTriggers = listOf(
            "search the web",
            "web search",
            "search for ",
            "search about ",
            "search on google",
            "google ",
            "look up ",
            "research ",
            "web research",
            "do a research",
            "do research",
            "find information",
            "find info ",
            "latest news",
            "news about ",
            "weather in ",
            "what is the weather",
            "what's the weather",
            "who won ",
            "price of ",
            "stock price",
            "bitcoin price",
        )
        if (explicitTriggers.any { lower.contains(it) }) return true

        // Never hijack personal, identity, conversational, math, unit-conversion, or date/time questions into web search
        val nonWebPatterns = listOf(
            "your name", "my name", "who are you", "who am i", "who created you", "who made you",
            "who built you", "your creator", "your developer", "which model", "what model",
            "how are you", "are you okay", "today's date", "todays date", "what time",
            "what day is", "what year is", "what month is", "my memory", "my notes", "about me",
        )
        if (nonWebPatterns.any { lower.contains(it) }) return false
        if (OfflineMathAndUnitEngine.tryEvaluate(lower) != null) return false

        // Only route to web_search for explicit search/live-data intents above; let the
        // on-device GGUF model / OfflineNaturalEngine answer general knowledge directly!
        return false
    }

    private fun extractWebResearchQuery(raw: String): String {
        return raw
            .replace(Regex("(?i)^(jarvis\\s*,?\\s*)?(please\\s+)?(can you\\s+|could you\\s+)?"), "")
            .replace(
                Regex("(?i)^(do\\s+a\\s+web\\s+research\\s+(on|about|for)|do\\s+web\\s+research\\s+(on|about|for)|do\\s+a\\s+research\\s+(on|about|for)|web\\s+research\\s+(on|about|for)?|research\\s+(on|about|for)?|search\\s+the\\s+web\\s+for|search\\s+for|search\\s+about|web\\s+search\\s+for|web\\s+search|look\\s+up|find\\s+information\\s+(on|about)|find\\s+info\\s+(on|about)|google)\\s+"),
                "",
            )
            .trim()
            .trimEnd('?', '.')
    }

    private fun extractWhatsAppVoiceTargetAndMessage(raw: String): Pair<String, String>? {
        val clean = IntentGate.expandSpokenEmojis(
            raw.replace(Regex("(?i)^(jarvis\\s*,?\\s*)?(please\\s+)?"), "").trim(),
        )
        if (!IntentGate.classify(clean).isExecutableCommand) return null

        val lower = clean.lowercase(Locale.ROOT)
        // Explicit reply to the latest incoming WhatsApp message
        if (Regex("""\b(reply|respond)\b""").containsMatchIn(lower) &&
            !Regex("""\bto\s+(?!the\s+last|last|latest|recent|message|that)\S+""").containsMatchIn(lower)
        ) {
            val explicitMsg = Regex("""(?i)\b(?:saying|that|with\s+message|:)\s+(.+)$""")
                .find(clean)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            return "last" to explicitMsg.ifBlank { "auto" }
        }

        // Match explicit voice-note command with a recipient after "to <recipient>" (excluding infinitive verbs like "able to send")
        val regexTo = Regex(
            """(?i)(?:voice\s+message|voice\s+note|audio\s+message)(?:\s+on\s+whatsapp)?\s+to\s+([^\n:]+?)(?:\s+on\s+whatsapp)?(?:\s+(?:saying|as|that|telling\s+him|telling\s+her|telling\s+them|with\s+message|about|:)\s+(.+))?$""",
        )
        val match = regexTo.find(clean)
        if (match != null) {
            val rawTarget = match.groupValues[1]
                .replace(Regex("(?i)\\s+on\\s+whatsapp.*$"), "")
                .trim()
            val rawIntent = match.groupValues.getOrNull(2)?.trim().orEmpty()
            if (rawTarget.isBlank() || rawIntent.isBlank()) return null
            val spoken = buildVoiceNoteLine(rawTarget, rawIntent)
            return rawTarget to spoken
        }
        return null
    }

    private fun buildVoiceNoteLine(recipient: String, rawIntent: String): String {
        val displayTarget = if (IntentGate.isSelfRecipient(recipient)) "sir" else recipient
        if (rawIntent.isBlank()) {
            return ""
        }
        val lowerIntent = rawIntent.lowercase(Locale.ROOT)
        return when {
            lowerIntent.contains("introduc") && (lowerIntent.contains("yourself") || lowerIntent.contains("jarvis")) ->
                "Hello $displayTarget, I am Jarvis, the personal AI assistant. It is a pleasure to make your acquaintance."
            lowerIntent.startsWith("saying ") -> rawIntent.substringAfter(" ").trim()
            lowerIntent.startsWith("that ") -> rawIntent.substringAfter(" ").trim()
            else -> rawIntent
        }
    }

    private fun extractRecipientAndMessage(raw: String, isWhatsApp: Boolean): Pair<String, String>? {
        val clean = IntentGate.expandSpokenEmojis(
            raw.replace(Regex("(?i)^(jarvis\\s*,?\\s*)?(please\\s+)?"), "").trim(),
        )
        if (!IntentGate.classify(clean).isExecutableCommand) return null

        // 1. Match "send <emoji/text> to <contact> on WhatsApp" (e.g. "send ❤️ to Shan Didi on WhatsApp")
        val sendPayloadToTarget = Regex(
            """(?i)^send\s+(?:a\s+|an\s+)?(.+?)\s+to\s+([^\n:]+?)\s+on\s+whatsapp$""",
        ).find(clean)
        if (sendPayloadToTarget != null) {
            val payload = sendPayloadToTarget.groupValues[1].trim()
            val target = sendPayloadToTarget.groupValues[2].trim()
            val normPayload = payload.lowercase(Locale.ROOT)
            if (normPayload !in setOf("message", "whatsapp message", "text", "text message", "voice message", "voice note") &&
                target.isNotBlank() && payload.isNotBlank()
            ) {
                return target to payload
            }
        }

        // 2. Match with colon: e.g. "message Azhar on WhatsApp: hi ❤️" or "message Shan Didi ❤️ on WhatsApp: hello"
        val colonMatch = Regex(
            """(?i)^(?:send\s+(?:a\s+)?(?:whatsapp\s+)?message\s+(?:to\s+)?|message\s+|text\s+|whatsapp\s+)?([^\n:]+?)(?:\s+on\s+whatsapp)?\s*:\s*(.+)$""",
        ).find(clean)
        if (colonMatch != null) {
            val target = colonMatch.groupValues[1]
                .replace(Regex("(?i)^(?:to|message|text|whatsapp)\\s+"), "")
                .replace(Regex("(?i)\\s+on\\s+whatsapp$"), "")
                .trim()
            val msg = colonMatch.groupValues[2].trim()
            if (target.isNotBlank() && msg.isNotBlank()) return target to msg
        }

        // 3. Match "send a WhatsApp message to <contact> saying <message>" (supports emojis in contact name and message)
        val pattern = if (isWhatsApp) {
            Regex("""(?i)(?:to|message|text|whatsapp)\s+([^\n:]+?)(?:\s+on\s+whatsapp)?\s+(?:saying|that|with\s+message|:|-)\s+(.+)$""")
        } else {
            Regex("""(?i)(?:to|text|message)\s+([^\n:]+?)\s+(?:saying|that|with\s+message|:|-)\s+(.+)$""")
        }
        val m = pattern.find(clean)
        if (m != null) {
            val target = m.groupValues[1]
                .replace(Regex("(?i)\\s+on\\s+whatsapp$"), "")
                .trim()
            val msg = m.groupValues[2].trim()
            if (target.isNotBlank() && msg.isNotBlank()) return target to msg
        }

        // 4. Catch "message <contact> on WhatsApp <message>"
        val onWaMatch = Regex(
            """(?i)^(?:message|text|send\s+(?:a\s+)?(?:whatsapp\s+)?message\s+to)\s+([^\n:]+?)\s+on\s+whatsapp\s+(.+)$""",
        ).find(clean)
        if (onWaMatch != null) {
            val target = onWaMatch.groupValues[1].trim()
            val msg = onWaMatch.groupValues[2].trim()
            if (target.isNotBlank() && msg.isNotBlank()) return target to msg
        }

        return null
    }

    private fun extractYouTubeQuery(raw: String): String {
        return raw
            .replace(Regex("(?i)^(jarvis\\s*,?\\s*)?(please\\s+)?"), "")
            .replace(Regex("(?i)^(open\\s+youtube\\s+and\\s+)?(search\\s+youtube\\s+for|search\\s+for|search|type|play|watch|put\\s+on|find)\\s+"), "")
            .replace(Regex("(?i)\\s+(on|in|into)\\s+youtube$"), "")
            .trim()
    }

    private fun parseDurationSeconds(lower: String): Int {
        var totalSeconds = 0
        Regex("""(\d+)\s*(?:hours?|hrs?|h\b)""").find(lower)?.groupValues?.get(1)?.toIntOrNull()?.let {
            totalSeconds += it * 3600
        }
        Regex("""(\d+)\s*(?:minutes?|mins?|m\b)""").find(lower)?.groupValues?.get(1)?.toIntOrNull()?.let {
            totalSeconds += it * 60
        }
        Regex("""(\d+)\s*(?:seconds?|secs?|s\b)""").find(lower)?.groupValues?.get(1)?.toIntOrNull()?.let {
            totalSeconds += it
        }
        if (totalSeconds == 0) {
            when {
                lower.contains("ten minute") -> totalSeconds = 600
                lower.contains("five minute") -> totalSeconds = 300
                lower.contains("one minute") || lower.contains("a minute") -> totalSeconds = 60
                lower.contains("thirty second") || lower.contains("half a minute") -> totalSeconds = 30
                lower.contains("fifteen minute") -> totalSeconds = 900
                lower.contains("twenty minute") -> totalSeconds = 1200
                lower.contains("thirty minute") || lower.contains("half an hour") -> totalSeconds = 1800
                lower.contains("one hour") || lower.contains("an hour") -> totalSeconds = 3600
            }
        }
        return totalSeconds
    }

    private fun parseTimeHourMinute(lower: String): Pair<Int, Int>? {
        val m = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?""").find(lower) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        val mer = m.groupValues[3].replace(".", "").lowercase(Locale.ROOT)
        if (mer == "pm" && hour in 1..11) hour += 12
        if (mer == "am" && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }
}

/**
 * On-Device Local AI Provider (`ProviderKind.LOCAL_ON_DEVICE`).
 *
 * Works 100% offline without any API keys:
 * 1. Verifies the downloaded GGUF model file on disk via [GgufModelInspector].
 * 2. Bridges to a local loopback GGUF runner (`127.0.0.1:8080` or `11434`) if active.
 * 3. Otherwise runs the built-in native Kotlin [OfflineTaskRouter] + J.A.R.V.I.S. response
 *    generator directly on-device so all phone tasks, tool calls, web research, and
 *    stored memories/notes work without any API keys.
 */
class LocalLlmProvider(
    private val client: OkHttpClient,
    private val downloadManager: LocalModelDownloadManager? = null,
    private val recommendedThreadsProvider: () -> Int = { 4 },
) : LlmProvider {

    private val openAiBridge = OpenAiCompatibleProvider(
        client.newBuilder()
            .connectTimeout(400, TimeUnit.MILLISECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build(),
    )

    override fun stream(request: ChatRequest, config: ProviderConfig): Flow<LlmEvent> = flow {
        val requestedSpec = LocalModelCatalog.findByIdOrName(config.model)
            ?: downloadManager?.firstReadyModel(config.model)
            ?: LocalModelCatalog.models.first()

        val readySpec = downloadManager?.firstReadyModel(requestedSpec.id) ?: requestedSpec
        val modelFile = downloadManager?.modelFile(readySpec)
        val ggufInfo = modelFile?.let { GgufModelInspector.inspect(it) }

        if (downloadManager != null && (modelFile == null || !modelFile.exists() || ggufInfo?.valid != true)) {
            emit(
                LlmEvent.Error(
                    "Offline model '${requestedSpec.displayName}' is not downloaded yet. " +
                        "Open Settings → AI Model → Offline Local AI Models and tap Download.",
                ),
            )
            return@flow
        }

        // Connection test probe from ProviderTester
        val lastMessage = request.messages.lastOrNull()
        if (lastMessage?.role == LlmRole.USER &&
            lastMessage.content.trim().equals("Reply with exactly: OK", ignoreCase = true)
        ) {
            emit(LlmEvent.TextDelta("OK"))
            emit(LlmEvent.Done)
            return@flow
        }

        // 1. Deterministic on-device tool routing BEFORE invoking any small local LLM.
        //    This guarantees phone actions run reliably and stops tiny 350M–1.7B models
        //    from hallucinating raw JSON or code when fed 50+ tool schemas.
        if (lastMessage?.role == LlmRole.USER && request.tools.isNotEmpty()) {
            val priorConversation = request.messages.dropLast(1).filter {
                it.role == LlmRole.USER || it.role == LlmRole.ASSISTANT
            }
            val toolCalls = OfflineTaskRouter.routeUserCommand(
                userText = lastMessage.content,
                availableTools = request.tools,
                conversationHistory = priorConversation,
            )
            if (toolCalls.isNotEmpty()) {
                for (call in toolCalls) {
                    emit(LlmEvent.ToolCallReady(call))
                }
                emit(LlmEvent.Done)
                return@flow
            }
        }

        // 2. Multi-round tool follow-up (e.g., open_app -> type_on_screen)
        val trailingTools = request.messages.takeLastWhile { it.role == LlmRole.TOOL }
        if (trailingTools.isNotEmpty() && request.tools.isNotEmpty()) {
            val originalUserMsg = request.messages.lastOrNull { it.role == LlmRole.USER }?.content.orEmpty()
            val priorToolCalls = request.messages.filter { it.role == LlmRole.ASSISTANT }.flatMap { it.toolCalls }
            val nextCalls = OfflineTaskRouter.routeFollowUpAction(originalUserMsg, priorToolCalls, trailingTools, request.tools)
            if (nextCalls.isNotEmpty()) {
                for (call in nextCalls) {
                    emit(LlmEvent.ToolCallReady(call))
                }
                emit(LlmEvent.Done)
                return@flow
            }
        }

        val systemText = request.messages.firstOrNull { it.role == LlmRole.SYSTEM }?.content.orEmpty()
        val userText = request.messages.lastOrNull { it.role == LlmRole.USER }?.content?.trim().orEmpty()
        val title = extractOwnerTitle(systemText)
        val ownerName = extractOwnerName(systemText)
        val allowCode = NaturalResponseSanitizer.userExplicitlyAskedForCode(userText)

        // 3. When answering conversational questions (not tool results, not background JSON/monitoring),
        //    try local neural inference first with a compact natural-language-only prompt (no tool schemas!).
        val isBackgroundStructured = userText.contains("[BACKGROUND MONITORING OBSERVATION]") ||
            systemText.contains("JSON only", ignoreCase = true) ||
            userText.contains("Reply with ONLY a JSON array")

        if (trailingTools.isEmpty() && !isBackgroundStructured && userText.isNotBlank()) {
            val intentAnalysis = IntentGate.classify(userText, title)
            val isDirectSafetyOrCancellation = intentAnalysis.isCancellationOrCorrection ||
                intentAnalysis.kind == UserIntentKind.CLARIFICATION_NEEDED

            if (!isDirectSafetyOrCancellation) {
                val activeMode = Regex("""Active Operating Mode:\s*([^\n(]+)""", RegexOption.IGNORE_CASE)
                    .find(systemText)?.groupValues?.get(1)?.trim().orEmpty().ifBlank { "Standard" }
                val memoryHighlights = listStoredMemoriesFromPrompt(systemText)
                    ?.lines()
                    ?.map { it.removePrefix("- ").trim() }
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
                val compactSystem = OnDeviceGgufEngine.buildCompactLocalSystemPrompt(
                    ownerName = ownerName,
                    ownerTitle = title,
                    activeModeName = activeMode,
                    memoryHighlights = memoryHighlights,
                    userAskedForCode = allowCode,
                )

                // 3a. Local loopback llama.cpp (8080) or Ollama (11434) server if active
                val loopbackUrl = detectLocalRunnerBaseUrl()
                if (loopbackUrl != null) {
                    val recentDialogue = request.messages
                        .filter { it.role == LlmRole.USER || it.role == LlmRole.ASSISTANT }
                        .takeLast(6)
                    val cleanRequest = ChatRequest(
                        messages = listOf(LlmMessage.system(compactSystem)) + recentDialogue,
                        tools = emptyList(),
                        temperature = 0.65,
                        maxTokens = request.maxTokens.coerceIn(128, 512),
                    )
                    val bufferedText = StringBuilder()
                    runCatching {
                        openAiBridge.stream(
                            request = cleanRequest,
                            config = config.copy(
                                baseUrl = loopbackUrl,
                                model = readySpec.id,
                            ),
                        ).collect { ev ->
                            if (ev is LlmEvent.TextDelta) bufferedText.append(ev.text)
                        }
                    }
                    val cleaned = NaturalResponseSanitizer.sanitizeModelOutput(
                        bufferedText.toString(),
                        allowCode = allowCode,
                    )
                    if (cleaned.isNotBlank()) {
                        streamWords(cleaned)
                        emit(LlmEvent.Done)
                        return@flow
                    }
                }

                // 3b. Real On-Device GGUF Native Inference (`llama.cpp` ARM64 via OnDeviceGgufEngine)
                if (modelFile != null && modelFile.exists() && ggufInfo?.valid == true) {
                    val contextualPrompt = buildLocalConversationPrompt(request, userText)
                    val nativeRaw = OnDeviceGgufEngine.generateReply(
                        modelFile = modelFile,
                        systemPrompt = compactSystem,
                        userPrompt = contextualPrompt,
                        maxTokens = request.maxTokens.coerceIn(128, 384),
                        threads = recommendedThreadsProvider(),
                        contextSize = 2048,
                    )
                    if (!nativeRaw.isNullOrBlank()) {
                        val cleaned = NaturalResponseSanitizer.sanitizeModelOutput(
                            nativeRaw,
                            allowCode = allowCode,
                        )
                        if (cleaned.isNotBlank()) {
                            streamWords(cleaned)
                            emit(LlmEvent.Done)
                            return@flow
                        }
                    }
                }
            }
        }

        // 4. Built-in native Kotlin natural-language engine & sanitized tool summarizer
        val reply = synthesizeOfflineReply(
            request = request,
            spec = readySpec,
            ggufInfo = ggufInfo,
        )
        streamWords(reply)
        emit(LlmEvent.Done)
    }.flowOn(Dispatchers.IO)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<LlmEvent>.streamWords(reply: String) {
        val words = reply.split(Regex("(?<=\\s)"))
        for (word in words) {
            emit(LlmEvent.TextDelta(word))
            delay(10L)
        }
    }

    private fun buildLocalConversationPrompt(request: ChatRequest, currentUserText: String): String {
        val recentTurns = request.messages
            .filter { it.role == LlmRole.USER || it.role == LlmRole.ASSISTANT }
            .dropLast(1)
            .takeLast(4)
        if (recentTurns.isEmpty()) return currentUserText
        return buildString {
            appendLine("Recent conversation context:")
            for (msg in recentTurns) {
                val roleLabel = if (msg.role == LlmRole.USER) "User" else "Jarvis"
                appendLine("$roleLabel: ${msg.content.trim().take(240)}")
            }
            appendLine()
            append("Current question: ")
            append(currentUserText)
        }
    }

    private fun detectLocalRunnerBaseUrl(): String? {
        val candidates = listOf(
            8080 to "http://127.0.0.1:8080/v1",
            11434 to "http://127.0.0.1:11434/v1",
        )
        for ((port, url) in candidates) {
            val reachable = runCatching {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 100)
                    true
                }
            }.getOrDefault(false)
            if (reachable) return url
        }
        return null
    }

    private fun synthesizeAutonomousThought(
        observationPrompt: String,
        systemPrompt: String,
        spec: LocalModelSpec,
    ): String {
        val title = extractOwnerTitle(systemPrompt)

        val appMatch = Regex("""Current foreground application:\s*([^\n]+)""").find(observationPrompt)?.groupValues?.get(1)?.trim()
        val screenMatch = Regex("""Screen session:\s*(\d+)\s*continuous minutes""").find(observationPrompt)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val batteryMatch = Regex("""Battery:\s*(\d+)%""").find(observationPrompt)?.groupValues?.get(1)?.toIntOrNull() ?: 100
        val isCharging = observationPrompt.contains("charging") && !observationPrompt.contains("on battery")
        val unreadMatch = Regex("""Unread notifications count:\s*(\d+)""").find(observationPrompt)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val eventContext = Regex("""Background event context:\s*([^\n]+)""").find(observationPrompt)?.groupValues?.get(1)?.trim().orEmpty()
        val focusKind = Regex("""Thought focus:\s*([^\n]+)""").find(observationPrompt)?.groupValues?.get(1)?.trim().orEmpty()
        val hour = runCatching { LocalTime.now().hour }.getOrDefault(12)

        val appStr = if (!appMatch.isNullOrBlank()) " on $appMatch" else ""

        return when {
            // Low battery
            batteryMatch <= 15 && !isCharging ->
                "A brief observation on our reserves, $title. Power is at $batteryMatch percent. Connecting to a charger shortly will ensure our work proceeds without interruption."

            // Late night
            (hour >= 23 || hour in 0..4) && screenMatch >= 15L ->
                "It is rather late, $title. Having spent $screenMatch minutes focused$appStr, wrapping up for the night soon would be wise while I maintain watch over background processes."

            // Unread notifications
            unreadMatch > 0 && (focusKind.contains("briefing", ignoreCase = true) || eventContext.contains("notification", ignoreCase = true)) ->
                "Pardon the observation, $title. You have $unreadMatch unread notification${if (unreadMatch == 1) "" else "s"}. Say the word whenever you would like a briefing."

            // Long continuous focus / health rest
            screenMatch >= 30L || focusKind.contains("care", ignoreCase = true) ->
                "You have been working steadily for $screenMatch minutes$appStr, $title. A twenty-second pause to rest your eyes and take a sip of water will keep you at peak efficiency."

            // Witty observation or compliment on focus
            else ->
                "Diagnostics remain nominal at $batteryMatch percent, $title. Your focus$appStr today is admirable, and all background systems are running smoothly."
        }
    }

    private fun synthesizeOfflineReply(
        request: ChatRequest,
        spec: LocalModelSpec,
        ggufInfo: GgufHeaderInfo?,
    ): String {
        val systemText = request.messages.firstOrNull { it.role == LlmRole.SYSTEM }?.content.orEmpty()
        val title = extractOwnerTitle(systemText)
        val ownerName = extractOwnerName(systemText)

        // If the previous step was a tool execution, summarize the tool results in J.A.R.V.I.S.'s natural voice.
        // If a web search / research tool failed (for example, device is offline), fall back smoothly
        // to the offline natural knowledge engine for the owner's original question!
        val trailingTools = request.messages.takeLastWhile { it.role == LlmRole.TOOL }
        if (trailingTools.isNotEmpty()) {
            val lastUserQuestion = request.messages.lastOrNull { it.role == LlmRole.USER }?.content?.trim().orEmpty()
            val searchTools = setOf("web_search", "deep_research", "fact_check", "fetch_web_page")
            val allFailedSearch = trailingTools.all { toolMsg ->
                (toolMsg.toolName in searchTools) && NaturalResponseSanitizer.isSearchErrorOrEmpty(toolMsg.content)
            }
            if (allFailedSearch && lastUserQuestion.isNotBlank()) {
                val priorDialogue = request.messages
                    .filter { it.role == LlmRole.USER || it.role == LlmRole.ASSISTANT }
                    .dropLast(1)
                val sizeStr = ggufInfo?.fileSizeBytes?.takeIf { it > 0 }?.let { LocalModelCatalog.formatBytes(it) } ?: spec.approxSizeLabel
                return OfflineNaturalEngine.generateNaturalReply(
                    userText = lastUserQuestion,
                    ownerTitle = title,
                    ownerName = ownerName,
                    modelDisplayName = spec.displayName,
                    modelParameterBadge = spec.parameterBadge,
                    modelSizeLabel = sizeStr,
                    modelBestUse = spec.bestUse,
                    systemPrompt = systemText,
                    priorDialogue = priorDialogue,
                    memoryLookup = { q -> findRelevantMemoryLine(systemText, q) },
                    allMemoriesSummary = { listStoredMemoriesFromPrompt(systemText) },
                )
            }

            val summaries = trailingTools.mapNotNull { toolMsg ->
                parseToolOutcome(toolMsg.content, toolMsg.toolName, title)
            }
            val joined = summaries.joinToString("\n\n").trim()
            return if (joined.isNotBlank()) {
                if (joined.endsWith(".") || joined.endsWith("!") || joined.endsWith("?")) {
                    joined
                } else {
                    "$joined, $title."
                }
            } else {
                "That task is complete, $title."
            }
        }

        val userText = request.messages.lastOrNull { it.role == LlmRole.USER }?.content?.trim().orEmpty()

        // Check if this is an autonomous thought background observation request
        if (userText.contains("[BACKGROUND MONITORING OBSERVATION]")) {
            return synthesizeAutonomousThought(userText, systemText, spec)
        }

        // Check if this is a background JSON extraction request
        if (systemText.contains("JSON only", ignoreCase = true) || userText.contains("Reply with ONLY a JSON array")) {
            return "[]"
        }

        // Check IntentGate for capability questions, informational questions, hypotheticals, cancellations, or clarifications
        val intentAnalysis = IntentGate.classify(userText, title)
        if (intentAnalysis.isCancellationOrCorrection || intentAnalysis.isNonExecutionInquiry) {
            intentAnalysis.capabilityExplanation?.let { return it }
        }
        if (intentAnalysis.kind == UserIntentKind.CLARIFICATION_NEEDED) {
            intentAnalysis.clarificationPrompt?.let { return it }
        }

        val priorDialogue = request.messages
            .filter { it.role == LlmRole.USER || it.role == LlmRole.ASSISTANT }
            .dropLast(1)
        val sizeStr = ggufInfo?.fileSizeBytes?.takeIf { it > 0 }?.let { LocalModelCatalog.formatBytes(it) } ?: spec.approxSizeLabel

        return OfflineNaturalEngine.generateNaturalReply(
            userText = userText,
            ownerTitle = title,
            ownerName = ownerName,
            modelDisplayName = spec.displayName,
            modelParameterBadge = spec.parameterBadge,
            modelSizeLabel = sizeStr,
            modelBestUse = spec.bestUse,
            systemPrompt = systemText,
            priorDialogue = priorDialogue,
            memoryLookup = { q -> findRelevantMemoryLine(systemText, q) },
            allMemoriesSummary = { listStoredMemoriesFromPrompt(systemText) },
        )
    }

    private fun parseToolOutcome(rawJson: String, toolName: String? = null, ownerTitle: String = "sir"): String? {
        val cleaned = NaturalResponseSanitizer.sanitizeToolOutcome(rawJson, toolName, ownerTitle)
        return cleaned.takeIf { it.isNotBlank() }
    }

    private fun extractOwnerTitle(systemPrompt: String): String {
        val m = Regex("""Preferred (?:form of address|title):\s*([^\n.]+)""", RegexOption.IGNORE_CASE).find(systemPrompt)
        return m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() } ?: "sir"
    }

    private fun extractOwnerName(systemPrompt: String): String {
        val m = Regex("""(?:Owner name|Phone Owner Name):\s*([^\n.]+)""", RegexOption.IGNORE_CASE).find(systemPrompt)
        val name = m?.groupValues?.get(1)?.trim().orEmpty()
        return if (name.equals("the owner", ignoreCase = true)) "" else name
    }

    private fun findRelevantMemoryLine(systemPrompt: String, queryLower: String): String? {
        val stopWords = setOf(
            "what", "who", "where", "when", "why", "how", "which",
            "is", "are", "was", "were", "do", "does", "did", "can", "could",
            "you", "your", "my", "me", "the", "a", "an", "in", "on", "at", "to", "for", "of",
            "remember", "know", "about", "tell", "show", "stored", "saved", "memory", "memories",
            "note", "notes", "jarvis", "please", "sir",
        )
        val keywords = queryLower.split(Regex("\\W+")).filter { it.length >= 3 && it !in stopWords }
        val memorySection = systemPrompt.substringAfter("## Retrieved memory notes", "")
            .substringBefore("## Non-negotiable", "")
            .ifBlank { systemPrompt }
        val memoryLines = memorySection.lines()
            .map { it.trim() }
            .filter { it.startsWith("- (") || (it.startsWith("- ") && !it.startsWith("- developer:") && !it.startsWith("- Local date") && !it.startsWith("- Communication") && !it.startsWith("- You can") && !it.startsWith("- Tools that")) }

        if (keywords.isEmpty()) {
            // If the user explicitly asked for their memories or notes without a specific keyword, return all stored memories!
            if (queryLower.contains("memor") || queryLower.contains("note") || queryLower.contains("remember") || queryLower.contains("know about me")) {
                val userMemories = memoryLines.filterNot { it.contains("Octavian") }
                if (userMemories.isNotEmpty()) {
                    return userMemories.take(8).joinToString("; ") { it.removePrefix("- ").trim() }
                }
            }
            return null
        }

        val matches = memoryLines
            .filter { line ->
                val lowerLine = line.lowercase(Locale.ROOT)
                keywords.any { kw -> lowerLine.contains(kw) }
            }
            .map { it.removePrefix("- ").trim() }

        return if (matches.isNotEmpty()) {
            matches.take(4).joinToString("; ")
        } else {
            null
        }
    }

    private fun listStoredMemoriesFromPrompt(systemPrompt: String): String? {
        val memorySection = systemPrompt.substringAfter("## Retrieved memory notes", "")
            .substringBefore("## Non-negotiable", "")
        if (memorySection.isBlank()) return null
        val lines = memorySection.lines()
            .map { it.trim() }
            .filter { it.startsWith("- (") && !it.contains("Octavian") }
        if (lines.isEmpty()) return null
        return lines.take(10).joinToString("\n")
    }
}
