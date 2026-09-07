package net.weero.measix.pilot.data.db.transcript

import androidx.sqlite.db.SupportSQLiteDatabase

/** SQLite TEXT is sliced in code points, keeping every CursorWindow below the platform limit. */
internal fun readTranscriptPayload(db: SupportSQLiteDatabase, nodeId: String): String = buildString {
    var start = 1
    while (true) {
        val slice = db.query("SELECT substr(messages, ?, ?) FROM message_node WHERE id = ?",
            arrayOf<Any>(start, 256 * 1024, nodeId)).use { cursor ->
            check(cursor.moveToFirst()) { "Missing transcript node $nodeId" }
            cursor.getString(0)
        }
        if (slice.isEmpty()) break
        append(slice)
        start += slice.codePointCount(0, slice.length)
    }
}
