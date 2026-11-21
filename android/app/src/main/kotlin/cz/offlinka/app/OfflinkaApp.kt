package cz.offlinka.app

import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.storage.StorageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import cz.offlinka.app.content.ContentSource
import cz.offlinka.app.content.CoreContentSource
import cz.offlinka.app.data.AppDatabase
import cz.offlinka.app.data.Settings
import cz.offlinka.app.drive.DriveAccess
import cz.offlinka.app.drive.SafDriveAccess
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

open class OfflinkaApp : Application() {
    lateinit var graph: AppGraph
        private set

    /** Tests override these with fakes. */
    protected open fun createSource(): ContentSource = CoreContentSource()

    protected open fun createDrives(): DriveAccess = SafDriveAccess(this)

    protected open fun createDatabase(): AppDatabase = AppDatabase.create(this)

    protected open val watchStorageBroadcasts: Boolean = true

    private val foreground = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        val settings = Settings(this)
        settings.applyTheme()
        val source = createSource()
        runCatching { source.setCacheBudget(cacheBudget()) }.onFailure { Log.w(TAG, "cache budget not set", it) }
        graph = AppGraph(
            context = this,
            source = source,
            drives = createDrives(),
            db = createDatabase(),
            settings = settings,
            foreground = foreground,
            builtinGuidesPath = ::bundledGuides,
        )
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { foreground.value = true }
            override fun onStop(owner: LifecycleOwner) { foreground.value = false }
        })
        graph.heavyIndexAllowed = heavyIndexAllowed()
        graph.shelf.start()
        updateDriveVisible()
        if (watchStorageBroadcasts) watchStorage()
    }

    /** Low memory: drop the core's cluster caches and loaded indexes (they reload on demand). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            runCatching { graph.source.trimMemory() }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { graph.source.trimMemory() }
    }

    /** Cluster cache shared by all books (native memory): the core's 48 MB, 32 MB on low-RAM phones. */
    private fun cacheBudget(): Long {
        val low = getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
        return (if (low) 32L else 48L) * 1024 * 1024
    }

    /** English top1m needs ~460 MB while its index is built: offer it only on phones with 3 GB or more. */
    private fun heavyIndexAllowed(): Boolean = runCatching {
        val am = getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        !am.isLowRamDevice && (info.totalMem == 0L || info.totalMem >= 3L * 1024 * 1024 * 1024)
    }.getOrDefault(false)

    /**
     * The guides book bundled in the APK (assets), copied once to app storage because the core opens
     * files by path. "První pomoc" then works without the drive.
     */
    private fun bundledGuides(): String? = runCatching {
        val out = File(noBackupFilesDir, BUNDLED_GUIDES)
        // Size of the asset (stored uncompressed); unknown on odd asset managers, then copy only when missing.
        val size = runCatching { assets.openFd(BUNDLED_GUIDES).use { it.length } }.getOrDefault(-1L)
        if (!out.isFile || (size >= 0 && out.length() != size)) {
            val tmp = File(out.path + ".part")
            assets.open(BUNDLED_GUIDES).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(out)
        }
        out.path
    }.onFailure { Log.w(TAG, "bundled guides missing", it) }.getOrNull()

    /** Is a removable volume (the USB drive) mounted? */
    fun updateDriveVisible() {
        graph.driveVisible.value = runCatching {
            val sm = getSystemService(StorageManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                sm.storageVolumes.any { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }
            } else {
                null
            }
        }.getOrNull()
    }

    /** USB drive mounted / unplugged: let the shelf re-check at once instead of on the next failed read. */
    private fun watchStorage() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                updateDriveVisible()
                graph.shelf.onStorageChanged()
            }
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    companion object {
        private const val TAG = "OfflinkaApp"
        const val BUNDLED_GUIDES = "offlinka_cs_prirucky.zim"
    }
}

val Context.graph: AppGraph get() = (applicationContext as OfflinkaApp).graph
