package cz.offlinka.app.drive

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.util.Log
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/** What a SAF tree URI says about the drive it points into. */
object DriveTrees {
    /** "1234-ABCD" of a tree on that volume ("1234-ABCD:Knihovna"); the whole id when it has no colon. */
    fun volumeId(tree: Uri): String = treeDocId(tree).substringBefore(':')

    /** The folder inside the volume that was picked: "" for the root, "Knihovna" for that folder. */
    fun pickedPath(tree: Uri): String = treeDocId(tree).let { if (':' in it) it.substringAfter(':') else "" }

    private fun treeDocId(tree: Uri): String =
        runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: tree.lastPathSegment.orEmpty()
}

/** One entry of a drive folder. */
data class DocEntry(val id: String, val name: String, val isDir: Boolean, val size: Long)

/**
 * Read-only access to drives the user granted us through the Storage Access Framework.
 * Nothing here ever writes, renames or deletes on the drive.
 */
interface DriveAccess {
    /** Trees we hold a persisted read permission for, in the order they were granted. */
    fun grantedTrees(): List<Uri>

    fun hasPermission(tree: Uri): Boolean

    /** Keeps read access across reboots. Throws [SecurityException] if the grant is not persistable. */
    fun persist(tree: Uri)

    fun release(tree: Uri)

    /** Display name of the picked folder (the drive label when the root was picked). Null when unreachable. */
    fun label(tree: Uri): String?

    /** The volume's own label ("OFFLINKA") when a folder inside it was picked; null when unknown. */
    fun volumeLabel(tree: Uri): String? = null

    /** Children of [dirId] (or of the picked folder when null). Throws [IOException] when the drive is gone. */
    fun list(tree: Uri, dirId: String?): List<DocEntry>

    /** A read-only file descriptor of a file, detached: the caller owns it. Throws [IOException] when the drive is gone. */
    fun openFd(tree: Uri, docId: String): Int

    /** A small file's content (README). Throws [IOException] when the drive is gone. */
    fun openStream(tree: Uri, docId: String): InputStream
}

class SafDriveAccess(private val context: Context) : DriveAccess {
    private val resolver: ContentResolver get() = context.contentResolver

    override fun grantedTrees(): List<Uri> = resolver.persistedUriPermissions
        .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
        .sortedBy { it.persistedTime }
        .map { it.uri }

    override fun hasPermission(tree: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }

    override fun persist(tree: Uri) {
        resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override fun release(tree: Uri) {
        runCatching { resolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    override fun label(tree: Uri): String? = try {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        Log.i(TAG, "label($tree) failed: $e")
        null
    }

    override fun volumeLabel(tree: Uri): String? = try {
        val volume = DriveTrees.volumeId(tree)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.getSystemService(StorageManager::class.java)?.storageVolumes
                ?.firstOrNull { it.uuid.equals(volume, ignoreCase = true) }
                ?.getDescription(context)
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    override fun list(tree: Uri, dirId: String?): List<DocEntry> {
        val parent = dirId ?: DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val cursor = try {
            resolver.query(children, projection, null, null, null)
        } catch (e: SecurityException) {
            throw IOException("no access to $tree", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("drive not available: $tree", e)
        } catch (e: IllegalStateException) {
            throw IOException("drive not available: $tree", e)
        } ?: throw IOException("drive not available: $tree")
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val mime = c.getString(2) ?: ""
                    add(
                        DocEntry(
                            id = c.getString(0),
                            name = c.getString(1) ?: "",
                            isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                            size = if (c.isNull(3)) 0L else c.getLong(3),
                        ),
                    )
                }
            }
        }
    }

    override fun openFd(tree: Uri, docId: String): Int = open(tree, docId).detachFd()

    override fun openStream(tree: Uri, docId: String): InputStream = ParcelFileDescriptor.AutoCloseInputStream(open(tree, docId))

    private fun open(tree: Uri, docId: String): ParcelFileDescriptor {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        return try {
            resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString())
        } catch (e: SecurityException) {
            throw IOException("no access to $uri", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("drive not available: $uri", e)
        } catch (e: IllegalStateException) {
            throw IOException("drive not available: $uri", e)
        }
    }

    companion object {
        private const val TAG = "OfflinkaDrive"

        /**
         * The system folder picker, opened on the first removable volume (the USB drive) when Android can
         * tell us which one it is (API 29+).
         */
        fun pickerIntent(context: Context): Intent {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val sm = context.getSystemService(StorageManager::class.java)
                val removable = sm?.storageVolumes?.firstOrNull { it.isRemovable && it.state == "mounted" }
                if (removable != null) {
                    return removable.createOpenDocumentTreeIntent().addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                    )
                }
            }
            return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }
    }
}
