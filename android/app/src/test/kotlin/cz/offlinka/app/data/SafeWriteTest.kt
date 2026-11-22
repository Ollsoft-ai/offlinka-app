package cz.offlinka.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import cz.offlinka.app.fakes.TestGraph
import cz.offlinka.app.ui.ArticleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A phone with a full disk keeps the reader working: saving, history and searches fail quietly. */
@RunWith(RobolectricTestRunner::class)
class SafeWriteTest {
    @Test fun `a full disk is logged, not a crash`() = runBlocking {
        assertThat(safeWrite<Unit>("history") { throw android.database.sqlite.SQLiteFullException("database or disk is full") }).isNull()
        assertThat(safeWrite<Unit>("saved") { throw android.database.sqlite.SQLiteDiskIOException("disk I/O error") }).isNull()
        assertThat(safeWrite("ok") { 42 }).isEqualTo(42)
    }

    @Test fun `visiting and saving with an unusable database does not throw`() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val t = TestGraph(context, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        t.graph.db.close() // every write now fails like on a broken or full disk
        val store = ArticleStore(t.graph)
        store.recordVisit("wikipedia_cs_all", "Praha", "Praha")
        store.toggleSaved("wikipedia_cs_all", "Praha", "Praha", saved = false)
    }
}
