package com.siddarthkay.syncup

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.util.Log
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "SAFProvider"

// Detects "the SAF document doesn't exist on disk", regardless of how the
// SAF provider chose to surface it. ExternalStorageProvider in particular
// wraps the underlying FileNotFoundException in an IllegalArgumentException
// whose getMessage() embeds the inner exception's toString() — there is
// NO cause chain. So we have to look at the message text too.
private fun isNotFoundException(e: Throwable): Boolean {
    if (e is java.io.FileNotFoundException) return true
    var cur: Throwable? = e
    while (cur != null) {
        if (cur is java.io.FileNotFoundException) return true
        val msg = cur.message?.lowercase() ?: ""
        if (msg.contains("filenotfoundexception") ||
            msg.contains("missing file") ||
            msg.contains("no such file")) return true
        cur = cur.cause
        if (cur === e) break
    }
    return false
}

/**
 * Implements gobridge.SAFBridge so the Go-side SAF filesystem can delegate
 * file operations through Android's ContentResolver / DocumentsContract APIs.
 *
 * Data I/O is fd-based: [openFd] returns a raw file descriptor that Go wraps
 * in os.NewFile(), so read/write throughput bypasses JNI entirely.
 */
class SAFProvider(private val ctx: Context) : gobridge.SAFBridge {

    // Cache: "treeURI\nrelativePath" -> documentId
    //
    // Sized to hold the working set of a multi-tens-of-thousands-of-files scan.
    // ListChildrenJSON pre-warms one entry per child during DirNames; once
    // hashing workers pick those files up they call OpenFd which re-resolves
    // the path. If the cache is smaller than the active scan's path count,
    // hash-time openFd misses and re-walks via per-component findChildDocId,
    // turning each file's Binder cost from 1 query to ~depth+1.
    // ~200 bytes per entry × 65k ≈ 13 MB heap — fine on Android.
    private val docIdCache = LruCache<String, String>(65_536)

    // Stat-data cache: "treeURI\nrelativePath" -> CachedStat
    //
    // Solves the dominant cost during scans: syncthing's scanner does many
    // more Stat calls than file Opens (caseFilesystem.checkCase + ancestor
    // walking + scanSubdirsDeletedAndIgnored iteration over the DB), and
    // most of those stats are for paths we just returned in a recent
    // listChildrenJSON. Without this, every stat is a Binder roundtrip; with
    // it, hot paths resolve from memory.
    //
    // TTL is short (30s) so external file changes surface within a scan
    // cycle. Mutating ops invalidate explicitly.
    //
    // Negative entries (exists=false) are also cached, so syncthing's
    // recurring ".stignore" / ".stversions" probes stop hitting Binder.
    private data class CachedStat(
        val name: String,
        val size: Long,
        val modTimeMs: Long,
        val isDir: Boolean,
        val exists: Boolean,
        val expiresAtNanos: Long,
    )
    private val statDataCache = ConcurrentHashMap<String, CachedStat>()
    private val statCacheTtlNanos = TimeUnit.SECONDS.toNanos(30)

    private fun statCacheKey(treeURI: String, relPath: String) = "$treeURI\n$relPath"

    private fun putStatCache(treeURI: String, relPath: String, name: String, size: Long, modTimeMs: Long, isDir: Boolean) {
        statDataCache[statCacheKey(treeURI, relPath)] = CachedStat(
            name = name, size = size, modTimeMs = modTimeMs, isDir = isDir,
            exists = true, expiresAtNanos = System.nanoTime() + statCacheTtlNanos
        )
    }

    private fun putStatCacheNegative(treeURI: String, relPath: String) {
        statDataCache[statCacheKey(treeURI, relPath)] = CachedStat(
            name = "", size = 0L, modTimeMs = 0L, isDir = false,
            exists = false, expiresAtNanos = System.nanoTime() + statCacheTtlNanos
        )
    }

    private fun getStatCache(treeURI: String, relPath: String): CachedStat? {
        val key = statCacheKey(treeURI, relPath)
        val entry = statDataCache[key] ?: return null
        if (entry.expiresAtNanos < System.nanoTime()) {
            statDataCache.remove(key)
            return null
        }
        return entry
    }

