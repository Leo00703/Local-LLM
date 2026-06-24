package com.druk.lmplayground.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        SystemPromptEntity::class,
        PromptUsage::class,
        FolderEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao

    abstract fun systemPromptDao(): SystemPromptDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN contextSize INTEGER NOT NULL DEFAULT 4096")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN temperature REAL NOT NULL DEFAULT 0.8")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN topP REAL NOT NULL DEFAULT 0.95")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN repetitionPenalty REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN topK INTEGER NOT NULL DEFAULT 40")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN minP REAL NOT NULL DEFAULT 0.05")
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN seed INTEGER NOT NULL DEFAULT -1")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN thinkingBudget INTEGER NOT NULL DEFAULT 1024")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN responseDurationSeconds REAL NOT NULL DEFAULT 0")
            }
        }

        /**
         * System-prompt feature in one shot. The intermediate schemas 5/6
         * never shipped to real users, so we collapse the three dev-only
         * migration steps into a single 4 → 5 migration.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Denormalized system-prompt text stored alongside each chat
                // session so reopening a conversation replays with the same
                // prompt it was started with.
                db.execSQL(
                    "ALTER TABLE chat_sessions ADD COLUMN systemPrompt TEXT NOT NULL DEFAULT ''"
                )

                // Library of reusable system prompts.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS system_prompts (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "text TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL DEFAULT 0" +
                        ")"
                )

                // Per-model MRU markers.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS prompt_usage (" +
                        "promptId TEXT NOT NULL, " +
                        "modelFilename TEXT NOT NULL, " +
                        "lastUsedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY (promptId, modelFilename), " +
                        "FOREIGN KEY (promptId) REFERENCES system_prompts(id) ON DELETE CASCADE" +
                        ")"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_prompt_usage_modelFilename " +
                        "ON prompt_usage (modelFilename)"
                )
            }
        }

        /**
         * Generic per-conversation metadata blob (JSON). Backs
         * [ConversationMetadata]; first use is the web_search link map.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE chat_sessions ADD COLUMN metadata TEXT NOT NULL DEFAULT '{}'"
                )
            }
        }

        /**
         * Project folders. Adds a `folders` table and a nullable `folderId` on
         * chat_sessions (NULL = unfiled). Existing chats stay unfiled, so no
         * conversation is lost. Folder deletion cascades to its chats in code
         * (ChatDao.deleteFolderAndChats), so no DB-level foreign key is added
         * here — keeping the migration a plain ADD COLUMN / CREATE TABLE.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS folders (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "name TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "position INTEGER NOT NULL DEFAULT 0" +
                        ")"
                )
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN folderId TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_chat_sessions_folderId " +
                        "ON chat_sessions (folderId)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lmplayground.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7
                    )
                    .build().also { INSTANCE = it }
            }
        }
    }
}
