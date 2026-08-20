package com.englus.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface EpisodeDao {

    @Query("SELECT * FROM episodes ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<Episode>>

    @Query("SELECT * FROM episodes WHERE id = :id")
    fun observe(id: Long): Flow<Episode?>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun get(id: Long): Episode?

    @Insert
    suspend fun insert(episode: Episode): Long

    @Update
    suspend fun update(episode: Episode)

    @Query("DELETE FROM episodes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE episodes SET state = :state, progress = :progress, error = :error WHERE id = :id")
    suspend fun setState(id: Long, state: TranscriptState, progress: Float, error: String?)

    @Query("SELECT * FROM sentences WHERE episodeId = :episodeId ORDER BY position")
    fun observeSentences(episodeId: Long): Flow<List<Sentence>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSentences(sentences: List<Sentence>)

    @Query("DELETE FROM sentences WHERE episodeId = :episodeId")
    suspend fun clearSentences(episodeId: Long)

    @Query("UPDATE sentences SET translation = :translation WHERE id = :id")
    suspend fun setTranslation(id: Long, translation: String)

    @Query("SELECT * FROM sentences WHERE episodeId = :episodeId AND translation IS NULL ORDER BY position")
    suspend fun untranslated(episodeId: Long): List<Sentence>

    @Query("UPDATE episodes SET translationState = :state WHERE id = :id")
    suspend fun setTranslationState(id: Long, state: TranslationState)

    @Query("UPDATE sentences SET saved = :saved WHERE id = :id")
    suspend fun setSaved(id: Long, saved: Boolean)

    @Query("SELECT * FROM sentences WHERE saved = 1 ORDER BY episodeId, position")
    fun observeSaved(): Flow<List<Sentence>>

    @Query("SELECT * FROM sentences WHERE id = :id")
    suspend fun getSentence(id: Long): Sentence?

    /** Neighbouring line, used as context for a cloud explanation. */
    @Query("SELECT text FROM sentences WHERE episodeId = :episodeId AND position = :position")
    suspend fun sentenceTextAt(episodeId: Long, position: Int): String?
}

@Dao
interface DictationDao {

    @Insert
    suspend fun insert(attempt: DictationAttempt): Long

    /**
     * Saved sentences joined to their audio, plus the most recent diagnosis.
     *
     * LEFT JOIN via subquery rather than a join on the attempts table: a sentence
     * bookmarked by hand has no attempt at all, and it still belongs in the list.
     */
    @Query(
        """
        SELECT s.*, e.title AS episodeTitle, e.audioPath AS audioPath,
          (SELECT d.diagnosis FROM dictation_attempts d
             WHERE d.sentenceId = s.id ORDER BY d.attemptedAt DESC LIMIT 1) AS lastDiagnosis
        FROM sentences s
        JOIN episodes e ON e.id = s.episodeId
        WHERE s.saved = 1
        ORDER BY s.episodeId, s.position
        """
    )
    fun observeSaved(): Flow<List<SavedSentence>>

    @Query(
        """
        SELECT diagnosis, COUNT(*) AS count FROM dictation_attempts
        WHERE diagnosis != 'CORRECT'
        GROUP BY diagnosis
        """
    )
    fun observeWeakness(): Flow<List<DiagnosisCount>>

    @Query("SELECT COUNT(*) FROM dictation_attempts")
    fun observeTotalAttempts(): Flow<Int>

    @Query("SELECT COUNT(*) FROM dictation_attempts WHERE diagnosis = 'CORRECT'")
    fun observeCorrectAttempts(): Flow<Int>
}

@Dao
interface NoteDao {

    @Query("SELECT * FROM sentence_notes WHERE sentenceId = :sentenceId")
    fun observe(sentenceId: Long): Flow<SentenceNote?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: SentenceNote)

    @Query("DELETE FROM sentence_notes WHERE sentenceId = :sentenceId")
    suspend fun delete(sentenceId: Long)
}

@Dao
interface FeedDao {

    @Query("SELECT * FROM podcasts ORDER BY addedAt DESC")
    fun observePodcasts(): Flow<List<Podcast>>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    fun observePodcast(id: Long): Flow<Podcast?>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    suspend fun getPodcast(id: Long): Podcast?

    @Query("SELECT * FROM podcasts WHERE feedUrl = :feedUrl")
    suspend fun findByUrl(feedUrl: String): Podcast?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPodcast(podcast: Podcast): Long

    @Update
    suspend fun updatePodcast(podcast: Podcast)

