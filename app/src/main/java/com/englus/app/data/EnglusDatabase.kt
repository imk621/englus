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

    /** One-shot read of every episode, for maintenance passes after a restore. */
    @Query("SELECT * FROM episodes")
    suspend fun all(): List<Episode>

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
interface ReviewDao {

    /**
     * Creates cards for saved sentences that do not have one yet.
     *
     * Done as a backfill rather than only at save time so that sentences saved
     * before the scheduler existed still enter the rotation, and so a card is
     * never silently missing because one write path forgot to create it.
     *
     * due = 0, not :now. A new card is due immediately, and stamping it with the
     * current time made it lose a race with the due-count query by milliseconds —
     * the card existed but nothing was ever due.
     */
    @Query(
        """
        INSERT INTO review_cards (sentenceId, stability, difficulty, state, due, lastReview, reps, lapses, createdAt)
        SELECT s.id, 0.0, 0.0, 'NEW', 0, 0, 0, 0, :now
        FROM sentences s
        WHERE s.saved = 1
          AND NOT EXISTS (SELECT 1 FROM review_cards c WHERE c.sentenceId = s.id)
        """
    )
    suspend fun backfill(now: Long)

    @Query(
        """
        SELECT c.*, s.text AS text, s.translation AS translation,
               s.startMs AS startMs, s.endMs AS endMs,
               e.title AS episodeTitle, e.audioPath AS audioPath,
               (SELECT d.diagnosis FROM dictation_attempts d
                  WHERE d.sentenceId = s.id ORDER BY d.attemptedAt DESC LIMIT 1) AS lastDiagnosis
        FROM review_cards c
        JOIN sentences s ON s.id = c.sentenceId
        JOIN episodes e ON e.id = s.episodeId
        WHERE s.saved = 1 AND c.due <= :now
        ORDER BY c.due
        """
    )
    suspend fun due(now: Long): List<DueCard>

