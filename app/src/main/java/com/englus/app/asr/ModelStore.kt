package com.englus.app.asr

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Where the whisper weights live, and how they get there.
 *
 * Importing through the system file picker is the only route that works without
 * adb: Android 11+ blocks file managers from writing into Android/data/<pkg>, so
 * "download the .bin and drop it in the folder" is not a thing a user can do.
 */
class ModelStore(private val context: Context) {

    val dir: File = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }

    fun available(): List<File> {
        // Also scan files/ itself: a directory created by `adb shell mkdir` is
        // owned by shell and unreadable to the app, whereas files/ always works.
        val roots = listOfNotNull(dir, context.getExternalFilesDir(null))
        return roots
            .flatMap { d -> d.listFiles { f -> f.isFile && f.name.endsWith(".bin") }?.toList().orEmpty() }
            .distinctBy { it.name }
            .sortedBy { it.name }
    }

    /**
     * Phase 0 settled this: base.en is accurate enough for study material and
     * fast enough on an S25, so prefer it and never silently fall back to a
     * bigger model that would triple transcription time.
     */
    fun preferred(): File? {
        val all = available()
        return all.firstOrNull { it.name.contains("base.en") } ?: all.firstOrNull()
    }

    class ImportException(message: String) : Exception(message)

    /** Copies a picked .bin in, verifying it really is a ggml model. */
    fun import(uri: Uri, onProgress: (Float) -> Unit = {}): File {
        val name = displayName(uri).ifEmpty { "model.bin" }
        if (!name.endsWith(".bin")) throw ImportException("whisper 모델은 .bin 파일이어야 합니다: $name")

        val total = size(uri)
        val dest = File(dir, name)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) throw ImportException("파일을 열 수 없습니다")
                dest.outputStream().use { output ->
                    val buf = ByteArray(1 shl 16)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        copied += n
                        if (total > 0) onProgress(copied.toFloat() / total)
                    }
                }
            }
            // Checked here rather than at load time: a truncated download would
            // otherwise surface much later as an opaque "model failed to load"
            // in the middle of a transcription job.
            val magic = ByteArray(4)
            dest.inputStream().use { it.read(magic) }
            if (!magic.contentEquals(GGML_MAGIC)) {
                throw ImportException("ggml 모델 파일이 아닙니다 (매직 불일치)")
            }
            return dest
        } catch (e: Throwable) {
            dest.delete()
            throw e
        }
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    private fun size(uri: Uri): Long =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.SIZE)
                if (i >= 0 && c.moveToFirst()) c.getLong(i) else 0L
            }
        }.getOrNull() ?: 0L

    private companion object {
        /** First 4 bytes of a ggml file: GGML_FILE_MAGIC 0x67676d6c, little-endian. */
        val GGML_MAGIC = byteArrayOf(0x6c, 0x6d, 0x67, 0x67)
    }
}
