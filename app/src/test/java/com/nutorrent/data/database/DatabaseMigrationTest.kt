package com.nutorrent.data.database

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class DatabaseMigrationTest {

    @Test
    fun `test migration 1 to 2 executes alter table queries`() {
        val executedQueries = mutableListOf<String>()

        val proxy = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executedQueries.add(args[0] as String)
            }
            null
        } as SupportSQLiteDatabase

        assertEquals(1, NuTorrentDatabase.MIGRATION_1_2.startVersion)
        assertEquals(2, NuTorrentDatabase.MIGRATION_1_2.endVersion)

        NuTorrentDatabase.MIGRATION_1_2.migrate(proxy)

        assertEquals(4, executedQueries.size)
        assertTrue(executedQueries.any { it.contains("isMagnet") })
        assertTrue(executedQueries.any { it.contains("uploadedBytes") })
        assertTrue(executedQueries.any { it.contains("priority") })
        assertTrue(executedQueries.any { it.contains("selectedFiles") })
    }

    @Test
    fun `test migration 2 to 3 executes alter table queries`() {
        val executedQueries = mutableListOf<String>()

        val proxy = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executedQueries.add(args[0] as String)
            }
            null
        } as SupportSQLiteDatabase

        assertEquals(2, NuTorrentDatabase.MIGRATION_2_3.startVersion)
        assertEquals(3, NuTorrentDatabase.MIGRATION_2_3.endVersion)

        NuTorrentDatabase.MIGRATION_2_3.migrate(proxy)

        assertEquals(1, executedQueries.size)
        assertTrue(executedQueries.any { it.contains("sequentialDownload") })
    }

    @Test
    fun `test migration 3 to 4 executes alter table queries`() {
        val executedQueries = mutableListOf<String>()

        val proxy = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executedQueries.add(args[0] as String)
            }
            null
        } as SupportSQLiteDatabase

        assertEquals(3, NuTorrentDatabase.MIGRATION_3_4.startVersion)
        assertEquals(4, NuTorrentDatabase.MIGRATION_3_4.endVersion)

        NuTorrentDatabase.MIGRATION_3_4.migrate(proxy)

        assertEquals(1, executedQueries.size)
        assertTrue(executedQueries.any { it.contains("category") })
    }

    @Test
    fun `test migration 4 to 5 executes alter table queries`() {
        val executedQueries = mutableListOf<String>()

        val proxy = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executedQueries.add(args[0] as String)
            }
            null
        } as SupportSQLiteDatabase

        assertEquals(4, NuTorrentDatabase.MIGRATION_4_5.startVersion)
        assertEquals(5, NuTorrentDatabase.MIGRATION_4_5.endVersion)

        NuTorrentDatabase.MIGRATION_4_5.migrate(proxy)

        assertEquals(1, executedQueries.size)
        assertTrue(executedQueries.any { it.contains("lastError") })
    }

    @Test
    fun `test migration 5 to 6 executes alter table queries`() {
        val executedQueries = mutableListOf<String>()

        val proxy = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executedQueries.add(args[0] as String)
            }
            null
        } as SupportSQLiteDatabase

        assertEquals(5, NuTorrentDatabase.MIGRATION_5_6.startVersion)
        assertEquals(6, NuTorrentDatabase.MIGRATION_5_6.endVersion)

        NuTorrentDatabase.MIGRATION_5_6.migrate(proxy)

        assertEquals(3, executedQueries.size)
        assertTrue(executedQueries.any { it.contains("completedPieces") })
        assertTrue(executedQueries.any { it.contains("verifiedBytes") })
        assertTrue(executedQueries.any { it.contains("isCompleted") })
    }
}