    @Query(
        """
        SELECT COUNT(*) FROM review_cards c
        JOIN sentences s ON s.id = c.sentenceId
        WHERE s.saved = 1 AND c.due <= :now
        """
    )
    fun observeDueCount(now: Long): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM review_cards c
        JOIN sentences s ON s.id = c.sentenceId
        WHERE s.saved = 1
        """
    )
    fun observeTotalCards(): Flow<Int>

    @Update
    suspend fun update(card: ReviewCard)

    @Insert
    suspend fun log(entry: ReviewLog)

    @Query("SELECT COUNT(*) FROM review_logs WHERE reviewedAt >= :since")
    fun observeReviewsSince(since: Long): Flow<Int>

    /** Ordered per card so the first two reviews of each can be paired up. */
    @Query("SELECT * FROM review_logs ORDER BY cardId, reviewedAt")
    suspend fun allLogs(): List<ReviewLog>

    @Query("SELECT COUNT(*) FROM review_logs")
    fun observeLogCount(): Flow<Int>
}

@Dao
interface WritingDao {

    @Insert
    suspend fun insert(writing: Writing): Long

    @Insert
    suspend fun insertMistakes(mistakes: List<WritingMistake>)

    @Update
    suspend fun update(writing: Writing)

    @Query("SELECT * FROM writings ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Writing>>

    @Query("SELECT * FROM writings WHERE id = :id")
    fun observe(id: Long): Flow<Writing?>

    @Query("DELETE FROM writings WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM writing_mistakes WHERE writingId = :writingId")
    fun observeMistakes(writingId: Long): Flow<List<WritingMistake>>

    @Query("SELECT prompt FROM writings WHERE prompt != '' ORDER BY createdAt DESC LIMIT 10")
    suspend fun recentPrompts(): List<String>

    @Query("SELECT category, COUNT(*) AS count FROM writing_mistakes GROUP BY category ORDER BY count DESC")
    fun observeMistakeCounts(): Flow<List<MistakeCount>>

    @Query("SELECT COUNT(*) FROM writings")
    fun observeCount(): Flow<Int>
}

@Dao
interface SpeakingDao {

    @Insert
    suspend fun insert(attempt: SpeakingAttempt): Long

    @Query("SELECT AVG(accuracy) FROM speaking_attempts WHERE episodeId = :episodeId")
    fun observeAverage(episodeId: Long): Flow<Float?>

    @Query("SELECT COUNT(*) FROM speaking_attempts")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM speaking_attempts WHERE sentenceId = :sentenceId ORDER BY attemptedAt DESC LIMIT 1")
    suspend fun latestFor(sentenceId: Long): SpeakingAttempt?

    /**
     * Practice so far, per episode, in one query.
     *
     * The speaking home lists every episode; asking for an average per row would
     * be one query per item and would re-run on every recomposition.
     */
    @Query(
        """
        SELECT episodeId, COUNT(*) AS attempts, AVG(accuracy) AS average
        FROM speaking_attempts
        GROUP BY episodeId
        """
    )
    fun observeProgress(): Flow<List<SpeakingProgress>>

    @Query("SELECT AVG(accuracy) FROM speaking_attempts")
    fun observeOverallAverage(): Flow<Float?>
}

@Dao
interface ExamDao {

    @Insert
    suspend fun insertSession(session: ExamSession): Long

    @Insert
    suspend fun insertQuestions(questions: List<ExamQuestion>)

    @Update
    suspend fun updateSession(session: ExamSession)

    @Update
    suspend fun updateQuestion(question: ExamQuestion)

    @Query("SELECT * FROM exam_sessions WHERE id = :id")
    suspend fun session(id: Long): ExamSession?

    @Query("SELECT * FROM exam_questions WHERE sessionId = :sessionId ORDER BY position")
    fun observeQuestions(sessionId: Long): Flow<List<ExamQuestion>>

    @Query("SELECT * FROM exam_sessions WHERE finished = 1 ORDER BY createdAt DESC LIMIT 20")
    fun observeHistory(): Flow<List<ExamSession>>

    @Query(
        """
        SELECT q.kind AS kind, COUNT(*) AS total,
               SUM(CASE WHEN q.chosenIndex = q.correctIndex THEN 1 ELSE 0 END) AS correct
        FROM exam_questions q
        JOIN exam_sessions s ON s.id = q.sessionId
        WHERE s.finished = 1
        GROUP BY q.kind
        """
    )
    fun observeKindScores(): Flow<List<ExamKindScore>>
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
interface VocabDao {

    @Query("SELECT * FROM vocab_words WHERE lemma IN (:lemmas)")
    suspend fun get(lemmas: List<String>): List<VocabWord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(words: List<VocabWord>)

    @Query("SELECT lemma FROM vocab_words WHERE state = 'KNOWN'")
    suspend fun knownLemmas(): List<String>

    @Query("SELECT lemma FROM vocab_words WHERE state = 'KNOWN'")
    fun observeKnownLemmas(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM vocab_words")
    fun observeMetCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM vocab_words WHERE state = 'KNOWN'")
    fun observeKnownCount(): Flow<Int>

    /**
     * Known/unknown split per frequency band, computed in SQL.
     *
     * The CASE mirrors [com.englus.app.vocab.Band]; doing it here rather than in
     * Kotlin avoids reading every row into memory just to bucket it, and the
     * profile screen queries this on every change.
     */
    @Query(
        """
        SELECT
          CASE
            WHEN rank = 0 THEN 5
            WHEN rank <= 1000 THEN 0
            WHEN rank <= 2000 THEN 1
            WHEN rank <= 3000 THEN 2
            WHEN rank <= 5000 THEN 3
            ELSE 4
          END AS band,
          SUM(CASE WHEN state = 'KNOWN' THEN 1 ELSE 0 END) AS known,
          SUM(CASE WHEN state = 'UNKNOWN' THEN 1 ELSE 0 END) AS unknown,
          COUNT(*) AS met
        FROM vocab_words
        GROUP BY band
        ORDER BY band
        """
    )
    fun observeBandStats(): Flow<List<VocabBandStat>>

    /**
     * Unknown words, most frequent first.
     *
     * Rank order, not recency: a word in the top 1,000 will be met again within
     * the hour, and one at rank 9,000 may never come back. Studying in the order
     * the words were missed spends the same effort for a fraction of the return.
     * Off-list words (rank 0) sort last.
     */
    @Query(
        """
        SELECT * FROM vocab_words
        WHERE state = 'UNKNOWN'
        ORDER BY CASE WHEN rank = 0 THEN 999999 ELSE rank END
        LIMIT :limit
        """
    )
    fun observeUnknown(limit: Int = 100): Flow<List<VocabWord>>

    @Query("UPDATE vocab_words SET state = :state, manual = :manual WHERE lemma = :lemma")
    suspend fun setManual(lemma: String, state: VocabState, manual: Int)

    @Query("SELECT * FROM vocab_words WHERE lemma = :lemma")
    suspend fun word(lemma: String): VocabWord?

    /**
     * Candidate sentences containing a word, with the audio needed to replay them.
     *
     * LIKE on a stem rather than the lemma, because SQL cannot see inflections:
     * "study" does not occur inside "studies". The pattern deliberately
     * over-matches and the caller discards the false hits by lemmatising each
     * token, which is the only place that comparison can be made correctly.
     */
    @Query(
        """
        SELECT s.*, e.title AS episodeTitle, e.audioPath AS audioPath,
          (SELECT d.diagnosis FROM dictation_attempts d
             WHERE d.sentenceId = s.id ORDER BY d.attemptedAt DESC LIMIT 1) AS lastDiagnosis
        FROM sentences s
        JOIN episodes e ON e.id = s.episodeId
        WHERE s.text LIKE :pattern
        ORDER BY s.saved DESC, s.episodeId, s.position
        LIMIT :limit
        """
    )
    suspend fun sentencesContaining(pattern: String, limit: Int = 200): List<SavedSentence>
}

@Dao
interface ConversationDao {

    @Insert
    suspend fun insert(conversation: Conversation): Long

    @Update
    suspend fun update(conversation: Conversation)

    @Insert
    suspend fun insertTurns(turns: List<ConversationTurn>)

    @Update
    suspend fun updateTurn(turn: ConversationTurn)

    @Query("SELECT * FROM conversations ORDER BY startedAt DESC LIMIT 30")
    fun observeAll(): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observe(id: Long): Flow<Conversation?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: Long): Conversation?

    @Query("SELECT * FROM conversation_turns WHERE conversationId = :id ORDER BY position")
    fun observeTurns(id: Long): Flow<List<ConversationTurn>>

    @Query("SELECT * FROM conversation_turns WHERE conversationId = :id ORDER BY position")
    suspend fun turns(id: Long): List<ConversationTurn>

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    /** Recent topics, so the next suggestion does not repeat one. */
    @Query("SELECT topic FROM conversations ORDER BY startedAt DESC LIMIT 8")
    suspend fun recentTopics(): List<String>
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
        ReviewCard::class, ReviewLog::class,
        Writing::class, WritingMistake::class,
        SpeakingAttempt::class,
        ExamSession::class, ExamQuestion::class,
        VocabWord::class,
        Conversation::class, ConversationTurn::class,
    ],
    version = 12,
    exportSchema = true,
)
@TypeConverters(
    Converters::class, FeedConverters::class,
    DictationConverters::class, ReviewConverters::class,
    WritingConverters::class, ExamConverters::class,
    VocabConverters::class,
)
abstract class EnglusDatabase : RoomDatabase() {

    abstract fun episodes(): EpisodeDao
    abstract fun feeds(): FeedDao
    abstract fun dictation(): DictationDao
    abstract fun notes(): NoteDao
    abstract fun reviews(): ReviewDao
    abstract fun writings(): WritingDao
    abstract fun speaking(): SpeakingDao
    abstract fun exams(): ExamDao
    abstract fun vocab(): VocabDao
    abstract fun conversations(): ConversationDao

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

        /** Adds spaced-repetition scheduling. Additive. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `review_cards` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sentenceId` INTEGER NOT NULL, " +
                        "`stability` REAL NOT NULL, " +
                        "`difficulty` REAL NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`due` INTEGER NOT NULL, " +
                        "`lastReview` INTEGER NOT NULL, " +
                        "`reps` INTEGER NOT NULL, " +
                        "`lapses` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`sentenceId`) REFERENCES `sentences`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_review_cards_sentenceId` " +
                        "ON `review_cards` (`sentenceId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_review_cards_due` ON `review_cards` (`due`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `review_logs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`cardId` INTEGER NOT NULL, " +
                        "`rating` INTEGER NOT NULL, " +
                        "`stateBefore` TEXT NOT NULL, " +
                        "`elapsedDays` REAL NOT NULL, " +
                        "`scheduledDays` INTEGER NOT NULL, " +
                        "`reviewedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_review_logs_cardId` ON `review_logs` (`cardId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_review_logs_reviewedAt` " +
                        "ON `review_logs` (`reviewedAt`)"
                )
            }
        }

        /** Adds writing practice. Additive. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `writings` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`prompt` TEXT NOT NULL, " +
                        "`userText` TEXT NOT NULL, " +
                        "`correctedText` TEXT NOT NULL, " +
                        "`naturalText` TEXT NOT NULL, " +
                        "`overall` TEXT NOT NULL, " +
                        "`model` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_writings_createdAt` ON `writings` (`createdAt`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `writing_mistakes` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`writingId` INTEGER NOT NULL, " +
                        "`category` TEXT NOT NULL, " +
                        "`original` TEXT NOT NULL, " +
                        "`corrected` TEXT NOT NULL, " +
                        "`explanation` TEXT NOT NULL, " +
                        "FOREIGN KEY(`writingId`) REFERENCES `writings`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_writing_mistakes_writingId` " +
                        "ON `writing_mistakes` (`writingId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_writing_mistakes_category` " +
                        "ON `writing_mistakes` (`category`)"
                )
            }
        }

        /** Adds reading material alongside listening. Additive. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `episodes` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'LISTENING'"
                )
            }
        }

        /** Adds shadowing attempts. Additive. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `speaking_attempts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sentenceId` INTEGER NOT NULL, " +
                        "`episodeId` INTEGER NOT NULL, " +
                        "`accuracy` REAL NOT NULL, " +
                        "`recognizedText` TEXT NOT NULL, " +
                        "`targetText` TEXT NOT NULL, " +
                        "`spokenMs` INTEGER NOT NULL, " +
                        "`targetMs` INTEGER NOT NULL, " +
                        "`attemptedAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`sentenceId`) REFERENCES `sentences`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_speaking_attempts_sentenceId` " +
                        "ON `speaking_attempts` (`sentenceId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_speaking_attempts_attemptedAt` " +
                        "ON `speaking_attempts` (`attemptedAt`)"
                )
            }
        }

        /** Adds listening exams. Additive. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `exam_sessions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`episodeId` INTEGER NOT NULL, " +
                        "`episodeTitle` TEXT NOT NULL, " +
                        "`total` INTEGER NOT NULL, " +
                        "`correct` INTEGER NOT NULL, " +
                        "`finished` INTEGER NOT NULL, " +
                        "`elapsedMs` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_exam_sessions_episodeId` " +
                        "ON `exam_sessions` (`episodeId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_exam_sessions_createdAt` " +
                        "ON `exam_sessions` (`createdAt`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `exam_questions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sessionId` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "`prompt` TEXT NOT NULL, " +
                        "`options` TEXT NOT NULL, " +
                        "`correctIndex` INTEGER NOT NULL, " +
                        "`explanation` TEXT NOT NULL, " +
                        "`startMs` INTEGER NOT NULL, " +
                        "`endMs` INTEGER NOT NULL, " +
                        "`chosenIndex` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `exam_sessions`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_exam_questions_sessionId` " +
                        "ON `exam_questions` (`sessionId`)"
                )
            }
        }

        /**
         * Adds the vocabulary profile and per-episode difficulty. Additive.
         *
         * The three episode columns are nullable on purpose: episodes already on
         * the phone were never measured, and null says that, where a default of
         * zero would claim they are the hardest text in the library.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `vocab_words` (" +
                        "`lemma` TEXT NOT NULL, " +
                        "`rank` INTEGER NOT NULL, " +
                        "`seen` INTEGER NOT NULL, " +
                        "`correct` INTEGER NOT NULL, " +
                        "`missed` INTEGER NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`manual` INTEGER NOT NULL, " +
                        "`firstSeenAt` INTEGER NOT NULL, " +
                        "`lastSeenAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`lemma`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vocab_words_rank` ON `vocab_words` (`rank`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vocab_words_state` ON `vocab_words` (`state`)")
                db.execSQL("ALTER TABLE `episodes` ADD COLUMN `cefr` TEXT")
                db.execSQL("ALTER TABLE `episodes` ADD COLUMN `k2Coverage` REAL")
                db.execSQL("ALTER TABLE `episodes` ADD COLUMN `avgSentenceLen` REAL")
            }
        }

        /** Adds spoken conversations and their transcripts. Additive. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `conversations` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`topic` TEXT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, " +
                        "`durationSec` INTEGER NOT NULL, " +
                        "`model` TEXT NOT NULL, " +
                        "`feedback` TEXT NOT NULL, " +
                        "`mistakeCount` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_conversations_startedAt` " +
                        "ON `conversations` (`startedAt`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `conversation_turns` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`conversationId` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "`fromUser` INTEGER NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`correction` TEXT, " +
                        "`note` TEXT, " +
                        "FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_conversation_turns_conversationId` " +
                        "ON `conversation_turns` (`conversationId`)"
                )
            }
        }

        /** The file Room opens; backup copies this, not a snapshot of the objects. */
        const val NAME = "englus.db"

        /**
         * Closes the singleton so the file underneath can be replaced.
         *
         * Restoring into a live database is not possible: open flows would hold
         * handles to a file that is about to be swapped, and SQLite would see
         * its own storage change beneath it. The caller replaces the files and
         * then restarts the process.
         */
        fun closeForRestore() = synchronized(this) {
            instance?.close()
            instance = null
        }

        fun get(context: Context): EnglusDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                EnglusDatabase::class.java,
                NAME,
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12).build().also { instance = it }
        }
    }
}
