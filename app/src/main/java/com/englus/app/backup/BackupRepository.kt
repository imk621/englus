package com.englus.app.backup

import android.content.Context
import android.net.Uri
import com.englus.app.data.EnglusDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What a backup file says about itself. */
data class BackupInfo(
    val schemaVersion: Int,
    val createdAt: Long,
    val episodes: Int,
    val savedSentences: Int,
    val reviewLogs: Int,
    val vocabWords: Int,
)

/**
 * Export and restore the learning history.
 *
 * This app has no account and no server, which was the right call for something
 * personal - but it also left exactly zero ways back from a lost phone. Months
 * of review scheduling, a vocabulary profile built one dictation at a time, and
 * every sentence marked as missed are reconstructible only by doing the work
 * again. Every other missing feature can be added later; this one cannot be
 * added after the phone is gone.
 *
 * Two things are deliberately left out.
 *
 * The API key is not exported. It lives behind the device keystore, so the
 * encrypted form would be unreadable anywhere else, and writing the plaintext
 * into a file that is going to be mailed to oneself or dropped in cloud storage
 * would turn a backup into a credential leak. Re-entering it takes a moment.
 *
 * The audio is not exported. One episode is tens of megabytes and a library is
 * gigabytes, which would make the backup too large to actually keep anywhere.
 * Podcast audio can be fetched again; what cannot be fetched again is which
 * sentences this particular learner failed.
 */
class BackupRepository(private val context: Context) {

    class BackupException(message: String) : Exception(message)

    suspend fun export(target: Uri): BackupInfo = withContext(Dispatchers.IO) {
        val db = EnglusDatabase.get(context)
        val info = collect(db)

        // Room runs in WAL mode, so recent writes may still sit in the -wal file.
        // Copying englus.db without this can silently lose the last session -
        // the backup would quietly restore to yesterday.
        db.openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }

        val dbFile = context.getDatabasePath(EnglusDatabase.NAME)
        if (!dbFile.exists()) throw BackupException("데이터베이스를 찾을 수 없습니다.")

        val out = context.contentResolver.openOutputStream(target)
            ?: throw BackupException("파일을 만들 수 없습니다.")

        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest(info).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(DB_ENTRY))
            dbFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        info
    }

    /** Reads the manifest without touching anything, so a restore can be confirmed first. */
    suspend fun inspect(source: Uri): BackupInfo = withContext(Dispatchers.IO) {
        val text = readEntry(source, MANIFEST)
            ?: throw BackupException("englus 백업 파일이 아닙니다.")
        parse(text)
    }

    /**
     * Replaces the database with the one in the file.
     *
     * A backup from an older schema is fine: Room runs the same hand-written
     * migrations it runs on an app update. A backup from a newer schema is
     * refused rather than opened, because a downgrade has no defined path and
     * would damage the very thing being restored.
     */
    suspend fun restore(source: Uri): BackupInfo = withContext(Dispatchers.IO) {
        val info = inspect(source)
        if (info.schemaVersion > CURRENT_SCHEMA) {
            throw BackupException(
                "더 새로운 버전의 앱에서 만든 백업입니다 " +
                    "(백업 ${info.schemaVersion}, 이 앱 $CURRENT_SCHEMA). 앱을 먼저 업데이트하세요."
            )
        }

        // Staged first: a half-written database file is worse than no restore.
        val staged = File(context.cacheDir, "restore-${System.currentTimeMillis()}.db")
        try {
            if (!copyEntry(source, DB_ENTRY, staged)) {
                throw BackupException("백업 안에 데이터베이스가 없습니다.")
            }

            EnglusDatabase.closeForRestore()

            val dbFile = context.getDatabasePath(EnglusDatabase.NAME)
            dbFile.parentFile?.mkdirs()
            // The side files belong to the database being replaced. Leaving them
            // would let SQLite replay a journal against a file it never wrote.
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
            staged.inputStream().use { input ->
                dbFile.outputStream().use { input.copyTo(it) }
            }
            info
        } finally {
            staged.delete()
        }
    }

    /**
     * Audio is not in the backup, so restored episodes point at files that are
     * not there. Blanking the path makes the review card fall back to its text
     * front - the handling reading material already uses - instead of handing
     * the player a path that will fail.
     */
    suspend fun reconcileMissingAudio(): Int = withContext(Dispatchers.IO) {
        val dao = EnglusDatabase.get(context).episodes()
        var cleared = 0
        dao.all().forEach { episode ->
            if (episode.audioPath.isNotBlank() && !File(episode.audioPath).exists()) {
                dao.update(episode.copy(audioPath = ""))
                cleared++
            }
        }
        cleared
    }

    private fun collect(db: EnglusDatabase): BackupInfo {
        fun count(sql: String): Int = db.openHelper.readableDatabase.query(sql)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

        return BackupInfo(
            schemaVersion = CURRENT_SCHEMA,
            createdAt = System.currentTimeMillis(),
            episodes = count("SELECT COUNT(*) FROM episodes"),
            savedSentences = count("SELECT COUNT(*) FROM sentences WHERE saved = 1"),
            reviewLogs = count("SELECT COUNT(*) FROM review_logs"),
            vocabWords = count("SELECT COUNT(*) FROM vocab_words"),
        )
    }

    private fun manifest(info: BackupInfo): String = JSONObject().apply {
        put("app", APP)
        put("schemaVersion", info.schemaVersion)
        put("createdAt", info.createdAt)
        put("episodes", info.episodes)
        put("savedSentences", info.savedSentences)
        put("reviewLogs", info.reviewLogs)
        put("vocabWords", info.vocabWords)
    }.toString()

    internal fun parse(text: String): BackupInfo {
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: throw BackupException("백업 정보를 읽을 수 없습니다.")
        if (json.optString("app") != APP) throw BackupException("englus 백업 파일이 아닙니다.")
        return BackupInfo(
            schemaVersion = json.optInt("schemaVersion", 0),
            createdAt = json.optLong("createdAt"),
            episodes = json.optInt("episodes"),
            savedSentences = json.optInt("savedSentences"),
            reviewLogs = json.optInt("reviewLogs"),
            vocabWords = json.optInt("vocabWords"),
        )
    }

    private fun readEntry(source: Uri, name: String): String? {
        var found: String? = null
        forEachEntry(source) { zip, entry ->
            if (entry.name == name) {
                found = zip.readBytes().toString(Charsets.UTF_8)
                true
            } else false
        }
        return found
    }

    private fun copyEntry(source: Uri, name: String, dest: File): Boolean {
        var copied = false
        forEachEntry(source) { zip, entry ->
            if (entry.name == name) {
                dest.outputStream().use { zip.copyTo(it) }
                copied = true
                true
            } else false
        }
        return copied
    }

    private fun forEachEntry(source: Uri, block: (ZipInputStream, ZipEntry) -> Boolean) {
        val input = context.contentResolver.openInputStream(source)
            ?: throw BackupException("파일을 열 수 없습니다.")
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return
                if (block(zip, entry)) return
                zip.closeEntry()
            }
        }
    }

    companion object {
        const val CURRENT_SCHEMA = 12
        private const val APP = "englus"
        private const val MANIFEST = "englus-backup.json"
        private const val DB_ENTRY = "englus.db"

        fun suggestedFileName(now: Long): String {
            val date = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                .format(java.util.Date(now))
            return "englus-backup-$date.zip"
        }
    }
}
