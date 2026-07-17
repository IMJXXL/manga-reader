package com.mangareader

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.mangareader.data.Book
import com.mangareader.data.Folder
import com.mangareader.data.MangaDao
import com.mangareader.zip.CommonsZipHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class Scanner(private val ctx: Context, private val dao: MangaDao) {
    companion object {
        private const val TAG = "Scanner"
        private val UNSUPPORTED_STREAMING_EXTS = setOf("cbr", "rar", "cb7", "7z", "mobi", "azw", "azw3", "pdb", "prc")
    }

    suspend fun scan(uri: Uri, onProgress: ((Int) -> Unit)? = null): Int = withContext(Dispatchers.IO) {
        var count = 0
        try {
            val treeStr = uri.toString()
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val name = docId.substringAfterLast("/").replace("%20", " ")
            val auth = uri.authority ?: ""
            val child = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)

            val books = mutableListOf<Book>()
            scanDirFast(auth, treeStr, docId, child, books) { found -> onProgress?.invoke(found) }

            if (books.isNotEmpty()) {
                dao.insertBooksBatch(books)
                count = books.size
                cacheRealPaths(books)
            }

            val ex = dao.getFolderByUri(treeStr)
            if (ex != null) dao.updateFolder(ex.copy(lastScanAt = System.currentTimeMillis(), displayName = name))
            else dao.insertFolder(Folder(treeUri = treeStr, displayName = name, lastScanAt = System.currentTimeMillis()))
            Log.d(TAG, "scan: $count from $name (${System.currentTimeMillis()}ms)")
        } catch (e: Exception) { Log.e(TAG, "scan: ${e.message}", e) }
        count
    }

    fun preloadCovers(books: List<Book>, onProgress: ((loaded: Int, total: Int) -> Unit)? = null) {
        val coverDir = File(ctx.filesDir, "covers")
        if (!coverDir.exists()) coverDir.mkdirs()
        var ok = 0; var fail = 0; var skip = 0; var noPath = 0
        val total = books.size
        for ((idx, b) in books.withIndex()) {
            val coverPath = b.coverPath
            if (coverPath == null) { noPath++; continue }
            if (!coverPath.startsWith("cv:")) { noPath++; continue }
            try {
                val key = md5(coverPath)
                val file = File(coverDir, "$key.thumb")
                if (file.exists()) { skip++; continue }

                val parts = coverPath.removePrefix("cv:").split("|")
                if (parts.size < 3) { fail++; continue }
                val auth = parts[0]
                val treeDocId = parts[1]
                val docId = parts[2]
                val treeUri = Uri.parse("content://$auth/tree/${Uri.encode(treeDocId)}")
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

                val ext = docId.substringAfterLast(".").lowercase()
                Log.d(TAG, "preloadCovers: docId=$docId ext=$ext")
                if (ext in listOf("cbz", "zip", "epub")) {
                    preloadZipCover(coverPath, docUri, file)
                } else if (ext in listOf("mobi", "azw", "azw3")) {
                    preloadMobiCover(coverPath, docUri, file)
                } else if (ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp")) {
                    preloadImageCover(coverPath, docUri, file)
                }
                if (file.exists()) ok++ else fail++
            } catch (e: Exception) {
                Log.e(TAG, "preloadCover failed: ${e.message}")
                fail++
            }
            onProgress?.invoke(idx + 1, total)
        }
        Log.d(TAG, "preloadCovers done: ok=$ok fail=$fail skip=$skip")
    }

    private fun preloadZipCover(coverPath: String, docUri: Uri, file: File) {
        Log.d(TAG, "preloadZipCover: docUri=$docUri")
        try {
            val data = CommonsZipHelper.readImageEntry(ctx, docUri, 0)
            if (data != null && data.isNotEmpty()) {
                Log.d(TAG, "preloadZipCover: got ${data.size} bytes from CommonsZipHelper")
                val bmp = decodeCoverBitmap(data)
                if (bmp != null) {
                    file.outputStream().use { out -> bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out) }
                    bmp.recycle()
                    Log.d(TAG, "preloadZipCover: OK saved ${file.name}")
                    return
                }
                Log.w(TAG, "preloadZipCover: decodeCoverBitmap returned null")
            } else {
                Log.w(TAG, "preloadZipCover: CommonsZipHelper returned null/empty")
            }
        } catch (e: Exception) { Log.e(TAG, "preloadZipCover: ${e.message}") }
    }

    private fun preloadMobiCover(coverPath: String, docUri: Uri, file: File) {
        // fd-based opening
        try {
            val cacheKey = docUri.toString()
            val pfd = ctx.contentResolver.openFileDescriptor(docUri, "r")
            if (pfd != null) {
                val fd = pfd.detachFd()
                try {
                    try {
                        com.mangareader.native.NativeEngine.openMobiWithFd(cacheKey, fd)
                    } catch (_: Exception) {}
                    val rawData = com.mangareader.native.NativeEngine.readMobiCover(cacheKey)
                    if (rawData != null) {
                        val bmp = decodeCoverBitmap(rawData)
                        if (bmp != null) {
                            file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
                            bmp.recycle()
                            return
                        }
                    }
                } finally {
                    try { pfd?.close() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        // Fallback: scan via ContentResolver (JPEG only)
        try {
            ctx.contentResolver.openInputStream(docUri)?.use { input ->
                val bis = java.io.BufferedInputStream(input, 65536)
                var b0 = 0; var b1 = bis.read(); var b2 = bis.read(); var b3 = bis.read()
                var pos = 4L
                while (b3 != -1) {
                    if (b0 == 0xFF && b1 == 0xD8) {
                        val imgData = readJpegFromStream(bis, pos)
                        if (imgData != null) {
                            val bmp = decodeCoverBitmap(imgData)
                            if (bmp != null) {
                                file.outputStream().use { out -> bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out) }
                                bmp.recycle()
                            }
                        }
                        return
                    }
                    b0 = b1; b1 = b2; b2 = b3; b3 = bis.read(); pos++
                }
            }
        } catch (_: Exception) {}
    }

    private fun readJpegFromStream(bis: java.io.BufferedInputStream, startPos: Long): ByteArray? {
        try {
            val baos = java.io.ByteArrayOutputStream()
            baos.write(0xFF)
            baos.write(0xD8)
            var prev = 0xFF
            while (true) {
                val b = bis.read(); if (b == -1) break
                baos.write(b)
                if (prev == 0xFF && b == 0xD9) break
                prev = b
            }
            return if (baos.size() > 100) baos.toByteArray() else null
        } catch (_: Exception) { return null }
    }

    private fun preloadImageCover(coverPath: String, docUri: Uri, file: File) {
        Log.d(TAG, "preloadImageCover: docUri=$docUri")
        try {
            ctx.contentResolver.openInputStream(docUri)?.use { input ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, opts)
                var sampleSize = 1
                while (opts.outWidth / sampleSize > 1024 || opts.outHeight / sampleSize > 1024) sampleSize *= 2
                Log.d(TAG, "preloadImageCover: ${opts.outWidth}x${opts.outHeight} sample=$sampleSize")
                ctx.contentResolver.openInputStream(docUri)?.use { input2 ->
                    val bmp = BitmapFactory.decodeStream(input2, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
                    if (bmp != null) {
                        file.outputStream().use { out -> bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out) }
                        bmp.recycle()
                        Log.d(TAG, "preloadImageCover: OK saved to ${file.name}")
                    } else {
                        Log.w(TAG, "preloadImageCover: decode returned null")
                    }
                }
            } ?: Log.w(TAG, "preloadImageCover: openInputStream returned null")
        } catch (e: Exception) { Log.e(TAG, "preloadImageCover: ${e.message}") }
    }

    private fun getRealFile(uri: Uri): File? {
        try {
            ctx.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex("_data")
                    if (idx >= 0) {
                        val path = cursor.getString(idx)
                        if (path != null) {
                            val f = File(path)
                            if (f.exists() && f.length() > 0) return f
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun decodeCoverBitmap(data: ByteArray): android.graphics.Bitmap? {
        if (data.isEmpty()) return null
        return try {
            com.mangareader.native.NativeEngine.decodeRaw(data, 1024, 1024)
                ?: BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }.let { opts ->
                    var s = 1
                    while (opts.outWidth / s > 1024 || opts.outHeight / s > 1024) s *= 2
                    BitmapFactory.Options().apply { inSampleSize = s }
                })
        } catch (e: OutOfMemoryError) {
            System.gc()
            try {
                BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = 4 })
            } catch (_: Exception) { null }
        } catch (_: Exception) { null }
    }

    private fun md5(input: String): String {
        val bytes = java.security.MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun cacheRealPaths(books: List<Book>) {
        val prefs = ctx.getSharedPreferences("manga_reader", android.content.Context.MODE_PRIVATE)
        val editor = prefs.edit()
        var changed = false
        for (b in books) {
            val filePath = b.filePath
            val cached = prefs.getString("realpath_$filePath", null)
            if (cached != null) continue
            try {
                val p = filePath.removePrefix("a:").split("|", limit = 4)
                if (p.size < 3) continue
                val realFile = getRealFileForPath(p[0], p[1], p[2])
                if (realFile != null) {
                    editor.putString("realpath_$filePath", realFile.absolutePath)
                    changed = true
                }
            } catch (_: Exception) {}
        }
        if (changed) editor.apply()
    }

    private fun preDecodeFirstPages(books: List<Book>) {
        val decodeCount = 5
        for (b in books) {
            val filePath = b.filePath
            if (!filePath.startsWith("a:")) continue
            try {
                val parts = filePath.removePrefix("a:").split("|", limit = 4)
                if (parts.size < 3) continue
                val ext = parts.getOrElse(3) { "" }
                if (ext !in listOf("cbz", "zip", "cbr", "rar", "cb7", "7z")) continue
                val realFile = getRealFileForPath(parts[0], parts[1], parts[2]) ?: continue
                val archive = com.mangareader.native.NativeEngine.openArchive(realFile.absolutePath) ?: continue
                val screenMax = maxOf(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels)
                val count = minOf(decodeCount, archive.pageCount)
                for (i in 0 until count) {
                    try {
                        val raw = com.mangareader.native.NativeEngine.readPageRaw(archive, i) ?: continue
                        val bmp = com.mangareader.native.NativeEngine.decodeRaw(raw, screenMax, screenMax) ?: continue
                        try { com.mangareader.native.NativeEngine.cacheDecodedBitmap(archive, i, bmp) } catch (_: Throwable) {}
                    } catch (_: Exception) {}
                }
                archive.close()
            } catch (_: Exception) {}
        }
    }

    private fun getRealFileForPath(auth: String, treeDocId: String, docId: String): File? {
        try {
            val treeUri = Uri.parse("content://$auth/tree/${Uri.encode(treeDocId)}")
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            ctx.contentResolver.query(docUri, arrayOf("_data"), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex("_data")
                    if (idx >= 0) {
                        val path = cursor.getString(idx)
                        if (path != null) {
                            val f = File(path)
                            if (f.exists() && f.length() > 0) return f
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    suspend fun checkNomedia(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val child = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)
            ctx.contentResolver.query(child, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                val ni = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val name = c.getString(ni) ?: continue
                    if (name.equals(".nomedia", ignoreCase = true)) return@withContext true
                }
            }
        } catch (e: Exception) { Log.e(TAG, "checkNomedia: ${e.message}") }
        false
    }

    suspend fun createNomedia(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val child = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)
            DocumentsContract.createDocument(ctx.contentResolver, child, "application/octet-stream", ".nomedia")
            true
        } catch (e: Exception) {
            Log.e(TAG, "createNomedia: ${e.message}")
            false
        }
    }

    suspend fun getLastImportedFolder(): Uri? = withContext(Dispatchers.IO) {
        try {
            val folders = dao.getAllFoldersList()
            folders.maxByOrNull { it.lastScanAt }?.let { Uri.parse(it.treeUri) }
        } catch (e: Exception) {
            Log.e(TAG, "getLastImportedFolder: ${e.message}")
            null
        }
    }

    suspend fun rescan(f: Folder): Int = withContext(Dispatchers.IO) {
        var count = 0
        try {
            val uri = Uri.parse(f.treeUri)
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val auth = uri.authority ?: ""
            val oldCount = dao.countBooksByFolder(auth, docId)
            val child = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)

            val books = mutableListOf<Book>()
            scanDirFast(auth, f.treeUri, docId, child, books, skipDedup = true)
            if (books.isNotEmpty()) {
                dao.deleteBooksByFolderParams(auth, docId)
                dao.insertBooksBatch(books)
                cacheRealPaths(books)
                preloadCovers(books)
                count = books.size - oldCount
            }
            // 扫描为空时不删除旧数据，避免临时故障导致书架清空
            dao.updateFolder(f.copy(lastScanAt = System.currentTimeMillis()))
        } catch (e: Exception) { Log.e(TAG, "rescan: ${e.message}") }
        count
    }

    private suspend fun scanDirFast(auth: String, treeStr: String, treeDocId: String, childUri: Uri, books: MutableList<Book>, skipDedup: Boolean = false, onFound: ((Int) -> Unit)? = null) {
        try {
            val existingPaths = mutableSetOf<String>()
            try {
                val existingBooks = dao.getAllBooksList()
                existingBooks.forEach { existingPaths.add(it.filePath) }
            } catch (_: Exception) {}

            fun isPathDuplicate(newPath: String): Boolean {
                if (newPath in existingPaths) return true
                if (newPath.startsWith("a:")) {
                    val zPath = "z:${newPath}//"
                    if (zPath in existingPaths) return true
                }
                if (newPath.startsWith("z:")) {
                    val aPath = newPath.removePrefix("z:").removeSuffix("//")
                    if (aPath in existingPaths) return true
                }
                return false
            }

            var found = 0
            ctx.contentResolver.query(childUri, null, null, null, null)?.use { c ->
                val ni = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val ii = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mi = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (c.moveToNext()) {
                    val name = c.getString(ni) ?: continue
                    val id = c.getString(ii) ?: continue
                    val mime = if (mi >= 0) c.getString(mi) ?: "" else ""
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR

                    if (isDir) {
                        // 跳过空文件夹：先检查是否包含漫画
                        val dirChild = DocumentsContract.buildChildDocumentsUriUsingTree(
                            Uri.parse("content://$auth/tree/${Uri.encode(treeDocId)}"), id)
                        var hasComics = false
                        try {
                            ctx.contentResolver.query(dirChild, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { dc ->
                                while (dc.moveToNext()) {
                                    val n = dc.getString(0) ?: continue
                                    if (n.isComic() || n.isImg()) { hasComics = true; break }
                                }
                            }
                        } catch (_: Exception) {}
                        if (!hasComics) continue  // 空文件夹，跳过

                        val path = "d:$auth|$treeDocId|$id"
                        if (skipDedup || !isPathDuplicate(path)) {
                            var firstCover: String? = null
                            try { firstCover = findFirstCoverInFolder(auth, treeDocId, id) } catch (_: Exception) {}
                            books.add(Book(title = name.replace(Regex("[._-]"), " ").trim(), filePath = path, fileType = "dir", coverPath = firstCover))
                            found++; onFound?.invoke(found)
                        }
                    } else if (name.isComic()) {
                        val ext = name.substringAfterLast(".").lowercase()
                        val docId = "$auth|$treeDocId|$id"
                        val path = "a:$docId|$ext"
                        if (skipDedup || !isPathDuplicate(path)) {
                            val title = name.substringBeforeLast(".").replace(Regex("[._-]"), " ").trim()
                            val coverPath = "cv:$docId|$name"
                            if ((ext == "cbz" || ext == "zip") && com.mangareader.viewer.ArchiveEngine(ctx).hasZipSubDirs("a:$docId|$ext")) {
                                books.add(Book(title = title, filePath = "z:a:$docId|$ext//", fileType = ext, coverPath = coverPath))
                            } else {
                                books.add(Book(title = title, filePath = path, fileType = ext, coverPath = coverPath))
                            }
                            found++; onFound?.invoke(found)
                        }
                    }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "scanDirFast: ${e.message}") }
    }

    // 递归查找文件夹内第一本漫画（按名称排序），用作封面
    private fun findFirstCoverInFolder(auth: String, treeDocId: String, folderDocId: String, depth: Int = 0): String? {
        if (depth > 3) return null
        val treeUri = Uri.parse("content://$auth/tree/${Uri.encode(treeDocId)}")
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderDocId)
        Log.d(TAG, "findFirstCover: depth=$depth folder=$folderDocId treeUri=$treeUri")
        val comics = mutableListOf<Pair<String, String>>()
        val subFolders = mutableListOf<String>()
        try {
            ctx.contentResolver.query(childUri, null, null, null, null)?.use { c ->
                val ni = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val ii = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mi = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (c.moveToNext()) {
                    val n = c.getString(ni) ?: continue
                    val id = c.getString(ii) ?: continue
                    val mime = if (mi >= 0) c.getString(mi) ?: "" else ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        subFolders.add(id)
                    } else if (n.isComic() || (mime.startsWith("image/") && n.isImg())) {
                        comics.add(n to id)
                    }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "findFirstCover query: ${e.message}") }
        Log.d(TAG, "findFirstCover: comics=${comics.size} subFolders=${subFolders.size}")
        if (comics.isNotEmpty()) {
            val sorted = comics.sortedWith { a, b -> com.mangareader.util.ComicSortUtil.fileNameComparator.compare(a.first, b.first) }
            val result = "cv:$auth|$treeDocId|${sorted[0].second}|${sorted[0].first}"
            Log.d(TAG, "findFirstCover: OK result=$result")
            return result
        }
        for (subId in subFolders) {
            val result = findFirstCoverInFolder(auth, treeDocId, subId, depth + 1)
            if (result != null) return result
        }
        return null
    }

    private fun String.isImg() = lowercase().let {
        it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") ||
                it.endsWith(".webp") || it.endsWith(".gif") || it.endsWith(".bmp")
    }

    private fun String.isComic() = lowercase().let {
        it.endsWith(".cbz") || it.endsWith(".zip") || it.endsWith(".pdf") || it.endsWith(".epub") ||
                it.endsWith(".mobi") || it.endsWith(".azw") || it.endsWith(".azw3") ||
                it.endsWith(".cbr") || it.endsWith(".rar") || it.endsWith(".cb7") || it.endsWith(".7z")
    }
}
