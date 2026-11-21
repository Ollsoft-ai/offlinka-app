package cz.offlinka.app.ui

import cz.offlinka.app.AppGraph
import cz.offlinka.app.drive.OpenBook
import cz.offlinka.ui.model.BookKind
import cz.offlinka.ui.model.MapRegion
import cz.offlinka.ui.model.TryNowItem
import cz.offlinka.ui.model.TryNowKind

/**
 * "Vyzkoušejte hned" on Home (first run): one article, the map, the first-aid cards, a game. Each link is
 * checked against the book actually on hand, so the card never offers a page that is not there.
 * Paths verified with offlinka-cli on drive edition 2026-11.3 (maxi and mini).
 */
object TryNow {
    /** Article per UI language: (path, title shown). */
    val ARTICLE = mapOf("sk" to ("Vysoké_Tatry" to "Vysoké Tatry"), "cs" to ("Sněžka" to "Sněžka"))
    const val FIRST_AID = "prvni-pomoc.html"
    const val GAME = "2048/index.html"

    /** Blocking (reads the books): call off the main thread. */
    fun items(graph: AppGraph): List<TryNowItem> {
        val books = graph.shelf.servedBooks().values
        val ui = graph.uiLanguage()
        val out = mutableListOf<TryNowItem>()
        val wiki = graph.shelf.primaryWikipedia()
        val article = ARTICLE[wiki?.ui?.language] ?: ARTICLE.getValue("cs")
        if (wiki != null && exists(wiki, article.first)) out += TryNowItem(TryNowKind.ARTICLE, article.second, wiki.id, article.first)
        val region = if (ui == "sk") MapRegion.SLOVAKIA else MapRegion.CZECHIA
        val map = books.firstOrNull { it.ui.kind == BookKind.MAPS && it.ui.region == region }
            ?: books.firstOrNull { it.ui.kind == BookKind.MAPS }
        map?.mainPath?.let { main -> if (exists(map, main)) out += TryNowItem(TryNowKind.MAP, "", map.id, main) }
        val guides = books.firstOrNull { it.ui.kind == BookKind.GUIDES }
        if (guides != null && exists(guides, FIRST_AID)) out += TryNowItem(TryNowKind.FIRST_AID, "", guides.id, FIRST_AID)
        val games = books.firstOrNull { it.ui.kind == BookKind.GAMES }
        if (games != null && exists(games, GAME)) out += TryNowItem(TryNowKind.GAME, "", games.id, GAME)
        return out
    }

    private fun exists(book: OpenBook, path: String): Boolean = runCatching { book.content.contentLength(path) >= 0 }.getOrDefault(false)
}
