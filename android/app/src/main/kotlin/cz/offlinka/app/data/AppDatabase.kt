package cz.offlinka.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** An article the user saved ("Uložené"). Keyed by book id + ZIM path, so it survives a new edition. */
@Entity(tableName = "saved", primaryKeys = ["bookId", "path"])
data class SavedArticle(
    val bookId: String,
    val path: String,
    val title: String,
    val savedAt: Long,
)

/** Last visit of an article; one row per article, newest visit wins. */
@Entity(tableName = "history", primaryKeys = ["bookId", "path"], indices = [Index("visitedAt")])
data class HistoryEntry(
    val bookId: String,
    val path: String,
    val title: String,
    val visitedAt: Long,
)

@Entity(tableName = "recent_search")
data class RecentSearch(
    @PrimaryKey val query: String,
    val usedAt: Long,
)

@Dao
interface SavedDao {
    @Query("SELECT * FROM saved ORDER BY savedAt DESC")
    fun all(): Flow<List<SavedArticle>>

    @Query("SELECT EXISTS(SELECT 1 FROM saved WHERE bookId = :bookId AND path = :path)")
    fun isSaved(bookId: String, path: String): Flow<Boolean>

    @Upsert
    suspend fun save(item: SavedArticle)

    @Query("DELETE FROM saved WHERE bookId = :bookId AND path = :path")
    suspend fun remove(bookId: String, path: String)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<HistoryEntry>>

    @Upsert
    suspend fun record(entry: HistoryEntry)

    @Query("DELETE FROM history WHERE bookId = :bookId AND path = :path")
    suspend fun remove(bookId: String, path: String)

    @Query("DELETE FROM history")
    suspend fun clear()

    /** Keeps the newest [keep] rows. */
    @Query("DELETE FROM history WHERE visitedAt < (SELECT MIN(visitedAt) FROM (SELECT visitedAt FROM history ORDER BY visitedAt DESC LIMIT :keep))")
    suspend fun trim(keep: Int)
}

@Dao
interface RecentSearchDao {
    @Query("SELECT * FROM recent_search ORDER BY usedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<RecentSearch>>

    @Upsert
    suspend fun add(item: RecentSearch)

    @Query("DELETE FROM recent_search WHERE `query` = :query")
    suspend fun remove(query: String)

    @Query("DELETE FROM recent_search")
    suspend fun clear()

    @Query("DELETE FROM recent_search WHERE usedAt < (SELECT MIN(usedAt) FROM (SELECT usedAt FROM recent_search ORDER BY usedAt DESC LIMIT :keep))")
    suspend fun trim(keep: Int)
}

@Database(entities = [SavedArticle::class, HistoryEntry::class, RecentSearch::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun saved(): SavedDao
    abstract fun history(): HistoryDao
    abstract fun searches(): RecentSearchDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "offlinka.db").build()

        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }
}
