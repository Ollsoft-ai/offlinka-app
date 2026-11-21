package cz.offlinka.app

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import cz.offlinka.app.drive.DocEntry
import cz.offlinka.app.drive.DriveAccess
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * A test-only [DriveAccess]: a folder in app storage plays the USB drive. Renaming the folder away is
 * "unplugging", renaming it back is "plugging in". The permission is a flag, like a persisted SAF grant.
 */
class FolderDrive(context: Context) : DriveAccess {
    val root = File(context.filesDir, "fixture-drive")
    private val away = File(context.filesDir, "fixture-drive-unplugged")

    /** What the system folder picker hands back for this drive. */
    val tree: Uri = Uri.parse("content://cz.offlinka.fixture/tree/FIXTURE%3A")

    @Volatile private var granted = false

    /** Opening these files waits for the latch (a book that is still opening on a slow drive). */
    val gates: MutableMap<String, java.util.concurrent.CountDownLatch> = java.util.concurrent.ConcurrentHashMap()

    /**
     * A drive like the real one: Knihovna with the guides book (the copy bundled in the APK) and a README.
     * [alsoAs]: more books, copies of the guides under other file names (another book id on the shelf).
     */
    fun prepare(context: Context, alsoAs: List<String> = emptyList()) {
        gates.clear()
        unplugAway()
        away.deleteRecursively()
        root.deleteRecursively()
        File(root, "Knihovna").mkdirs()
        context.assets.open(OfflinkaApp.BUNDLED_GUIDES).use { input ->
            File(root, "Knihovna/${OfflinkaApp.BUNDLED_GUIDES}").outputStream().use { input.copyTo(it) }
        }
        alsoAs.forEach { name -> File(root, "Knihovna/${OfflinkaApp.BUNDLED_GUIDES}").copyTo(File(root, "Knihovna/$name")) }
        File(root, "README.txt").writeText("Offlinka\nStav obsahu (edice listopad 2026)\n")
        granted = false
    }

    fun unplug() {
        check(root.renameTo(away)) { "cannot unplug" }
    }

    fun plugIn() {
        check(away.renameTo(root)) { "cannot plug in" }
    }

    private fun unplugAway() {
        if (away.isDirectory && !root.exists()) away.renameTo(root)
    }

    override fun grantedTrees(): List<Uri> = if (granted) listOf(tree) else emptyList()

    override fun hasPermission(tree: Uri): Boolean = granted && tree == this.tree

    override fun persist(tree: Uri) {
        if (tree != this.tree) throw SecurityException("not the fixture drive")
        granted = true
    }

    override fun release(tree: Uri) {
        if (tree == this.tree) granted = false
    }

    override fun label(tree: Uri): String? = if (root.isDirectory) "OFFLINKA" else null

    override fun list(tree: Uri, dirId: String?): List<DocEntry> {
        val dir = if (dirId == null) root else File(root, dirId)
        val children = dir.listFiles() ?: throw IOException("drive not available")
        return children.map { f ->
            DocEntry(f.relativeTo(root).path, f.name, f.isDirectory, if (f.isFile) f.length() else 0)
        }
    }

    override fun openFd(tree: Uri, docId: String): Int {
        val f = File(root, docId)
        if (!f.isFile) throw IOException("no such file $docId")
        gates[f.name]?.await(60, java.util.concurrent.TimeUnit.SECONDS)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).detachFd()
    }

    override fun openStream(tree: Uri, docId: String): InputStream {
        val f = File(root, docId)
        if (!f.isFile) throw IOException("no such file $docId")
        return FileInputStream(f)
    }
}
