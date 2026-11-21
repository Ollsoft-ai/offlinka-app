package cz.offlinka.app.fakes

import android.content.Context
import androidx.room.Room
import cz.offlinka.app.AppGraph
import cz.offlinka.app.data.AppDatabase
import cz.offlinka.app.data.LocaleStore
import cz.offlinka.app.data.Settings
import cz.offlinka.app.drive.ShelfState
import cz.offlinka.ui.model.AppLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor

class MemoryLocales : LocaleStore {
    /** Czech by default, so book order does not depend on the JVM's locale. */
    var current = AppLanguage.CS
    override fun get() = current
    override fun set(language: AppLanguage) { current = language }
}

fun memoryDb(context: Context): AppDatabase {
    val direct = Executor { it.run() }
    return Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor(direct)
        .setTransactionExecutor(direct)
        .build()
}

/** An app graph on fakes, with one drive "OFFLINKA" holding Czech Wikipedia, the guides and the Czech map. */
class TestGraph(context: Context, scope: CoroutineScope) {
    val drives = FakeDriveAccess()
    val source = FakeContentSource(
        mapOf(
            "wikipedia_cs_all_maxi-offlinka_2026-09.zim" to { fakeWiki() },
            "offlinka_cs_prirucky.zim" to { fakeGuides() },
            "maps_en_czech-republic_2026-06.zim" to { fakeMaps() },
        ),
    )
    val locales = MemoryLocales()
    val graph = AppGraph(
        context = context,
        source = source,
        drives = drives,
        db = memoryDb(context),
        settings = Settings(context.getSharedPreferences("test-settings", Context.MODE_PRIVATE).also { it.edit().clear().commit() }, locales),
        scope = scope,
        indexDir = null,
    )
    val tree = drives.add(
        "content://com.android.externalstorage.documents/tree/1234-ABCD%3A", "OFFLINKA",
        "Knihovna/wikipedia_cs_all_maxi-offlinka_2026-09.zim" to 9_958_167_476L,
        "Knihovna/offlinka_cs_prirucky.zim" to 393_612L,
        "Knihovna/maps_en_czech-republic_2026-06.zim" to 1_065_265_251L,
    )

    /** Grants the drive and waits until every book is open. */
    fun connect(): ShelfState.Ready {
        drives.granted += tree
        graph.shelf.reloadAsync()
        return runBlocking {
            withTimeout(10_000) { graph.shelf.state.first { it is ShelfState.Ready && !it.opening } as ShelfState.Ready }
        }
    }
}