    private fun invalidateStatCache(treeURI: String, relPath: String) {
        val key = statCacheKey(treeURI, relPath)
        statDataCache.remove(key)
        // Also invalidate descendants — a directory's mutation can affect
        // any cached stat under it.
        val prefix = "$key/"
        val it = statDataCache.keys.iterator()
        while (it.hasNext()) {
            if (it.next().startsWith(prefix)) it.remove()
        }
    }

    // Watch tracking
    private val nextWatchId = AtomicLong(1)
    private val watches = ConcurrentHashMap<Long, WatchEntry>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private class WatchEntry(
        val treeURI: String,
        val observer: ContentObserver,
        val events: LinkedBlockingQueue<JSONObject> = LinkedBlockingQueue(4096),
    )

    // ---- StatJSON ----

    override fun statJSON(treeURI: String, relativePath: String): String {
        // Fast path: stat-data cache hit (positive or negative).
        getStatCache(treeURI, relativePath)?.let { c ->
            return if (c.exists) {
                JSONObject()
                    .put("name", c.name)
                    .put("size", c.size)
                    .put("modTimeMs", c.modTimeMs)
                    .put("isDir", c.isDir)
                    .put("exists", true)
                    .toString()
            } else {
                JSONObject().put("exists", false).toString()
            }
        }

        val treeUri = Uri.parse(treeURI)
        val docId = resolveDocumentId(treeUri, relativePath)
            ?: run {
                putStatCacheNegative(treeURI, relativePath)
                return JSONObject().put("exists", false).toString()
            }

        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        // ExternalStorageProvider's fast-path returns a docId without
        // verifying existence on disk. The subsequent query() then throws
        // an IllegalArgumentException (with FileNotFoundException text in
        // its message) for paths that don't exist. Catch and translate
        // to {"exists": false}.
        val cursor = try {
            ctx.contentResolver.query(docUri, projection, null, null, null)
        } catch (e: Exception) {
            if (isNotFoundException(e)) {
                putStatCacheNegative(treeURI, relativePath)
                return JSONObject().put("exists", false).toString()
            }
            Log.w(TAG, "statJSON: query failed for $relativePath", e)
            throw e
        } ?: run {
            putStatCacheNegative(treeURI, relativePath)
            return JSONObject().put("exists", false).toString()
        }

        cursor.use {
            if (!it.moveToFirst()) {
                putStatCacheNegative(treeURI, relativePath)
                return JSONObject().put("exists", false).toString()
            }
            val name = it.getString(0) ?: ""
            val size = if (it.isNull(1)) 0L else it.getLong(1)
            val modTimeMs = if (it.isNull(2)) 0L else it.getLong(2)
            val mimeType = it.getString(3) ?: ""
            val isDir = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
            putStatCache(treeURI, relativePath, name, size, modTimeMs, isDir)
            return JSONObject()
                .put("name", name)
                .put("size", size)
                .put("modTimeMs", modTimeMs)
                .put("isDir", isDir)
                .put("exists", true)
                .toString()
        }
    }

    // ---- ListChildrenJSON ----

    override fun listChildrenJSON(treeURI: String, relativePath: String): String {
        val treeUri = Uri.parse(treeURI)
        val parentDocId = resolveDocumentId(treeUri, relativePath)
            ?: throw Exception("directory not found: $relativePath")

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        // Same fast-path caveat as statJSON: parentDocId may have been
        // computed without verifying existence, so query() can throw
        // FileNotFoundException. Translate to the canonical "directory not
        // found" error that wrapNotFound on the Go side maps to ErrNotExist.
        val cursor = try {
            ctx.contentResolver.query(childrenUri, projection, null, null, null)
        } catch (e: Exception) {
            if (isNotFoundException(e)) {
                throw Exception("directory not found: $relativePath")
            }
            Log.w(TAG, "listChildrenJSON: query failed for $relativePath", e)
            throw e
        } ?: return "[]"

        val arr = JSONArray()
        cursor.use {
            while (it.moveToNext()) {
                val docId = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                val size = if (it.isNull(2)) 0L else it.getLong(2)
                val modTimeMs = if (it.isNull(3)) 0L else it.getLong(3)
                val mimeType = it.getString(4) ?: ""
                val isDir = mimeType == DocumentsContract.Document.MIME_TYPE_DIR

                // Pre-warm caches so subsequent statJSON / openFd for this
                // child hit memory instead of Binder. The stat-data cache
                // is the dominant win during scans (caseFilesystem + the
                // deletedAndIgnored phase generate many more stat calls
                // than file Opens).
                val childRelPath = if (relativePath.isEmpty()) name else "$relativePath/$name"
                cacheDocId(treeURI, childRelPath, docId)
                putStatCache(treeURI, childRelPath, name, size, modTimeMs, isDir)

                arr.put(
                    JSONObject()
                        .put("name", name)
                        .put("size", size)
                        .put("modTimeMs", modTimeMs)
                        .put("isDir", isDir)
                )
            }
        }
        return arr.toString()
    }

