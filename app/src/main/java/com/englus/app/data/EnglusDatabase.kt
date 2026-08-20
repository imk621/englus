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

    @Query("UPDATE sentences SET saved = :saved WHERE id = :id")
    suspend fun setSaved(id: Long, saved: Boolean)

    @Query("SELECT * FROM sentences WHERE saved = 1 ORDER BY episodeId, position")
    fun observeSaved(): Flow<List<Sentence>>
}

@Database(entities = [Episode::class, Sentence::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class EnglusDatabase : RoomDatabase() {

    abstract fun episodes(): EpisodeDao

    companion object {
        @Volatile private var instance: EnglusDatabase? = null

        fun get(context: Context): EnglusDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                EnglusDatabase::class.java,
                "englus.db",
            ).build().also { instance = it }
        }
    }
}
