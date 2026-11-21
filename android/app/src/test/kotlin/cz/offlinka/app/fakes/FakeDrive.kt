package cz.offlinka.app.fakes

import android.net.Uri
import cz.offlinka.app.drive.DocEntry
import cz.offlinka.app.drive.DriveAccess
import java.io.IOException
import java.io.InputStream

/**
 * In-memory drives for the SAF layer. A drive is a tree of folders and files; [plugged] pulls it in and out;
 * [granted] mimics persisted URI permissions.
 */
class FakeDriveAccess : DriveAccess {
    class Drive(val label: String, val files: Map<String, Long>, val contents: Map<String, ByteArray> = emptyMap()) {
        var plugged = true
    }

    val drives = linkedMapOf<Uri, Drive>()
    val granted = mutableListOf<Uri>()
    val released = mutableListOf<Uri>()
    var refusePersist = false
    private var nextFd = 100
    val openedFds = mutableListOf<String>()

    fun add(uri: String, label: String, vararg files: Pair<String, Long>): Uri {
        val u = Uri.parse(uri)
        drives[u] = Drive(label, files.toMap())
        return u
    }

    /** A drive with real small files (README) next to sized placeholders. */
    fun addWithContent(uri: String, label: String, contents: Map<String, ByteArray>, vararg files: Pair<String, Long>): Uri {
        val u = Uri.parse(uri)
        drives[u] = Drive(label, files.toMap() + contents.mapValues { it.value.size.toLong() }, contents)
        return u
    }

    override fun grantedTrees(): List<Uri> = granted.toList()

    override fun hasPermission(tree: Uri) = tree in granted

    override fun persist(tree: Uri) {
        if (refusePersist) throw SecurityException("not persistable")
        if (tree !in granted) granted += tree
    }

    override fun release(tree: Uri) {
        granted -= tree
        released += tree
    }

    private fun drive(tree: Uri): Drive {
        val d = drives[tree] ?: throw IOException("no such drive")
        if (!d.plugged) throw IOException("unplugged")
        return d
    }

    override fun label(tree: Uri): String? = drives[tree]?.takeIf { it.plugged }?.label

    override fun list(tree: Uri, dirId: String?): List<DocEntry> {
        val d = drive(tree)
        val prefix = if (dirId == null) "" else "$dirId/"
        val children = d.files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        val dirs = children.filter { '/' in it }.map { it.substringBefore('/') }.distinct()
        val files = children.filter { '/' !in it }
        return dirs.map { DocEntry(prefix + it, it, true, 0) } + files.map { DocEntry(prefix + it, it, false, d.files.getValue(prefix + it)) }
    }

    override fun openFd(tree: Uri, docId: String): Int {
        val d = drive(tree)
        if (docId !in d.files) throw IOException("no such file $docId")
        openedFds += docId
        return nextFd++
    }

    override fun openStream(tree: Uri, docId: String): InputStream {
        val d = drive(tree)
        return (d.contents[docId] ?: throw IOException("no content for $docId")).inputStream()
    }
}
