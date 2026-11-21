package cz.offlinka.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import cz.offlinka.app.fakes.MemoryLocales
import cz.offlinka.app.fakes.memoryDb
import cz.offlinka.ui.model.AppLanguage
import cz.offlinka.ui.model.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DataTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = memoryDb(context)

    @After fun close() = db.close()

    @Test fun `history keeps one row per article, newest first`() = runBlocking {
        val h = db.history()
        h.record(HistoryEntry("wiki", "Praha", "Praha", 1))
        h.record(HistoryEntry("wiki", "Brno", "Brno", 2))
        h.record(HistoryEntry("wiki", "Praha", "Praha", 3))
        assertThat(h.recent(10).first().map { it.path to it.visitedAt }).containsExactly("Praha" to 3L, "Brno" to 2L).inOrder()
    }

    @Test fun `history is trimmed to the newest rows`() = runBlocking {
        val h = db.history()
        repeat(20) { h.record(HistoryEntry("wiki", "A$it", "A$it", it.toLong())) }
        h.trim(5)
        assertThat(h.recent(100).first().map { it.path }).containsExactly("A19", "A18", "A17", "A16", "A15").inOrder()
    }

    @Test fun `saved articles toggle`() = runBlocking {
        val s = db.saved()
        assertThat(s.isSaved("wiki", "Praha").first()).isFalse()
        s.save(SavedArticle("wiki", "Praha", "Praha", 5))
        assertThat(s.isSaved("wiki", "Praha").first()).isTrue()
        assertThat(s.all().first().single().title).isEqualTo("Praha")
        s.remove("wiki", "Praha")
        assertThat(s.all().first()).isEmpty()
    }

    @Test fun `recent searches dedupe and trim`() = runBlocking {
        val r = db.searches()
        r.add(RecentSearch("praha", 1))
        r.add(RecentSearch("brno", 2))
        r.add(RecentSearch("praha", 3))
        assertThat(r.recent(10).first().map { it.query }).containsExactly("praha", "brno").inOrder()
        repeat(40) { r.add(RecentSearch("q$it", 10L + it)) }
        r.trim(30)
        assertThat(r.recent(100).first()).hasSize(30)
        r.clear()
        assertThat(r.recent(10).first()).isEmpty()
    }

    @Test fun `settings persist across instances`() {
        val prefs = context.getSharedPreferences("settings-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val locales = MemoryLocales()
        val a = Settings(prefs, locales)
        assertThat(a.values.value).isEqualTo(SettingsValues())
        a.setTheme(ThemeMode.DARK)
        a.setTextZoom(500)
        a.setSaveHistory(false)
        a.setLanguage(AppLanguage.SK)
        val b = Settings(prefs, locales)
        assertThat(b.values.value).isEqualTo(SettingsValues(ThemeMode.DARK, 200, false))
        assertThat(b.language).isEqualTo(AppLanguage.SK)
    }
}