    // ---- OpenFd ----

    override fun openFd(treeURI: String, relativePath: String, mode: String): Long {
        // Short-circuit on a fresh negative stat-cache entry (e.g. ignores.Load
        // probing for a .stignore that just got cached as not-exist via
        // checkCase's prior Lstat). Skips a guaranteed-doomed Binder call.
        // For read mode only — write/create paths shouldn't trust the cache.
        if (mode == "r") {
            val cached = getStatCache(treeURI, relativePath)
            if (cached != null && !cached.exists) {
                throw Exception("file not found: $relativePath")
            }
        }
        val treeUri = Uri.parse(treeURI)
        val docId = resolveDocumentId(treeUri, relativePath)
            ?: throw Exception("file not found: $relativePath")
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

        val safMode = when (mode) {
            "r"  -> "r"
            "w"  -> "w"
            "rw" -> "rw"
            "wt" -> "wt"
            else -> "r"
        }
        // ExternalStorageProvider's fast-path returns a docId without
        // verifying existence. openFileDescriptor then throws a
        // FileNotFoundException whose top-level getMessage() is
        // "Failed to determine if X is child of Y" — no "file not found"
        // / "filenotfound" / "no such file" substring, so the Go-side
        // wrapNotFound can't recognize it as a not-exist condition. Catch
        // and re-throw with a canonical message.
        val pfd: ParcelFileDescriptor = try {
            ctx.contentResolver.openFileDescriptor(docUri, safMode)
        } catch (e: Exception) {
            if (isNotFoundException(e)) {
                throw Exception("file not found: $relativePath")
            }
            Log.w(TAG, "openFd: openFileDescriptor failed for $relativePath mode=$mode", e)
            throw e
        } ?: throw Exception("openFileDescriptor returned null for $relativePath mode=$mode")
        // detachFd transfers ownership to the caller (Go); the ParcelFileDescriptor
        // can be GC'd without closing the fd.
        return pfd.detachFd().toLong()
    }

    // ---- CreateFile ----

    override fun createFile(
        treeURI: String,
        parentRelPath: String,
        name: String,
        mimeType: String,
    ): String {
        val treeUri = Uri.parse(treeURI)
        val parentDocId = resolveDocumentId(treeUri, parentRelPath)
            ?: throw Exception("parent directory not found: $parentRelPath")
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
        val newUri = try {
            DocumentsContract.createDocument(ctx.contentResolver, parentUri, mimeType, name)
        } catch (e: Exception) {
            Log.w(TAG, "createFile: createDocument failed for parent='$parentRelPath' name='$name'", e)
            throw e
        } ?: throw Exception("createDocument failed for $name in $parentRelPath")
        val newDocId = DocumentsContract.getDocumentId(newUri)
        val relPath = if (parentRelPath.isEmpty()) name else "$parentRelPath/$name"
        cacheDocId(treeURI, relPath, newDocId)
        // Drop any stale negative stat entry (e.g. .stfolder probed not-exist
        // moments before being created) so the next stat sees the new file.
        invalidateStatCache(treeURI, relPath)
        return relPath
    }

    // ---- CreateDir ----

    override fun createDir(treeURI: String, parentRelPath: String, name: String): String {
        return createFile(treeURI, parentRelPath, name, DocumentsContract.Document.MIME_TYPE_DIR)
    }

    // ---- Delete ----

    override fun delete(treeURI: String, relativePath: String) {
        val treeUri = Uri.parse(treeURI)
        val docId = resolveDocumentId(treeUri, relativePath)
            ?: throw Exception("not found: $relativePath")
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        if (!DocumentsContract.deleteDocument(ctx.contentResolver, docUri)) {
            throw Exception("deleteDocument failed for $relativePath")
        }
        invalidateCache(treeURI, relativePath)
        invalidateStatCache(treeURI, relativePath)
    }