    @Query("DELETE FROM podcasts WHERE id = :id")
    suspend fun deletePodcast(id: Long)

    @Query("SELECT * FROM feed_items WHERE podcastId = :podcastId ORDER BY publishedAt DESC")
    fun observeItems(podcastId: Long): Flow<List<FeedItem>>

    @Query("SELECT * FROM feed_items WHERE id = :id")
    suspend fun getItem(id: Long): FeedItem?

    /**
     * IGNORE, not REPLACE: a refresh re-reports every item in the feed, and
     * replacing would wipe episodeId and downloadState for anything already
     * downloaded. The unique (podcastId, guid) index is what makes this work.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItems(items: List<FeedItem>)

    @Update
    suspend fun updateItem(item: FeedItem)

    @Query("UPDATE feed_items SET downloadState = :state, downloadProgress = :progress, error = :error WHERE id = :id")
    suspend fun setDownloadState(id: Long, state: DownloadState, progress: Float, error: String?)
}

@Database(
    entities = [
        Episode::class, Sentence::class,
        Podcast::class, FeedItem::class,
        DictationAttempt::class, SentenceNote::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class, FeedConverters::class, DictationConverters::class)
abstract class EnglusDatabase : RoomDatabase() {

    abstract fun episodes(): EpisodeDao
    abstract fun feeds(): FeedDao
    abstract fun dictation(): DictationDao
    abstract fun notes(): NoteDao

    companion object {
        @Volatile private var instance: EnglusDatabase? = null

        /**
         * Adds podcast subscriptions. Purely additive — episodes and sentences
         * already transcribed on the phone must survive, which rules out
         * destructive migration.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `podcasts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`feedUrl` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`imageUrl` TEXT, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "`lastCheckedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_podcasts_feedUrl` " +
                        "ON `podcasts` (`feedUrl`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `feed_items` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`podcastId` INTEGER NOT NULL, " +
                        "`guid` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`audioUrl` TEXT NOT NULL, " +
                        "`publishedAt` INTEGER NOT NULL, " +
                        "`durationSec` INTEGER NOT NULL, " +
                        "`sizeBytes` INTEGER NOT NULL, " +
                        "`episodeId` INTEGER, " +
                        "`downloadState` TEXT NOT NULL, " +
                        "`downloadProgress` REAL NOT NULL, " +
                        "`error` TEXT, " +
                        "FOREIGN KEY(`podcastId`) REFERENCES `podcasts`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_feed_items_podcastId` " +
                        "ON `feed_items` (`podcastId`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_feed_items_podcastId_guid` " +
                        "ON `feed_items` (`podcastId`, `guid`)"
                )
            }
        }

        /** Adds dictation attempts. Additive, same reasoning as [MIGRATION_1_2]. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dictation_attempts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sentenceId` INTEGER NOT NULL, " +
                        "`episodeId` INTEGER NOT NULL, " +
                        "`userText` TEXT NOT NULL, " +
                        "`correctText` TEXT NOT NULL, " +
                        "`accuracy` REAL NOT NULL, " +
                        "`diagnosis` TEXT NOT NULL, " +
                        "`usedSlowReplay` INTEGER NOT NULL, " +
                        "`attemptedAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`sentenceId`) REFERENCES `sentences`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dictation_attempts_sentenceId` " +
                        "ON `dictation_attempts` (`sentenceId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dictation_attempts_attemptedAt` " +
                        "ON `dictation_attempts` (`attemptedAt`)"
                )
            }
        }

        /** Adds Korean translations. Additive; existing transcripts are untouched. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sentences` ADD COLUMN `translation` TEXT")
                db.execSQL(
                    "ALTER TABLE `episodes` ADD COLUMN `translationState` TEXT NOT NULL DEFAULT 'NONE'"
                )
            }
        }

        /** Adds cached cloud explanations. Additive. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sentence_notes` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sentenceId` INTEGER NOT NULL, " +
                        "`translation` TEXT NOT NULL, " +
                        "`structure` TEXT NOT NULL, " +
                        "`expressions` TEXT NOT NULL, " +
                        "`grammar` TEXT NOT NULL, " +
                        "`sound` TEXT NOT NULL, " +
                        "`model` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`sentenceId`) REFERENCES `sentences`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_sentence_notes_sentenceId` " +
                        "ON `sentence_notes` (`sentenceId`)"
                )
            }
        }

        fun get(context: Context): EnglusDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                EnglusDatabase::class.java,
                "englus.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { instance = it }
        }
    }
}
