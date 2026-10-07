package com.nutorrent.app.data.database

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
}