    // ---- Rename ----

    override fun rename(treeURI: String, oldRelPath: String, newRelPath: String) {
        val treeUri = Uri.parse(treeURI)
        val oldDocId = resolveDocumentId(treeUri, oldRelPath)
            ?: throw Exception("source not found: $oldRelPath")

        val oldName = oldRelPath.substringAfterLast('/')
        val newName = newRelPath.substringAfterLast('/')
        val oldParent = oldRelPath.substringBeforeLast('/', "")
        val newParent = newRelPath.substringBeforeLast('/', "")

        var docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, oldDocId)

        // rename if the filename changed
        if (oldName != newName) {
            val renamedUri = DocumentsContract.renameDocument(ctx.contentResolver, docUri, newName)
            if (renamedUri != null) {
                docUri = renamedUri
            }
        }

        // move if the parent directory changed (API 24+)
        if (oldParent != newParent && android.os.Build.VERSION.SDK_INT >= 24) {
            val oldParentDocId = resolveDocumentId(treeUri, oldParent)
                ?: throw Exception("old parent not found: $oldParent")
            val newParentDocId = resolveDocumentId(treeUri, newParent)
                ?: throw Exception("new parent not found: $newParent")
            val oldParentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, oldParentDocId)
            val newParentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, newParentDocId)
            DocumentsContract.moveDocument(ctx.contentResolver, docUri, oldParentUri, newParentUri)
        }

        invalidateCache(treeURI, oldRelPath)
        invalidateStatCache(treeURI, oldRelPath)
        invalidateStatCache(treeURI, newRelPath)
        // re-resolve new path to refresh cache
        resolveDocumentId(treeUri, newRelPath)
    }

    // ---- SetLastModified ----

    override fun setLastModified(treeURI: String, relativePath: String, mtimeMs: Long) {
        // SAF's COLUMN_LAST_MODIFIED is read-only on most providers.
        // Best-effort: swallow failures.
    }

    // ---- UsageJSON ----

    override fun usageJSON(treeURI: String): String {
        // Resolve the SAF tree URI back to its underlying volume so we can
        // report real free/total bytes. StorageVolume.getDirectory() is API 30+;
        // on older Android (or any failure) we report effectively-infinite space
        // so syncthing's pre-write disk check doesn't refuse the sync. Real
        // ENOSPC will still surface through the write path.
        var free = Long.MAX_VALUE
        var total = Long.MAX_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                val dir = sm.getStorageVolume(Uri.parse(treeURI))?.directory
                if (dir != null) {
                    free = dir.freeSpace
                    total = dir.totalSpace
                }
            } catch (_: Exception) {
                // Fall through to the infinite-space sentinel.
            }
        }
        return JSONObject().put("Free", free).put("Total", total).toString()
    }

    // ---- GetDisplayName ----

    override fun getDisplayName(treeURI: String): String {
        val treeUri = Uri.parse(treeURI)
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val cursor = ctx.contentResolver.query(docUri, projection, null, null, null)
            ?: return treeURI
        cursor.use {
            return if (it.moveToFirst()) it.getString(0) ?: treeURI else treeURI
        }
    }

    // ---- Document ID resolution ----

    /**
     * Resolve a relative POSIX path (e.g. "photos/2024/img.jpg") to a SAF
     * document ID. For ExternalStorageProvider trees (primary internal
     * storage and SD cards — the common case for this app), the document
     * ID has the well-known shape "<volume>:<rootRelPath>", so we can
     * compute it from the path without any Binder call. The cache+walk
     * fallback handles non-ExternalStorageProvider providers.
     */
    private fun resolveDocumentId(treeUri: Uri, relativePath: String): String? {
        if (relativePath.isEmpty()) {
            return DocumentsContract.getTreeDocumentId(treeUri)
        }

        // Fast path: ExternalStorageProvider's document IDs are constructed
        // by string concatenation, not by querying the provider. Eliminates
        // both the Binder roundtrip and the cache pressure for scans of any
        // size. Skipped for other authorities since their docId schemes
        // (cloud, OEM custom) aren't path-derivable.
        if (treeUri.authority == "com.android.externalstorage.documents") {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val colon = rootDocId.indexOf(':')
            if (colon >= 0) {
                val volume = rootDocId.substring(0, colon)
                val root = rootDocId.substring(colon + 1)
                val full = when {
                    root.isEmpty() -> relativePath
                    else -> "$root/$relativePath"
                }
                return "$volume:$full"
            }
        }

        val cacheKey = "$treeUri\n$relativePath"
        docIdCache.get(cacheKey)?.let { return it }

        val parts = relativePath.split("/")
        var currentDocId = DocumentsContract.getTreeDocumentId(treeUri)
        var currentPath = ""

        for (part in parts) {
            if (part.isEmpty() || part == ".") continue
            currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"
            val partialKey = "$treeUri\n$currentPath"
            val cached = docIdCache.get(partialKey)
            if (cached != null) {
                currentDocId = cached
                continue
            }
            val childDocId = findChildDocId(treeUri, currentDocId, part) ?: return null
            docIdCache.put(partialKey, childDocId)
            currentDocId = childDocId
        }

        return currentDocId
    }

    private fun findChildDocId(treeUri: Uri, parentDocId: String, childName: String): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        val cursor = ctx.contentResolver.query(childrenUri, projection, null, null, null)
            ?: return null
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(1)
                if (name == childName) {
                    return it.getString(0)
                }
            }
        }
        return null
    }

    private fun cacheDocId(treeURI: String, relativePath: String, docId: String) {
        // ExternalStorageProvider doc IDs are derived from the path in
        // resolveDocumentId, so caching them just wastes CPU and memory.
        if (treeURI.startsWith("content://com.android.externalstorage.documents/")) return
        docIdCache.put("$treeURI\n$relativePath", docId)
    }

    private fun invalidateCache(treeURI: String, relativePath: String) {
        docIdCache.remove("$treeURI\n$relativePath")
        // also invalidate children
        val prefix = "$treeURI\n$relativePath/"
        val snapshot = docIdCache.snapshot()
        for (key in snapshot.keys) {
            if (key.startsWith(prefix)) {
                docIdCache.remove(key)
            }
        }
    }

    // ---- RegisterWatch ----

    override fun registerWatch(treeURI: String): Long {
        val id = nextWatchId.getAndIncrement()
        val treeUri = Uri.parse(treeURI)
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val watchUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)

        val entry = WatchEntry(treeURI, object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                onChange(selfChange, null)
            }
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // ContentObserver typically gives coarse notifications.
                // We emit a NonRemove event for the root so syncthing triggers a rescan.
                val event = JSONObject()
                    .put("type", "nonremove")
                    .put("path", ".")
                entry?.events?.offer(event)
            }

            private val entry get() = watches[id]
        })

        watches[id] = entry
        ctx.contentResolver.registerContentObserver(watchUri, true, entry.observer)
        return id
    }

    // ---- UnregisterWatch ----

    override fun unregisterWatch(watchID: Long) {
        val entry = watches.remove(watchID) ?: return
        ctx.contentResolver.unregisterContentObserver(entry.observer)
    }

    // ---- PollWatchEventsJSON ----

    override fun pollWatchEventsJSON(watchID: Long, timeoutMs: Long): String {
        val entry = watches[watchID]
            ?: throw Exception("unknown watch ID: $watchID")

        val arr = JSONArray()
        // Block for up to timeoutMs waiting for the first event
        val first = entry.events.poll(timeoutMs, TimeUnit.MILLISECONDS)
        if (first != null) {
            arr.put(first)
            // Drain any additional events that arrived
            val batch = mutableListOf<JSONObject>()
            entry.events.drainTo(batch, 100)
            for (ev in batch) arr.put(ev)
        }
        return arr.toString()
    }

    // ---- StatBatchJSON ----

    override fun statBatchJSON(treeURI: String, pathsJSON: String): String {
        val paths = JSONArray(pathsJSON)
        val results = JSONArray()
        for (i in 0 until paths.length()) {
            val relPath = paths.getString(i)
            val stat = statJSON(treeURI, relPath)
            results.put(JSONObject(stat))
        }
        return results.toString()
    }

    // ---- ValidatePermission ----

    override fun validatePermission(treeURI: String): Boolean {
        val targetUri = Uri.parse(treeURI)
        val perms = ctx.contentResolver.persistedUriPermissions
        for (p in perms) {
            if (p.uri == targetUri && p.isReadPermission && p.isWritePermission) {
                return true
            }
        }
        return false
    }
}
