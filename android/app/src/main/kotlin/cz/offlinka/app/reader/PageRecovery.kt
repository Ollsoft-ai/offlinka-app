package cz.offlinka.app.reader

/**
 * Decides when the reader must reload its page because it was shown while the drive's books were not
 * open (after the process was restored, while the drive was unplugged, or during the first scan).
 * Pure logic, driven by the WebView client and the shelf.
 */
class PageRecovery {
    private var stale = false

    /** A main-frame page started loading; [booksOpen] = the drive's books were ready at that moment. */
    fun onPageStarted(booksOpen: Boolean) {
        if (!booksOpen) stale = true
    }

    /** The main frame got an HTTP error: 503 = a book was not available (closed, unplugged, still opening). */
    fun onMainFrameStatus(status: Int) {
        if (status == 503) stale = true
    }

    /** The drive's books are open (again): reload once if the page was shown without them. */
    fun shouldReloadWhenReady(): Boolean = stale.also { stale = false }
}

/** Text size of the reader: the user's setting times the system font scale (accessibility). */
object ReaderZoom {
    /** WebView text zoom (percent) for pages that are not MediaWiki. */
    fun textZoom(percent: Int, fontScale: Float): Int = (percent * fontScale.coerceIn(0.5f, 3f)).toInt().coerceIn(50, 400)

    /** Scale of the reader stylesheet (1 = 17 px body text) for MediaWiki pages, whose WebView zoom stays 100. */
    fun cssScale(percent: Int, fontScale: Float): Float = textZoom(percent, fontScale) / 100f
}

/** The reader's WebView history kept across activity recreation (lives in the app graph). */
class RetainedReaderState {
    @Volatile var bundle: android.os.Bundle? = null
}

/** A heading of the article for the contents sheet. */
data class Heading(val id: String, val text: String, val level: Int) {
    companion object {
        /** Parses the JSON string that evaluateJavascript returns (a JSON-encoded JSON array). */
        fun parse(evaluated: String?): List<Heading> = runCatching {
            if (evaluated == null || evaluated == "null") return emptyList()
            val inner = org.json.JSONTokener(evaluated).nextValue()
            val arr = if (inner is String) org.json.JSONArray(inner) else inner as org.json.JSONArray
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Heading(o.getString("id"), o.getString("text"), o.optInt("level", 2))
            }
        }.getOrDefault(emptyList())
    }
}
