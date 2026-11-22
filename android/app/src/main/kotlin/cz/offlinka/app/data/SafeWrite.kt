package cz.offlinka.app.data

import android.database.SQLException
import android.util.Log

/**
 * Saved articles, history and recent searches are conveniences: a phone with a full disk
 * (SQLiteFullException, SQLiteDiskIOException) must keep the reader working, so a failed write is
 * logged and dropped instead of crashing the app.
 */
suspend fun <T> safeWrite(what: String, block: suspend () -> T): T? = try {
    block()
} catch (e: SQLException) {
    Log.w("OfflinkaData", "$what not stored: ${e.javaClass.simpleName}")
    null
} catch (e: IllegalStateException) {
    // Room wraps some storage errors (database not writable) this way.
    Log.w("OfflinkaData", "$what not stored: ${e.javaClass.simpleName}")
    null
}
