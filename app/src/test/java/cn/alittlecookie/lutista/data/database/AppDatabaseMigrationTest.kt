package cn.alittlecookie.lutista.data.database

import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.just
import io.mockk.Runs
import org.junit.Assert.*
import org.junit.Test

class AppDatabaseMigrationTest {
    @Test fun versionFiveIsStrictlyAdditiveAndPreservesHistoricalMigrationChain() {
        val statements = mutableListOf<String>()
        val db = mockk<SupportSQLiteDatabase>()
        every { db.execSQL(capture(statements)) } just Runs
        AppDatabase.MIGRATION_4_5.migrate(db)
        assertEquals(1, AppDatabase.MIGRATION_1_2.startVersion)
        assertEquals(2, AppDatabase.MIGRATION_2_3.startVersion)
        assertEquals(3, AppDatabase.MIGRATION_3_4.startVersion)
        assertEquals(4, AppDatabase.MIGRATION_4_5.startVersion)
        assertEquals(5, AppDatabase.MIGRATION_4_5.endVersion)
        assertTrue(statements.all { it.trimStart().startsWith("CREATE ") || it.startsWith("ALTER TABLE processing_tasks ADD COLUMN ") })
        assertTrue(statements.any { it.contains("ADD COLUMN originalSourcePath TEXT") })
        assertTrue(statements.any { it.contains("ADD COLUMN submissionKey TEXT") })
        assertTrue(statements.any { it.contains("CREATE TABLE IF NOT EXISTS `camera_objects`") })
        assertTrue(statements.any { it.contains("CREATE TABLE IF NOT EXISTS `tether_transfers`") })
        assertTrue(statements.any { it.contains("UNIQUE INDEX") && it.contains("`serial`,`sessionId`,`storageId`,`handle`") })
        assertTrue(statements.any { it.contains("UNIQUE INDEX") && it.contains("`dedupeKey`,`kind`") })
    }
}
