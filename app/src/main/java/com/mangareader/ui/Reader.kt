package com.mangareader.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.Log
import android.view.View

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.App
import com.mangareader.data.Book
import com.mangareader.data.ReadingHistory
import com.mangareader.viewer.ArchiveEngine
import com.mangareader.viewer.NaturalComparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

    enum class Mode(val label: String) { 
        FLIP("单页式（从左到右）"),
        RTL("单页式（从右到左）"),
        VERT_PAGER("单页式（从上到下）"),
        VERT("条漫"),
        WEBTOON_GAP("条漫（页间有空隙）")
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Reader(bookId: Long, back: () -> Unit) {
    val ctx = LocalContext.current
    val act = ctx as? Activity
    val app = ctx.applicationContext as App
    val dao = app.db.mangaDao()
    val engine = remember { ArchiveEngine(ctx) }
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE) }
    var currentBookId by remember { mutableLongStateOf(bookId) }
    var config by remember { mutableStateOf(com.mangareader.data.ReaderConfig.load(ctx, bookId)) }
    var configVersion by remember { mutableIntStateOf(0) }

    // 监听 SharedPreferences 外部修改，自动触发 config 重读
    DisposableEffect(bookId) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith("book_${bookId}_") == true || key == "scrollMode" || key == "readBackground") {
                configVersion++
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    var book by remember { mutableStateOf<Book?>(null) }
    var pageList by remember { mutableStateOf(listOf<ArchiveEngine.PageMeta>()) }
    var cur by remember { mutableIntStateOf(0) }
    var mode by remember {
        val savedMode = prefs.getInt("read_mode_$bookId", -1)
        mutableStateOf(
            if (savedMode in Mode.entries.indices) Mode.entries[savedMode]
            else when (config.scrollMode) {
                com.mangareader.data.ReaderConfig.ScrollMode.VERTICAL -> Mode.VERT
                com.mangareader.data.ReaderConfig.ScrollMode.HORIZONTAL_LEFT -> Mode.FLIP
                com.mangareader.data.ReaderConfig.ScrollMode.HORIZONTAL_RIGHT -> Mode.RTL
            }
        )
    }

    // Reload mode when bookId or config changes
    LaunchedEffect(bookId, configVersion) {
        // 二次签名校验（与 MainActivity 互补，任一被移除则崩溃）
        com.mangareader.SignatureVerifier.verify(ctx, "")
        config = com.mangareader.data.ReaderConfig.load(ctx, bookId)
        val savedMode = prefs.getInt("read_mode_$bookId", -1)
        mode = if (savedMode in Mode.entries.indices) Mode.entries[savedMode] else when (config.scrollMode) {
            com.mangareader.data.ReaderConfig.ScrollMode.VERTICAL -> Mode.VERT
            com.mangareader.data.ReaderConfig.ScrollMode.HORIZONTAL_LEFT -> Mode.FLIP
            com.mangareader.data.ReaderConfig.ScrollMode.HORIZONTAL_RIGHT -> Mode.RTL
        }
        // 同步：确保 scrollMode 和 read_mode 一致
        val expectedScrollMode = when (mode) {
            Mode.VERT, Mode.WEBTOON_GAP -> com.mangareader.data.ReaderConfig.ScrollMode.VERTICAL
            Mode.RTL -> com.mangareader.data.ReaderConfig.ScrollMode.HORIZONTAL_RIGHT
            else -> com.mangareader.data.ReaderConfig.ScrollMode.HORIZONTAL_LEFT
        }
        if (config.scrollMode != expectedScrollMode) {
            config = config.copy(scrollMode = expectedScrollMode)
            config.save(ctx, bookId)
        }
    }
    var ui by remember { mutableStateOf(true) }
    var uiJob by remember { mutableStateOf<Job?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    var navStack by remember { mutableStateOf(listOf<String>()) }
    var effectiveRootPath by remember { mutableStateOf("") }
    var items by remember { mutableStateOf(listOf<ArchiveEngine.Item>()) }
    var tocItems by remember { mutableStateOf(listOf<ArchiveEngine.Item>()) }
    var viewing by remember { mutableStateOf(false) }
    var showComplete by remember { mutableStateOf(false) }
    var hasShownCompleteThisSession by remember { mutableStateOf(false) }
    var showErrorDialog by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val isVerticalMode = mode == Mode.VERT || mode == Mode.VERT_PAGER || mode == Mode.WEBTOON_GAP
    val isRtl = mode == Mode.RTL
    var showNavOverlay by remember { mutableStateOf(false) }
    var scrollX by remember { mutableIntStateOf(0) }
    var scrollY by remember { mutableIntStateOf(0) }
    var zoomLevel by remember { mutableFloatStateOf(1f) }
    var showChapterTransition by remember { mutableStateOf(false) }
    var chapterTransitionIsLast by remember { mutableStateOf(false) }
    var showSwitchConfirm by remember { mutableStateOf(false) }
    var switchTargetName by remember { mutableStateOf("") }
    var switchTargetPath by remember { mutableStateOf("") }

    val displayPages = remember(pageList) { pageList }

    var saveJob by remember { mutableStateOf<Job?>(null) }
    var activePagerRef by remember { mutableStateOf<com.mangareader.native.PagerReaderView?>(null) }
    var activeWebtoonRef by remember { mutableStateOf<com.mangareader.native.WebtoonReaderView?>(null) }

    fun hideUi() { hideSystemBars(act, config.isImmersiveMode, config.isKeepScreenOn) }
    fun showUi() { showSystemBars(act, config.isKeepScreenOn) }
    fun restoreUi() { restoreSystemBarsSync(act) }

    LaunchedEffect(viewing) {
        if (viewing) { delay(100); hideUi() }
    }
    DisposableEffect(Unit) { onDispose { restoreUi() } }

    fun save() {
        book?.let { b ->
            saveJob?.cancel()
            saveJob = scope.launch {
                activePagerRef?.updateZoomStateFromSSIV()
                val cx = activePagerRef?.currentCenterX?.toInt() ?: scrollX
                val cy = activePagerRef?.currentCenterY?.toInt() ?: scrollY
                val zs = activePagerRef?.currentZoomScale ?: zoomLevel
                scrollX = cx; scrollY = cy; zoomLevel = zs
                withContext(Dispatchers.IO) {
                    dao.updateReadingProgress(b.id, cur + 1, System.currentTimeMillis())
                    dao.insertHistory(ReadingHistory(
                        bookId = b.id,
                        pagesRead = cur + 1,
                        scrollX = cx,
                        scrollY = cy,
                        zoomLevel = zs
                    ))
                }
            }
        }
    }

    var goJob by remember { mutableStateOf<Job?>(null) }
    fun go(d: Int) {
        if (displayPages.isEmpty()) return
        val n = (cur + d).coerceIn(0, displayPages.size - 1)
        if (n == cur) return
        if (n != cur) { cur = n; save() }
        if (cur >= displayPages.size - 1 && viewing && displayPages.size > 1 && !showComplete && !hasShownCompleteThisSession) {
            showComplete = true; hasShownCompleteThisSession = true
        }
    }

    fun openFolder(path: String) {
        loading = true; viewing = false; pageList = emptyList(); errorMsg = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    var r = try { engine.children(path) } catch (_: Exception) { emptyList() }
                    var newTocItems = r
                    var newPath = navStack.lastOrNull() ?: path
                    while (r.size == 1 && r[0].dir && r[0].name.isNotEmpty()) {
                        val child = r[0]
                        if (newPath.startsWith("z:")) {
                            val inner = newPath.removePrefix("z:")
                            val sepIdx = inner.indexOf("//")
                            if (sepIdx >= 0) {
                                var archivePath = inner.substring(0, sepIdx)
                                if (!archivePath.startsWith("a:")) archivePath = "a:$archivePath"
                                val currentSubPath = inner.substring(sepIdx + 2)
                                val base = if (currentSubPath.isEmpty()) "" else if (currentSubPath.endsWith("/")) currentSubPath else "$currentSubPath/"
                                newPath = "z:${archivePath}//${base}${child.name}/"
                                r = try { engine.children(newPath) } catch (_: Exception) { emptyList() }
                                if (r.isNotEmpty()) newTocItems = r
                                continue
                            }
                        }
                        break
                    }
                    val pages = if (r.isEmpty()) {
                        try { engine.listPages(newPath) } catch (_: Exception) { emptyList() }
                    } else emptyList()
                    Triple(newTocItems, r, pages)
                }
                val zCount = navStack.count { it.startsWith("z:") }
                if (zCount <= 1 && result.first.isNotEmpty()) {
                    tocItems = result.first
                }
                val childItems = result.second
                val pages = result.third
                effectiveRootPath = navStack.lastOrNull() ?: path
                items = childItems
                if (items.isEmpty()) {
                    pageList = pages
                    cur = 0; viewing = true
                }
            } catch (e: Exception) { errorMsg = "加载失败: ${e.message}" }
            loading = false
        }
    }

    fun openItem(item: ArchiveEngine.Item) {
        if (item.dir) {
            val currentPath = navStack.lastOrNull()
            if (currentPath?.startsWith("z:") == true) {
                val inner = currentPath.removePrefix("z:")
                val sepIdx = inner.indexOf("//")
                if (sepIdx >= 0) {
                    val archivePath = inner.substring(0, sepIdx)
                    val currentSubPath = inner.substring(sepIdx + 2)
                    val base = if (currentSubPath.isEmpty()) "" else if (currentSubPath.endsWith("/")) currentSubPath else "$currentSubPath/"
                    val newSubPath = "${base}${item.name}/"
                    navStack = navStack + "z:${archivePath}//${newSubPath}"
                    openFolder(navStack.last())
                    return
                }
            }
            navStack = navStack + "d:${item.auth}|${item.tree}|${item.doc}"
            openFolder(navStack.last())
        } else {
            val currentPath = navStack.lastOrNull()
            if (currentPath?.startsWith("z:") == true) {
                loading = true; viewing = false; pageList = emptyList(); errorMsg = null
                scope.launch {
                    try {
                        val pages = withContext(Dispatchers.IO) { engine.listPages(currentPath) }
                        pageList = pages; cur = 0; viewing = true
                    } catch (e: Exception) { errorMsg = "加载失败: ${e.message}" }
                    loading = false
                }
                return
            }
            loading = true; viewing = false; pageList = emptyList(); errorMsg = null
            scope.launch {
                try {
                    val triple = withContext(Dispatchers.IO) {
                        val path = "a:${item.auth}|${item.tree}|${item.doc}|${item.name.substringAfterLast(".").lowercase()}"
                        val pages = engine.listPages(path)
                        Triple(path, pages, true)
                    }
                    navStack = navStack + triple.first
                    pageList = triple.second
                    cur = 0
                    viewing = true
                } catch (e: Exception) { errorMsg = "加载失败: ${e.message}" }
                loading = false
            }
        }
    }

    suspend fun findAdjacentBook(direction: Int): com.mangareader.data.Book? {
        return try {
            val allBooks = dao.getAllBooksList()
            val currentPath = book?.filePath ?: return null
            val idx = allBooks.indexOfFirst { b -> b.filePath == currentPath }
            if (idx == -1) return null
            val targetIdx = idx + direction
            if (targetIdx in allBooks.indices) allBooks[targetIdx] else null
        } catch (_: Exception) { null }
    }

    fun extractSubPathFromNav(): String {
        val last = navStack.lastOrNull() ?: return ""
        if (!last.startsWith("z:")) return ""
        val inner = last.removePrefix("z:")
        val sep = inner.indexOf("//")
        return if (sep >= 0) inner.substring(sep + 2) else ""
    }

    fun switchToNext() {
        val curSubPath = extractSubPathFromNav()
        if (tocItems.size > 1) {
            val curIdx = tocItems.indexOfFirst { it.subPath == curSubPath }
            val nextIdx = if (curIdx == -1) 1 else curIdx + 1
            if (nextIdx < tocItems.size) {
                switchTargetName = tocItems[nextIdx].name
                switchTargetPath = tocItems[nextIdx].subPath
                showSwitchConfirm = true
            } else {
                scope.launch {
                    val nextBook = findAdjacentBook(1)
                    if (nextBook != null) {
                        switchTargetName = nextBook.title
                        switchTargetPath = nextBook.filePath
                        showSwitchConfirm = true
                    } else {
                        android.widget.Toast.makeText(ctx, "没有下一本了", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            scope.launch {
                val nextBook = findAdjacentBook(1)
                if (nextBook != null) {
                    switchTargetName = nextBook.title
                    switchTargetPath = nextBook.filePath
                    showSwitchConfirm = true
                } else {
                    android.widget.Toast.makeText(ctx, "没有下一本了", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun switchToPrevious() {
        val curSubPath = extractSubPathFromNav()
        if (tocItems.size > 1) {
            val curIdx = tocItems.indexOfFirst { it.subPath == curSubPath }
            val prevIdx = if (curIdx <= 0) -1 else curIdx - 1
            if (prevIdx >= 0) {
                switchTargetName = tocItems[prevIdx].name
                switchTargetPath = tocItems[prevIdx].subPath
                showSwitchConfirm = true
            } else {
                scope.launch {
                    val prevBook = findAdjacentBook(-1)
                    if (prevBook != null) {
                        switchTargetName = prevBook.title
                        switchTargetPath = prevBook.filePath
                        showSwitchConfirm = true
                    } else {
                        android.widget.Toast.makeText(ctx, "没有上一本了", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            scope.launch {
                val prevBook = findAdjacentBook(-1)
                if (prevBook != null) {
                    switchTargetName = prevBook.title
                    switchTargetPath = prevBook.filePath
                    showSwitchConfirm = true
                } else {
                    android.widget.Toast.makeText(ctx, "没有上一本了", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun confirmSwitch() {
        showSwitchConfirm = false
        if (switchTargetPath.isEmpty()) return
        if (tocItems.size > 1 && tocItems.any { it.subPath == switchTargetPath }) {
            // 切章节：重置navStack到书根+新章节，不嵌套
            val bookRoot = navStack.firstOrNull { it.startsWith("z:") } ?: switchTargetPath
            navStack = listOf(bookRoot, switchTargetPath)
            scope.launch {
                withContext(Dispatchers.IO) {
                    items = emptyList(); viewing = false; pageList = emptyList()
                    val chItems = try { engine.children(switchTargetPath) } catch (_: Exception) { emptyList() }
                    if (chItems.isEmpty()) {
                        pageList = try { engine.listPages(switchTargetPath) } catch (_: Exception) { emptyList() }
                        cur = 0; viewing = true
                    } else {
                        items = chItems
                    }
                }
            }
        } else {
            save()
            viewing = false; pageList = emptyList(); items = emptyList(); tocItems = emptyList()
            navStack = listOf(switchTargetPath)
            hasShownCompleteThisSession = false
            scope.launch {
                val newBook = withContext(Dispatchers.IO) { dao.getBookByPath(switchTargetPath) }
                if (newBook != null) {
                    book = newBook
                    val newBookId = newBook.id
                    config = com.mangareader.data.ReaderConfig.load(ctx, newBookId)
                    act?.requestedOrientation = prefs.getInt("orientation_$newBookId", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
                    val savedMode = prefs.getInt("read_mode_$newBookId", -1)
                    mode = if (savedMode in Mode.entries.indices) Mode.entries[savedMode] else when (config.scrollMode) {
                        com.mangareader.data.ReaderConfig.ScrollMode.VERTICAL -> Mode.VERT
                        com.mangareader.data.ReaderConfig.ScrollMode.HORIZONTAL_LEFT -> Mode.FLIP
                        com.mangareader.data.ReaderConfig.ScrollMode.HORIZONTAL_RIGHT -> Mode.RTL
                    }
                    // 同步：确保 scrollMode 和 read_mode 一致
                    val expectedScrollMode = when (mode) {
                        Mode.VERT, Mode.WEBTOON_GAP -> com.mangareader.data.ReaderConfig.ScrollMode.VERTICAL
                        Mode.RTL -> com.mangareader.data.ReaderConfig.ScrollMode.HORIZONTAL_RIGHT
                        else -> com.mangareader.data.ReaderConfig.ScrollMode.HORIZONTAL_LEFT
                    }
                    if (config.scrollMode != expectedScrollMode) {
                        config = config.copy(scrollMode = expectedScrollMode)
                        config.save(ctx, newBookId)
                    }
                }
                openFolder(switchTargetPath)
            }
        }
    }

    fun goBack() {
        restoreUi()
        act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        save()
        if (viewing && tocItems.isNotEmpty() && tocItems.size > 1) {
            // 有多个目录项 → 回到目录列表
            viewing = false; pageList = emptyList()
            items = tocItems
        } else if (navStack.size > 1 && navStack.last() != effectiveRootPath) {
            // 有上一级 → 回到上一级目录
            viewing = false; pageList = emptyList()
            navStack = navStack.dropLast(1)
            openFolder(navStack.last())
        } else {
            // 退出阅读器
            try { back() } catch (_: Exception) {}
        }
    }

    suspend fun markAsReadNow() {
        book?.let { b ->
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                dao.updateReadingProgress(b.id, pageList.size, now)
                dao.updateBook(b.copy(totalPages = pageList.size, readPages = pageList.size, lastReadAt = now))
            }
            delay(100)
        }
    }

    fun saveOrientation(orientation: Int) {
        act?.requestedOrientation = orientation
        prefs.edit().putInt("orientation_$bookId", orientation).apply()
    }

    fun toggleUi() {
        ui = !ui
        if (ui && viewing) {
            showSystemBars(act, config.isKeepScreenOn)
            val delayMs = if (config.isEinkMode) config.einkUiHideDelay else 10000L
            uiJob?.cancel()
            uiJob = scope.launch { delay(delayMs); if (ui) { ui = false; hideUi() } }
        } else if (!ui) {
            uiJob?.cancel()
            hideUi()
        }
    }

    fun restartAutoHide() {
        uiJob?.cancel()
        if (ui && viewing) {
            val delayMs = if (config.isEinkMode) config.einkUiHideDelay else 10000L
            uiJob = scope.launch { delay(delayMs); if (ui) { ui = false; hideUi() } }
        }
    }

    // Book loading - only runs when bookId changes
    LaunchedEffect(bookId) {
        try {
            // Step 1: All IO work on Dispatchers.IO (no state mutation)
            val loadedBook = withContext(Dispatchers.IO) { dao.getBookById(bookId) }
            book = loadedBook
            loadedBook?.let { b ->
                act?.requestedOrientation = prefs.getInt("orientation_$bookId", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
                val filePath = b.filePath
                val readPagesValue = b.readPages

                if (filePath.startsWith("d:")) {
                    navStack = listOf(filePath)
                    val childItems = withContext(Dispatchers.IO) { try { engine.children(filePath) } catch (_: Exception) { emptyList() } }
                    items = childItems
                    if (childItems.isNotEmpty()) tocItems = childItems
                    if (childItems.isEmpty()) {
                        val pages = withContext(Dispatchers.IO) { try { engine.listPages(filePath) } catch (_: Exception) { emptyList() } }
                        pageList = pages
                        cur = (readPagesValue - 1).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                        viewing = true
                    }
                } else if (filePath.startsWith("z:")) {
                    navStack = listOf(filePath)
                    var result = withContext(Dispatchers.IO) { try { engine.children(filePath) } catch (_: Exception) { emptyList() } }
                    if (result.isNotEmpty()) tocItems = result
                    var currentNavStack = navStack
                    while (result.size == 1 && result[0].dir && result[0].name.isNotEmpty()) {
                        val child = result[0]
                        val cp = currentNavStack.lastOrNull()
                        if (cp?.startsWith("z:") == true) {
                            val inner = cp.removePrefix("z:")
                            val sepIdx = inner.indexOf("//")
                            if (sepIdx >= 0) {
                                var archivePath = inner.substring(0, sepIdx)
                                if (!archivePath.startsWith("a:")) archivePath = "a:$archivePath"
                                val currentSubPath = inner.substring(sepIdx + 2)
                                val base = if (currentSubPath.isEmpty()) "" else if (currentSubPath.endsWith("/")) currentSubPath else "$currentSubPath/"
                                val newPath = "z:${archivePath}//${base}${child.name}/"
                                currentNavStack = currentNavStack + newPath
                                navStack = currentNavStack
                                result = withContext(Dispatchers.IO) { try { engine.children(newPath) } catch (_: Exception) { emptyList() } }
                                if (result.isNotEmpty()) tocItems = result
                                continue
                            }
                        }
                        break
                    }
                    effectiveRootPath = navStack.lastOrNull() ?: ""
                    items = result
                    if (items.isEmpty()) {
                        val pages = withContext(Dispatchers.IO) { try { engine.listPages(navStack.lastOrNull() ?: filePath) } catch (_: Exception) { emptyList() } }
                        pageList = pages
                        cur = (readPagesValue - 1).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                        viewing = true
                        if (b.totalPages == 0 && pages.isNotEmpty()) withContext(Dispatchers.IO) { dao.updateBook(b.copy(totalPages = pages.size)) }
                    }
                } else {
                    val pages = withContext(Dispatchers.IO) { try { engine.listPages(filePath) } catch (_: Exception) { emptyList() } }
                    pageList = pages
                    cur = (readPagesValue - 1).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                    viewing = true
                    if (b.totalPages == 0 && pages.isNotEmpty()) withContext(Dispatchers.IO) { dao.updateBook(b.copy(totalPages = pages.size)) }
                }
            }
            // Pre-decode first page SYNCHRONOUSLY before showing reader (instant open)
            if (pageList.isNotEmpty() && loadedBook?.filePath?.startsWith("a:") == true) {
                try {
                    val realPath = withContext(Dispatchers.IO) { engine.getRealFileForPath(loadedBook.filePath) }
                    if (realPath.isNotEmpty()) {
                        val archive = com.mangareader.native.NativeEngine.openArchive(realPath)
                        if (archive != null) {
                            try {
                                val screenMax = maxOf(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels)
                                val raw = com.mangareader.native.NativeEngine.readPageRaw(archive, cur)
                                if (raw != null) {
                                    val bmp = com.mangareader.native.NativeEngine.decodeRaw(raw, screenMax, screenMax)
                                    if (bmp != null) {
                                        try { com.mangareader.native.NativeEngine.cacheDecodedBitmap(archive, cur, bmp) } catch (_: Throwable) {}
                                    }
                                }
                                for (i in 1 until minOf(5, pageList.size)) {
                                    try {
                                        val raw2 = com.mangareader.native.NativeEngine.readPageRaw(archive, i)
                                        if (raw2 != null) {
                                            val bmp2 = com.mangareader.native.NativeEngine.decodeRaw(raw2, screenMax, screenMax)
                                            if (bmp2 != null) {
                                                try { com.mangareader.native.NativeEngine.cacheDecodedBitmap(archive, i, bmp2) } catch (_: Throwable) {}
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                            } finally { archive.close() }
                        }
                    }
                } catch (_: Exception) {}
            }
            loading = false; if (viewing) hideUi()
            if (loadedBook == null) { showErrorDialog = true; loading = false }
        } catch (e: Exception) { errorMsg = "加载失败: ${e.message}"; loading = false }
    }

    // Config refresh - only re-reads config when settings change, does NOT reload book
    LaunchedEffect(configVersion) {
        config = com.mangareader.data.ReaderConfig.load(ctx, bookId)
    }

    val bgColor = remember(configVersion) {
        // 读取全局背景色设置（无 bookId 前缀），默认白色
        val globalBgName = prefs.getString("readBackground", "WHITE") ?: "WHITE"
        val globalBg = try { com.mangareader.data.ReaderConfig.ReadBackground.valueOf(globalBgName) } catch (_: Exception) { com.mangareader.data.ReaderConfig.ReadBackground.DEFAULT }
        when (globalBg) {
            com.mangareader.data.ReaderConfig.ReadBackground.WHITE -> Color(0xFFE8E8E8)
            com.mangareader.data.ReaderConfig.ReadBackground.GRAY -> Color(0xFF202125)
            com.mangareader.data.ReaderConfig.ReadBackground.DARK -> Color(0xFF000000)
            com.mangareader.data.ReaderConfig.ReadBackground.LIGHT -> Color(0xFFF5F5F5)
            com.mangareader.data.ReaderConfig.ReadBackground.DEFAULT -> Color(0xFF111111)
        }
    }

    // Live-sync flip animation and side padding to pager/webtoon views
    LaunchedEffect(configVersion) {
        activePagerRef?.flipAnimationEnabled = config.isFlipAnimation
        activePagerRef?.imageMarginH = config.imageMarginH
        activePagerRef?.setTrimWhiteBorder(config.isTrimWhiteBorder, config.trimThreshold, config.trimWhiteRatio)
        activePagerRef?.pageBgColor = bgColor.toArgb()
        activeWebtoonRef?.imageMarginH = config.imageMarginH
        activeWebtoonRef?.gapEnabled = (mode == Mode.WEBTOON_GAP)
        activeWebtoonRef?.trimWhiteBorder = config.isTrimWhiteBorder
        activeWebtoonRef?.trimThreshold = config.trimThreshold
        activeWebtoonRef?.trimWhiteRatio = config.trimWhiteRatio
        activeWebtoonRef?.pageBgColor = bgColor.toArgb()
    }

    // Auto webtoon: detect tall/narrow images and switch to vertical mode
    LaunchedEffect(viewing, displayPages.size) {
        if (viewing && config.autoWebtoon && displayPages.size >= 3 && mode != Mode.VERT && mode != Mode.VERT_PAGER && mode != Mode.WEBTOON_GAP) {
            val userSetMode = prefs.getInt("read_mode_$bookId", -1)
            if (userSetMode >= 0) return@LaunchedEffect
            kotlinx.coroutines.delay(300)
            val treeStr = navStack.lastOrNull() ?: book?.filePath ?: ""
            var totalRatio = 0f
            var count = 0
            for (i in 0 until minOf(3, displayPages.size)) {
                try {
                    val bmp = withContext(Dispatchers.IO) { engine.loadPage(displayPages[i], treeStr) }
                    if (bmp != null) {
                        totalRatio += bmp.width.toFloat() / bmp.height.toFloat()
                        count++
                    }
                } catch (_: Exception) {}
            }
            if (count > 0 && totalRatio / count < 0.55f) {
                mode = Mode.VERT
            }
        }
    }

    var webtoonViewRef by remember { mutableStateOf<View?>(null) }
    var pagerViewRef by remember { mutableStateOf<View?>(null) }
    var autoScrollPaused by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (ui || !viewing) {
                val pageSubtitle = if (viewing && displayPages.isNotEmpty() && cur in displayPages.indices) {
                    val p = displayPages[cur]
                    val name = p.name.ifEmpty { p.ext.substringAfterLast("/").substringBeforeLast(".") }
                    "${cur + 1}/${displayPages.size}  $name"
                } else ""
                ReaderTopBar(
                    title = book?.title ?: "",
                    subtitle = pageSubtitle,
                    viewing = viewing,
                    bgColor = bgColor,
                    isFavorite = book?.isFavorite ?: false,
                    onBack = ::goBack,
                    onFavoriteClick = {
                        book?.let { b ->
                            val newFav = !(b.isFavorite)
                            scope.launch {
                                withContext(Dispatchers.IO) { dao.updateBook(b.copy(isFavorite = newFav)) }
                                book = b.copy(isFavorite = newFav)
                            }
                        }
                    }
                )
            }
        },
        containerColor = bgColor,
        bottomBar = {
            if (viewing && ui) {
                Column(Modifier.fillMaxWidth()) {
                    ReaderBottomBar(actions = buildList {
                        add(BottomBarAction(Icons.Default.Menu, "目录") { showToc = !showToc })
                        add(BottomBarAction(Icons.Default.Settings, "设置") { showSettings = true })
                    }, bgColor = bgColor)
                    // 进度条放在底部（横竖都一样）
                    ChapterNavigator(
                        isVertical = false,
                        currentPage = cur,
                        totalPages = displayPages.size,
                        onPageChange = { newCur -> cur = newCur },
                        onSliderFinished = { save(); restartAutoHide() },
                        onPreviousChapter = ::switchToPrevious,
                        onNextChapter = ::switchToNext,
                        isShowPageNum = config.isShowPageNum,
                        isRtl = isRtl,
                        bgColor = bgColor
                    )
                }
            }
        }
    ) { pad ->
        val shouldApplyPadding = !config.isImmersiveMode || ui || !viewing
        Box(Modifier.fillMaxSize().background(bgColor)) {
            // 内容区域（带 padding）
            Box(Modifier.fillMaxSize().then(if (shouldApplyPadding) Modifier.padding(pad) else Modifier)) {
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFF7C8CF8)) }
                    errorMsg != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(errorMsg!!) }
                    viewing && displayPages.isNotEmpty() -> {
                    val safeCur = cur.coerceIn(0, (displayPages.size - 1).coerceAtLeast(0))
                    if (safeCur != cur) cur = safeCur
                    val vertical = mode == Mode.VERT || mode == Mode.VERT_PAGER || mode == Mode.WEBTOON_GAP
                    Box(Modifier.fillMaxSize()) {
                        com.mangareader.native.NativeReaderScreen(
                            engine = engine,
                            treePath = navStack.lastOrNull() ?: book?.filePath ?: "",
                            pages = displayPages,
                            startPage = safeCur,
                            isVertical = vertical,
                            isRtl = isRtl,
                            grayscale = config.isGrayscale,
                            invertColors = config.isInvertColors,
                            centerGapType = config.centerGapType,
                            landscapeZoom = config.landscapeZoom,
                            trimWhiteBorder = config.isTrimWhiteBorder,
                            trimThreshold = config.trimThreshold,
                            trimWhiteRatio = config.trimWhiteRatio,
                            panNavigation = config.panNavigation,
                            pageLayout = when (config.pageLayout) {
                                com.mangareader.data.ReaderConfig.PageLayout.SINGLE -> 0
                                com.mangareader.data.ReaderConfig.PageLayout.DOUBLE -> 1
                                com.mangareader.data.ReaderConfig.PageLayout.DOUBLE_SHIFTED -> 2
                            },
                            pageBgColor = bgColor.toArgb(),
                            doublePageReverse = config.doublePageReverse,
                            doublePageSplit = config.doublePageSplit,
                            modifier = Modifier.fillMaxSize(),
                            onPageChanged = { newCur -> cur = newCur; save() },
                            onToggleUi = ::toggleUi,
                            onViewReady = { w, p ->
                                webtoonViewRef = w; pagerViewRef = p
                                activePagerRef = p as? com.mangareader.native.PagerReaderView
                                activeWebtoonRef = w as? com.mangareader.native.WebtoonReaderView
                                (p as? com.mangareader.native.PagerReaderView)?.let {
                                    it.flipAnimationEnabled = config.isFlipAnimation
                                    it.imageMarginH = config.imageMarginH
                                    it.onChapterEnd = { isLast ->
                                        if (config.chapterTransition && tocItems.size > 1) {
                                            chapterTransitionIsLast = isLast
                                            showChapterTransition = true
                                        }
                                    }
                                    it.onPageLongPress = { pageIdx ->
                                        if (pageIdx in displayPages.indices) {
                                            scope.launch {
                                                try {
                                                    val bmp = withContext(Dispatchers.IO) { engine.loadPage(displayPages[pageIdx], book?.filePath ?: "") }
                                                    if (bmp != null) {
                                                        val dir = java.io.File(ctx.getExternalFilesDir(null), "saved_pages"); dir.mkdirs()
                                                        val file = java.io.File(dir, "page_${System.currentTimeMillis()}.png")
                                                        java.io.FileOutputStream(file).use { out -> bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }
                                                        android.widget.Toast.makeText(ctx, "已保存到 ${file.name}", android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                } catch (e: Exception) { android.widget.Toast.makeText(ctx, "保存失败", android.widget.Toast.LENGTH_SHORT).show() }
                                            }
                                        }
                                    }
                                }
                                (w as? com.mangareader.native.WebtoonReaderView)?.let {
                                    it.imageMarginH = config.imageMarginH
                                }
                            }
                        )
                    }
                }
                items.isNotEmpty() -> ItemGrid(items, engine, bgColor, { openItem(it) }, Modifier.fillMaxSize().background(bgColor))
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("没有内容") }
                }
                // 翻页闪烁效果
                com.mangareader.ui.FlipFlashOverlay(
                    currentPage = cur,
                    enabled = config.isFlipFlashEnabled,
                    durationMs = config.flipFlashDuration,
                    intervalPages = config.flipFlashInterval,
                    modifier = Modifier.fillMaxSize()
                )
                // 导航区域提示 — 仅水平模式（单页从左到右/从右到左）显示
                if (showNavOverlay && viewing && !isVerticalMode) {
                    com.mangareader.ui.ReaderNavigationOverlay(
                        visible = true,
                        isVertical = false,
                        onDismiss = { showNavOverlay = false },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    if (showErrorDialog) {
        AlertDialog(onDismissRequest = { showErrorDialog = false }, title = { Text("源文件丢失") },
            text = { Text("该漫画的源文件已不存在或无法访问。\n\n是否从书架中移除？") },
            confirmButton = { TextButton(onClick = { scope.launch { book?.let { dao.deleteBook(it) } }; showErrorDialog = false; try { back() } catch (_: Exception) {} }) { Text("移除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showErrorDialog = false }) { Text("保留") } })
    }

    if (showComplete) {
        AlertDialog(onDismissRequest = { showComplete = false }, title = { Text("阅读完成") },
            text = { Text("已读完「${book?.title ?: ""}」的所有页面") },
            confirmButton = {
                TextButton(onClick = { scope.launch { markAsReadNow() }; showComplete = false }) { Text("标记已读") }
            },
            dismissButton = { TextButton(onClick = { showComplete = false }) { Text("继续浏览") } })
    }

    if (showSettings) {
        com.mangareader.ui.ReaderSettingsSheet(
            config = config,
            mode = mode,
            bookId = bookId,
            onConfigChange = { newConfig ->
                config = newConfig
                newConfig.save(ctx, bookId)
                configVersion++
            },
            onModeChange = { newMode ->
                mode = newMode
                prefs.edit().putInt("read_mode_$bookId", newMode.ordinal).apply()
                configVersion++
                // 切换模式时显示导航区域提示，3秒后自动消失
                showNavOverlay = true
                scope.launch { delay(3000); showNavOverlay = false }
            },
            onOrientationChange = { orientation ->
                saveOrientation(orientation)
                config = config.copy(orientation = orientation)
                config.save(ctx, bookId)
                configVersion++
            },
            onDismiss = { showSettings = false }
        )
    }

    if (showToc) {
        val displayItems = if (viewing && tocItems.isNotEmpty()) tocItems else items
        ReaderTocSheet(
            displayItems = displayItems,
            currentNavStack = navStack,
            viewing = viewing,
            onDismiss = { showToc = false },
            onChapterNavigate = { chapterPath ->
                // 保留书根路径 + 新章节路径，不丢弃整个 navStack
                val bookRoot = navStack.firstOrNull { it.startsWith("z:") } ?: chapterPath
                navStack = listOf(bookRoot, chapterPath)
                scope.launch {
                    withContext(Dispatchers.IO) {
                        items = emptyList(); viewing = false; pageList = emptyList()
                        val chItems = try { engine.children(chapterPath) } catch (_: Exception) { emptyList() }
                        if (chItems.isEmpty()) {
                            pageList = try { engine.listPages(chapterPath) } catch (_: Exception) { emptyList() }
                            cur = 0; viewing = true
                        } else {
                            items = chItems
                        }
                    }
                }
            },
            onItemOpen = { openItem(it) }
        )
    }

    // Chapter transition overlay
    ChapterTransitionOverlay(
        visible = showChapterTransition,
        isLast = chapterTransitionIsLast,
        onNextChapter = {
            showChapterTransition = false
            switchToNext()
        },
        onPreviousChapter = {
            showChapterTransition = false
            switchToPrevious()
        },
        modifier = Modifier.fillMaxSize()
    )

    // Switch confirmation dialog
    if (showSwitchConfirm) {
        AlertDialog(
            onDismissRequest = { showSwitchConfirm = false },
            title = { Text("切换确认") },
            text = { Text("确认切换到：$switchTargetName ?") },
            confirmButton = {
                TextButton(onClick = { confirmSwitch() }) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { showSwitchConfirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ItemGrid(items: List<ArchiveEngine.Item>, engine: ArchiveEngine, bgColor: Color, tap: (ArchiveEngine.Item) -> Unit, mod: Modifier) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("manga_reader", Context.MODE_PRIVATE) }
    var sortAsc by remember { mutableStateOf(true) }
    var cols by remember { mutableIntStateOf(prefs.getInt("item_grid_cols", 3)) }
    LaunchedEffect(Unit) {
        val saved = prefs.getInt("item_grid_cols", 3)
        if (saved != cols) cols = saved
    }
    val sorted = remember(items, sortAsc) {
        val base = items.sortedWith(compareByDescending<ArchiveEngine.Item> { it.dir }.thenComparing({ it.name }, NaturalComparator))
        if (sortAsc) base else base.reversed()
    }
    Column(mod) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${sorted.size} 项", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { sortAsc = !sortAsc }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Icon(Icons.Default.Sort, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (sortAsc) "正序" else "倒序", fontSize = 12.sp)
                }
                TextButton(onClick = { cols = when(cols) { 2 -> 3; 3 -> 4; else -> 2 }; prefs.edit().putInt("item_grid_cols", cols).apply() }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Icon(Icons.Default.GridView, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${cols}列", fontSize = 12.sp)
                }
            }
        }
        LazyVerticalGrid(columns = GridCells.Fixed(cols), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
            itemsIndexed(sorted, key = { i, e -> "${e.auth}|${e.doc}_$i" }) { _, e -> ItemCard(e, engine, bgColor, { tap(e) }) }
        }
    }
}

@Composable
private fun ItemCard(item: ArchiveEngine.Item, engine: ArchiveEngine, bgColor: Color, tap: () -> Unit) {
    var cover by remember(item.doc, item.subPath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.doc, item.subPath) {
        cover = null
        withContext(Dispatchers.IO) { try { cover = engine.loadCoverForItem(item) } catch (_: Exception) { cover = null } }
    }
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = tap).shadow(4.dp, RoundedCornerShape(12.dp))) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).background(bgColor.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
            if (cover != null) {
                val bitmap = cover!!
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawImage(bitmap.asImageBitmap())
                }
            }
            Box(Modifier.fillMaxWidth().height(36.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)))))
            val badgeColor = if (item.dir) Color(0xFFFFB74D) else Color(0xFF7C8CF8)
            Surface(Modifier.align(Alignment.TopEnd).padding(4.dp), RoundedCornerShape(6.dp), color = badgeColor.copy(alpha = 0.9f)) {
                Text(if (item.dir) "文件夹" else "压缩包", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, color = Color.White)
            }
        }
        Box(Modifier.fillMaxWidth().background(bgColor.copy(alpha = 0.9f)).padding(horizontal = 8.dp, vertical = 6.dp)) {
            val luminance = bgColor.red * 0.299f + bgColor.green * 0.587f + bgColor.blue * 0.114f
            val textColor = if (luminance > 0.5f) Color(0xFF333333) else Color(0xFFCCCCCC)
            Text(item.name, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = textColor)
        }
    }
}
