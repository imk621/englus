package com.englus.app.ocr

import android.content.Context
import android.net.Uri
import com.englus.app.EnglusApp
import com.englus.app.data.EnglusDatabase
import com.englus.app.data.Episode
import com.englus.app.data.EpisodeKind
import com.englus.app.data.Sentence
import com.englus.app.data.TranscriptState
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Turns a photo or pasted text into reading material.
 *
 * Reading reuses the episode/sentence tables rather than getting its own: the
 * whole downstream stack — translation, cloud explanation, bookmarks, spaced
 * repetition — already works on sentences, and duplicating it for reading would
 * mean maintaining two of everything for no gain.
 */
class ReadingRepository(private val context: Context) {

    private val dao = EnglusDatabase.get(context).episodes()

    class OcrException(message: String) : Exception(message)

    /** Recognises text in an image. On-device, offline, free. */
    suspend fun recognize(uri: Uri): String = withContext(Dispatchers.IO) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val image = InputImage.fromFilePath(context, uri)
            recognizer.process(image).await().text
        } finally {
            recognizer.close()
        }
    }

    suspend fun importImage(uri: Uri, title: String?): Long {
        val text = recognize(uri)
        if (text.isBlank()) throw OcrException("이미지에서 글자를 찾지 못했습니다.")
        return importText(text, title)
    }

    suspend fun importText(text: String, title: String?): Long = withContext(Dispatchers.IO) {
        val sentences = SentenceSplitter.split(text)
        if (sentences.isEmpty()) throw OcrException("문장을 찾지 못했습니다.")

        val id = dao.insert(
            Episode(
                // The first few words are a better handle than "Document 3".
                title = title?.takeIf { it.isNotBlank() }
                    ?: sentences.first().take(40).trim().ifBlank { "읽기" },
                audioPath = "",
                importedAt = System.currentTimeMillis(),
                // Nothing to transcribe; the text is already the transcript.
                state = TranscriptState.DONE,
                progress = 1f,
                kind = EpisodeKind.READING,
            )
        )
        dao.insertSentences(
            sentences.mapIndexed { i, s ->
                Sentence(
                    episodeId = id,
                    position = i,
                    text = s,
                    startMs = 0,
                    endMs = 0,
                    words = emptyList(),
                )
            }
        )
        (context.applicationContext as EnglusApp).repository.enqueueTranslation(id)
        id
    }

    /** Where a camera capture should be written before it is recognised. */
    fun cameraFile(): File =
        File(context.cacheDir, "capture-${System.currentTimeMillis()}.jpg")
}
