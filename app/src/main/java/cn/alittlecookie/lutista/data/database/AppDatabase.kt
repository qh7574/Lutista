package cn.alittlecookie.lutista.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cn.alittlecookie.lutista.data.database.dao.CameraObjectDao
import cn.alittlecookie.lutista.data.database.dao.TetherTransferDao
import cn.alittlecookie.lutista.data.database.entity.CameraObjectEntity
import cn.alittlecookie.lutista.data.database.entity.TetherTransferEntity
import cn.alittlecookie.lutista.data.database.dao.FilterDao
import cn.alittlecookie.lutista.data.database.dao.TaskDao
import cn.alittlecookie.lutista.data.database.dao.WatermarkDao
import cn.alittlecookie.lutista.data.database.entity.FilterEntity
import cn.alittlecookie.lutista.data.database.entity.TaskEntity
import cn.alittlecookie.lutista.data.database.entity.WatermarkEntity

@Database(
    entities = [
        FilterEntity::class,
        WatermarkEntity::class,
        TaskEntity::class,
        CameraObjectEntity::class,
        TetherTransferEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun filterDao(): FilterDao
    abstract fun watermarkDao(): WatermarkDao
    abstract fun taskDao(): TaskDao
    abstract fun cameraObjectDao(): CameraObjectDao
    abstract fun tetherTransferDao(): TetherTransferDao

    companion object {
        /** Additive only: existing recipes, watermarks and task history are preserved. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE processing_tasks ADD COLUMN originalSourcePath TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE processing_tasks ADD COLUMN submissionKey TEXT DEFAULT NULL")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `camera_objects` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `serial` TEXT NOT NULL, `sessionId` TEXT NOT NULL,
                        `handle` INTEGER NOT NULL, `storageId` INTEGER NOT NULL,
                        `format` INTEGER NOT NULL, `parent` INTEGER NOT NULL,
                        `size` INTEGER NOT NULL, `name` TEXT NOT NULL,
                        `dateTaken` TEXT NOT NULL, `isDir` INTEGER NOT NULL,
                        `seq` INTEGER NOT NULL, `durationMs` INTEGER,
                        `updatedAt` INTEGER NOT NULL)
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_camera_objects_handle` ON `camera_objects`(`serial`,`sessionId`,`storageId`,`handle`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_camera_objects_parent` ON `camera_objects`(`serial`,`sessionId`,`storageId`,`parent`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_camera_objects_sort` ON `camera_objects`(`sessionId` ASC,`isDir` ASC,`handle` DESC)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `tether_transfers` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `dedupeKey` TEXT NOT NULL, `kind` TEXT NOT NULL,
                        `handle` INTEGER NOT NULL, `name` TEXT NOT NULL,
                        `size` INTEGER NOT NULL, `targetPath` TEXT NOT NULL,
                        `state` TEXT NOT NULL, `bytesDone` INTEGER NOT NULL DEFAULT 0,
                        `taskId` INTEGER, `error` TEXT,
                        `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_tether_transfers_dedupe` ON `tether_transfers`(`dedupeKey`,`kind`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_tether_transfers_state` ON `tether_transfers`(`state`,`createdAt`)")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE filters ADD COLUMN isBuiltIn INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE filters ADD COLUMN filterDirPath TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE filters ADD COLUMN lutGlobalStrength REAL NOT NULL DEFAULT 1.0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE filters ADD COLUMN isEnabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE filters ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE watermarks ADD COLUMN isEnabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE watermarks ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
