package be.miro.onecast.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Podcast::class, Episode::class, QueueItem::class],
    version = 6,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun queueDao(): QueueDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** Adds nullable chapter columns; existing rows default to "no chapters". */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN chapters TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE episodes ADD COLUMN chaptersUrl TEXT")
            }
        }

        /** Adds nullable per-episode artwork; existing rows fall back to the podcast art. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN imageUrl TEXT")
            }
        }

        /** Adds the "Up Next" queue table (episode ids ordered by a position key). */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `queue` (" +
                        "`episodeId` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`episodeId`), " +
                        "FOREIGN KEY(`episodeId`) REFERENCES `episodes`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
            }
        }

        /** Adds the downloaded-file columns; existing rows start out as "not downloaded". */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN downloadPath TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN downloadSizeBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE episodes ADD COLUMN downloadedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Adds the video columns. Existing rows start out as audio-only; the video URL is
         * backfilled from the feed on the next refresh (see `PodcastRepository.insertNewEpisodes`).
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN videoUrl TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN downloadHasVideo INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "podcast.db",
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                ).build()
                    .also { instance = it }
            }
    }
}
