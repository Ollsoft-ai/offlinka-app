package cz.offlinka.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import cz.offlinka.app.fakes.TestGraph
import cz.offlinka.app.reader.PageInfo
import cz.offlinka.app.reader.ReaderUrls
import cz.offlinka.ui.model.BookKind
import cz.offlinka.ui.model.BookState
import cz.offlinka.ui.model.BookUi
import cz.offlinka.ui.model.QuickLink
import cz.offlinka.ui.model.SearchGroup
import cz.offlinka.ui.model.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ViewModelsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var t: TestGraph

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        t = TestGraph(context, scope)
        t.connect()
    }

    @After fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun <T> await(flow: kotlinx.coroutines.flow.Flow<T>, predicate: (T) -> Boolean): T =
        runBlocking { withTimeout(5_000) { flow.first(predicate) } }

    @Test fun `search groups results by book and ignores diacritics`() {
        val vm = SearchViewModel(t.graph, null, Dispatchers.Unconfined)
        vm.setQuery("cesky")
        val s = await(vm.state) { !it.searching && it.groups.isNotEmpty() }
        assertThat(s.groups.single().book.id).isEqualTo("wikipedia_cs_all")
        assertThat(s.groups.single().hits.map { it.title }).containsExactly("Český Krumlov")
    }

    @Test fun `a redirect hit opens its target`() {
        val vm = SearchViewModel(t.graph, null, Dispatchers.Unconfined)
        vm.setQuery("Prag")
        val s = await(vm.state) { !it.searching && it.groups.isNotEmpty() }
        val hit = s.groups.single().hits.single()
        assertThat(hit.title).isEqualTo("Prag")
        assertThat(hit.path).isEqualTo("Praha")
    }

    @Test fun `search in one book`() {
        val vm = SearchViewModel(t.graph, "offlinka_cs_prirucky", Dispatchers.Unconfined)
        vm.setQuery("prv")
        val s = await(vm.state) { !it.searching && it.groups.isNotEmpty() }
        assertThat(s.scope?.id).isEqualTo("offlinka_cs_prirucky")
        assertThat(s.groups.single().hits.map { it.path }).containsExactly("prvni-pomoc.html")
        vm.setScope(null)
        vm.setQuery("praha")
        val all = await(vm.state) { !it.searching && it.scope == null && it.groups.isNotEmpty() }
        assertThat(all.groups.map { it.book.id }).containsExactly("wikipedia_cs_all")
    }

    @Test fun `a multi-word query with no title match retries its longest word`() {
        val vm = SearchViewModel(t.graph, null, Dispatchers.Unconfined)
        vm.setQuery("praha hlavní")
        val s = await(vm.state) { !it.searching && it.groups.isNotEmpty() }
        assertThat(s.retriedWord).isEqualTo("hlavní")
        assertThat(s.groups.flatMap { it.hits }.map { it.title }).contains("Hlavní strana")
        vm.setQuery("praha")
        val direct = await(vm.state) { !it.searching && it.query == "praha" && it.groups.isNotEmpty() }
        assertThat(direct.retriedWord).isNull()
    }

    @Test fun `the UI-language Wikipedia leads the search groups, the rest keep the core's order`() {
        fun g(id: String, kind: BookKind, lang: String) =
            SearchGroup(BookUi(id = id, kind = kind, language = lang, zimTitle = id), emptyList())
        val core = listOf(
            g("offlinka_cs_prirucky", BookKind.GUIDES, "cs"),
            g("wikipedia_cs_all", BookKind.WIKIPEDIA, "cs"),
            g("wikipedia_sk_all", BookKind.WIKIPEDIA, "sk"),
            g("wiktionary_sk_all", BookKind.WIKTIONARY, "sk"),
        )
        assertThat(SearchViewModel.uiWikipediaFirst(core, "sk").map { it.book.id })
            .containsExactly("wikipedia_sk_all", "offlinka_cs_prirucky", "wikipedia_cs_all", "wiktionary_sk_all").inOrder()
        assertThat(SearchViewModel.uiWikipediaFirst(core, "cs").map { it.book.id })
            .containsExactly("wikipedia_cs_all", "offlinka_cs_prirucky", "wikipedia_sk_all", "wiktionary_sk_all").inOrder()
    }

    @Test fun `no results and empty query`() {
        val vm = SearchViewModel(t.graph, null, Dispatchers.Unconfined)
        vm.setQuery("xyzzy")
        assertThat(await(vm.state) { !it.searching && it.query == "xyzzy" }.noResults).isTrue()
        vm.setQuery("  ")
        assertThat(await(vm.state) { it.query == "  " }.showRecent).isTrue()
    }

    @Test fun `recent searches are remembered once used`() {
        val vm = SearchViewModel(t.graph, null, Dispatchers.Unconfined)
        vm.remember("Sněžka ")
        vm.remember("Praha")
        val s = await(vm.state) { it.recent.size == 2 }
        assertThat(s.recent).containsExactly("Praha", "Sněžka").inOrder()
        vm.removeRecent("Praha")
        assertThat(await(vm.state) { it.recent.size == 1 }.recent).containsExactly("Sněžka")
    }

    @Test fun `reader records each finished visit and toggles saved`() {
        val page = MutableStateFlow(PageInfo())
        val vm = ReaderViewModel(t.graph, page)
        page.value = PageInfo(url = ReaderUrls.article("wikipedia_cs_all", "Praha"), title = "Praha", visit = 1)
        val a = await(vm.article) { it.path == "Praha" }
        assertThat(a.book?.id).isEqualTo("wikipedia_cs_all")
        assertThat(a.isApp).isFalse()
        val history = await(t.graph.db.history().recent(10)) { it.isNotEmpty() }
        assertThat(history.single().title).isEqualTo("Praha")

        vm.toggleSaved()
        assertThat(await(vm.article) { it.saved }.saved).isTrue()
        assertThat(await(t.graph.db.saved().all()) { it.isNotEmpty() }.single().path).isEqualTo("Praha")
        vm.toggleSaved()
        assertThat(await(vm.article) { !it.saved }.saved).isFalse()
    }

    @Test fun `maps are apps - no history, no saving`() {
        val page = MutableStateFlow(PageInfo())
        val vm = ReaderViewModel(t.graph, page)
        page.value = PageInfo(url = ReaderUrls.article("maps_en_czech-republic", "index.html"), title = "Map", visit = 1)
        assertThat(await(vm.article) { it.bookId == "maps_en_czech-republic" }.isApp).isTrue()
        runBlocking { kotlinx.coroutines.delay(200) }
        assertThat(runBlocking { t.graph.db.history().recent(10).first() }).isEmpty()
    }

    @Test fun `history is not recorded when the user turned it off`() {
        t.graph.settings.setSaveHistory(false)
        val page = MutableStateFlow(PageInfo())
        ReaderViewModel(t.graph, page)
        page.value = PageInfo(url = ReaderUrls.article("wikipedia_cs_all", "Brno"), title = "Brno", visit = 1)
        runBlocking { kotlinx.coroutines.delay(200) }
        assertThat(runBlocking { t.graph.db.history().recent(10).first() }).isEmpty()
    }

    @Test fun `a 404 page is neither recorded nor savable`() {
        val page = MutableStateFlow(PageInfo())
        val vm = ReaderViewModel(t.graph, page)
        page.value = PageInfo(url = ReaderUrls.article("wikipedia_cs_all", "Nic"), title = "Článek tu není", notFound = true, visit = 1)
        val a = await(vm.article) { it.bookId != null }
        assertThat(a.path).isNull()
        runBlocking { kotlinx.coroutines.delay(200) }
        assertThat(runBlocking { t.graph.db.history().recent(10).first() }).isEmpty()
    }

    @Test fun `text size from the reader goes to settings`() {
        val vm = ReaderViewModel(t.graph, MutableStateFlow(PageInfo()))
        vm.setTextZoom(150)
        assertThat(t.graph.settings.values.value.textZoom).isEqualTo(150)
    }

    @Test fun `home shows the books, quick links resolve into the guides`() {
        val vm = HomeViewModel(t.graph)
        val s = await(vm.state) { it.books.isNotEmpty() }
        assertThat(s.books.map { it.id }).containsExactly("wikipedia_cs_all", "maps_en_czech-republic", "offlinka_cs_prirucky").inOrder()
        assertThat(s.hasGuides).isTrue()
        assertThat(s.hasRandom).isTrue()
        runBlocking {
            assertThat(vm.quickLink(QuickLink.FIRST_AID)).isEqualTo(ReaderUrls.article("offlinka_cs_prirucky", "prvni-pomoc.html"))
            assertThat(vm.quickLink(QuickLink.PREPARE)).isEqualTo(ReaderUrls.article("offlinka_cs_prirucky", "vypadek-proudu.html"))
            assertThat(vm.quickLink(QuickLink.RANDOM)).isEqualTo(ReaderUrls.article("wikipedia_cs_all", "Brno"))
        }
    }

    @Test fun `a book that does not open is a failed tile on home and opens on retry`() {
        t.source.failing += "maps_en_czech-republic_2026-06.zim"
        runBlocking { t.graph.shelf.reload() }
        val vm = HomeViewModel(t.graph)
        val failed = await(vm.state) { s -> s.books.any { it.state == BookState.FAILED } }
        assertThat(failed.books.single { it.state == BookState.FAILED }.id).isEqualTo("maps_en_czech-republic")
        t.source.failing.clear()
        vm.retry(failed.books.single { it.id == "maps_en_czech-republic" })
        val ok = await(vm.state) { s -> s.books.all { it.state == BookState.OPEN } }
        assertThat(ok.books.map { it.id }).containsExactly("wikipedia_cs_all", "maps_en_czech-republic", "offlinka_cs_prirucky").inOrder()
    }

    @Test fun `library lists the open books`() {
        val vm = LibraryViewModel(t.graph)
        val s = await(vm.state) { it.books.isNotEmpty() }
        assertThat(s.books).hasSize(3)
        assertThat(s.drive?.name).isEqualTo("OFFLINKA")
    }

    @Test fun `settings expose language, theme and drives`() {
        val vm = SettingsViewModel(t.graph)
        vm.setLanguage(cz.offlinka.ui.model.AppLanguage.EN)
        vm.setTheme(ThemeMode.LIGHT)
        val s = await(vm.state) { it.language == cz.offlinka.ui.model.AppLanguage.EN && it.theme == ThemeMode.LIGHT }
        assertThat(s.drives.single().connected).isTrue()
        assertThat(t.locales.current).isEqualTo(cz.offlinka.ui.model.AppLanguage.EN)
    }
}
