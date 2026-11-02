package com.jarvis.assistant.memory

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface ActionLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ActionLogEntity)

    @Query("SELECT * FROM action_log ORDER BY timestamp DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<ActionLogEntity>>

    @Query("SELECT * FROM action_log ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ActionLogEntity>

    @Query("SELECT COUNT(*) FROM action_log")
    suspend fun count(): Int

    @Query("DELETE FROM action_log")
    suspend fun clear()
}

@Database(
    entities = [
        MemoryEntity::class,
        MemoryFts::class,
        ProfileFactEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        SummaryEntity::class,
        SkillEntity::class,
        ActionLogEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun actionLogDao(): ActionLogDao

    companion object {
        private const val NAME = "jarvis.db"

        @Volatile
        private var instance: JarvisDatabase? = null

        fun get(context: Context): JarvisDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    JarvisDatabase::class.java,
                    NAME,
                )
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { instance = it }
            }
    }
}
