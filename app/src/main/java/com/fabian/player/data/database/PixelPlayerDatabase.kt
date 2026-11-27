package com.fabian.player.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AlbumArtThemeEntity::class,
        SearchHistoryEntity::class,
        SongEntity::class,
        SongSearchFtsEntity::class,
        AlbumEntity::class,
        ArtistEntity::class,
        TransitionRuleEntity::class,
        SongArtistCrossRef::class,
        SongEngagementEntity::class,
        FavoritesEntity::class,
        LyricsEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        NavidromeSongEntity::class,
        NavidromePlaylistEntity::class,
        JellyfinSongEntity::class,
        JellyfinPlaylistEntity::class,
        ListenBrainzPendingListenEntity::class,
        AudioBookmarkEntity::class,
        OfflineTrackEntity::class
    ],
    version = 6,
    exportSchema = true
)
abstract class PixelPlayerDatabase : RoomDatabase() {
    abstract fun albumArtThemeDao(): AlbumArtThemeDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun musicDao(): MusicDao
    abstract fun transitionDao(): TransitionDao
    abstract fun engagementDao(): EngagementDao
    abstract fun favoritesDao(): FavoritesDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun localPlaylistDao(): LocalPlaylistDao
    abstract fun navidromeDao(): NavidromeDao
    abstract fun jellyfinDao(): JellyfinDao
    abstract fun listenBrainzDao(): ListenBrainzDao
    abstract fun audioBookmarkDao(): AudioBookmarkDao
    abstract fun offlineTrackDao(): OfflineTrackDao

    companion object {
        private val FAVORITE_SYNC_TRIGGER_NAMES = listOf(
            "trg_favorites_insert_sync_song",
            "trg_favorites_update_sync_song",
            "trg_favorites_delete_sync_song"
        )

        private val SONGS_FTS_TRIGGER_NAMES = listOf(
            "trg_songs_fts_insert",
            "trg_songs_fts_update",
            "trg_songs_fts_delete"
        )

        private fun hasAllRuntimeTriggers(
            db: SupportSQLiteDatabase,
            triggerNames: List<String>
        ): Boolean {
            val placeholders = triggerNames.joinToString(",") { "'$it'" }
            db.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name IN ($placeholders)"
            ).use { cursor ->
                return cursor.moveToFirst() && cursor.getInt(0) == triggerNames.size
            }
        }

        private fun hasTable(db: SupportSQLiteDatabase, tableName: String): Boolean {
            db.query(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(tableName)
            ).use { cursor ->
                return cursor.moveToFirst()
            }
        }

        fun installFavoriteSyncTriggers(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TRIGGER IF EXISTS trg_favorites_insert_sync_song")
            db.execSQL("DROP TRIGGER IF EXISTS trg_favorites_update_sync_song")
            db.execSQL("DROP TRIGGER IF EXISTS trg_favorites_delete_sync_song")

            db.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS trg_favorites_insert_sync_song
                    AFTER INSERT ON favorites
                    BEGIN
                        UPDATE songs SET is_favorite = NEW.isFavorite WHERE id = NEW.songId;
                    END
                """.trimIndent()
            )

            db.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS trg_favorites_update_sync_song
                    AFTER UPDATE ON favorites
                    BEGIN
                        UPDATE songs SET is_favorite = NEW.isFavorite WHERE id = NEW.songId;
                    END
                """.trimIndent()
            )

            db.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS trg_favorites_delete_sync_song
                    AFTER DELETE ON favorites
                    BEGIN
                        UPDATE songs SET is_favorite = 0 WHERE id = OLD.songId;
                    END
                """.trimIndent()
            )
        }

        private fun createSongsSearchVirtualTable(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                    CREATE VIRTUAL TABLE IF NOT EXISTS songs_fts
                    USING fts4(
                        title,
                        artist_name,
                        tokenize=unicode61
                    )
                """.trimIndent()
            )
        }

        fun installSongsSearchSyncTriggers(db: SupportSQLiteDatabase) {
            createSongsSearchVirtualTable(db)

            db.execSQL("DROP TRIGGER IF EXISTS trg_songs_fts_insert")
            db.execSQL("DROP TRIGGER IF EXISTS trg_songs_fts_update")
            db.execSQL("DROP TRIGGER IF EXISTS trg_songs_fts_delete")

            db.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS trg_songs_fts_insert
                    AFTER INSERT ON songs
                    BEGIN
                        INSERT INTO songs_fts(rowid, title, artist_name)
                        VALUES (NEW.id, NEW.title, NEW.artist_name);
                    END
                """.trimIndent()
            )

            db.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS trg_songs_fts_update
                    AFTER UPDATE ON songs
                    BEGIN
                        DELETE FROM songs_fts WHERE rowid = OLD.id;
                        INSERT INTO songs_fts(rowid, title, artist_name)
                        VALUES (NEW.id, NEW.title, NEW.artist_name);
                    END
                """.trimIndent()
            )

            db.execSQL(
                """
                    CREATE TRIGGER IF NOT EXISTS trg_songs_fts_delete
                    AFTER DELETE ON songs
                    BEGIN
                        DELETE FROM songs_fts WHERE rowid = OLD.id;
                    END
                """.trimIndent()
            )
        }

        fun createRuntimeArtifactsCallback(): RoomDatabase.Callback {
            return object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    installFavoriteSyncTriggers(db)
                    installSongsSearchSyncTriggers(db)
                }

                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    if (!hasAllRuntimeTriggers(db, FAVORITE_SYNC_TRIGGER_NAMES)) {
                        installFavoriteSyncTriggers(db)
                    }
                    if (!hasTable(db, "songs_fts") ||
                        !hasAllRuntimeTriggers(db, SONGS_FTS_TRIGGER_NAMES)
                    ) {
                        installSongsSearchSyncTriggers(db)
                    }
                }
            }
        }
    }
}
