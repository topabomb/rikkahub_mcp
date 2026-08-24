package net.weero.measix.pilot.data.db

import androidx.room.withTransaction

fun interface DatabaseTransactionRunner {
    suspend fun run(block: suspend () -> Unit)
}

class RoomDatabaseTransactionRunner(
    private val database: AppDatabase,
) : DatabaseTransactionRunner {
    override suspend fun run(block: suspend () -> Unit) {
        database.withTransaction { block() }
    }
}
