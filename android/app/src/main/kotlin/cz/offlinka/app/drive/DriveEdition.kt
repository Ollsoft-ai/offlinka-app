package cz.offlinka.app.drive

/** The drive's content edition, read from the "(edice listopad 2026)" line of its README.txt. */
object DriveEdition {
    private val months = listOf(
        "led", "únor", "břez", "dub", "květ", "červen", "července", "srp", "září", "říj", "listopad", "prosin",
    )
    private val pattern = Regex("""edic[eií]\s+(\p{L}+)\s+(\d{4})""", RegexOption.IGNORE_CASE)

    /** `2026-11` or null. */
    fun parse(readme: String): String? {
        val m = pattern.find(readme) ?: return null
        val word = m.groupValues[1].lowercase()
        val year = m.groupValues[2]
        val month = when {
            // "červenec/července" (7) before the prefix "červen" (6)
            word.startsWith("července") || word.startsWith("červenec") -> 7
            else -> months.indexOfFirst { word.startsWith(it) }.takeIf { it >= 0 }?.plus(1)
        } ?: return null
        return "%s-%02d".format(year, month)
    }
}
