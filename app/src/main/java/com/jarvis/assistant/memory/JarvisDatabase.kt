package com.jarvis.assistant.memory

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 2,
    exportSchema = false,
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun actionLogDao(): ActionLogDao

    companion object {
        private const val NAME = "jarvis.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE memories ADD COLUMN expiresAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE skills ADD COLUMN description TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE action_log ADD COLUMN argumentsJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE action_log ADD COLUMN undoArgumentsJson TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "UPDATE skills SET stepsJson = REPLACE(REPLACE(stepsJson, 'read_battery_status', 'get_device_status'), 'read_time', 'get_current_time')",
                )
            }
        }

        @Volatile
        private var instance: JarvisDatabase? = null

        fun get(context: Context): JarvisDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    JarvisDatabase::class.java,
                    NAME,
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { instance = it }
            }
    }
}
