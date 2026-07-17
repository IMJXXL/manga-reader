package com.mangareader.native

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnLayout
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.viewpager2.widget.ViewPager2
import com.mangareader.viewer.ArchiveEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PagerReaderView(context: Context) : FrameLayout(context), ReaderViewDelegate {

    private val viewPager: ViewPager2
    private var pagerAdapter: PagerAdapter? = null

    private var engine: ArchiveEngine? = null
    private var treePath: String = ""
    private var pageList: List<ArchiveEngine.PageMeta> = emptyList()
    private var currentPage = 0
    private var isRtl = false
    @Volatile private var isScrolling = false
    private var doublePageMode = false
    private var shiftDoublePage = false
    private var useSSIV = true
    private var landscapeZoomEnabled = true
    private var trimWhiteBorder = false
    private var trimThreshold: Int = 220
    private var trimWhiteRatio: Float = 0.90f
    private var panNavigation = false
    private var pageLayoutMode = 0 // 0=SINGLE, 1=DOUBLE, 2=AUTO
    var pageBgColor: Int = android.graphics.Color.parseColor("#111111")
    var imageMarginH: Int = 0

    private var loadScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val decodeSemaphore = Semaphore(2)
    private val archiveMutex = Mutex()

    @Volatile private var nativeArchive: com.mangareader.native.NativeEngine.Archive? = null
    private var nativeArchivePath = ""

    // Zoom indicator state (driven by SSIV scale change detection)
    internal var zoomIndicatorTime = 0L
    private var zoomIndicatorAlpha = 0f
    private var lastReportedScale = 1f

    // SSIV zoom state for progress save/restore
    var currentZoomScale: Float = 1f
        private set
    var currentCenterX: Float = 0f
        private set
    var currentCenterY: Float = 0f
        private set

    /** Read zoom state from the currently visible SSIV */
    fun updateZoomStateFromSSIV() {
        if (!useSSIV) return
        try {
            val rv = viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return
            val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
            val pos = lm.findFirstVisibleItemPosition()
            if (pos == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return
            val holder = rv.findViewHolderForAdapterPosition(pos) as? PagerViewHolder ?: return
            val ssiv = holder.ssiv ?: return
            if (ssiv.visibility == View.VISIBLE && ssiv.scale > 1.01f) {
                currentZoomScale = ssiv.scale
                val center = ssiv.center ?: return
                currentCenterX = center.x
                currentCenterY = center.y
            } else {
                currentZoomScale = 1f
                currentCenterX = 0f
                currentCenterY = 0f
            }
        } catch (_: Exception) {}
    }

    private val bitmapCache = java.util.concurrent.ConcurrentHashMap<Int, Bitmap>()

    // Zoom indicator paint
    private val zoomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 42f; textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }
    private val zoomBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 0, 0, 0) }

    override var onPageChanged: ((Int) -> Unit)? = null
    override var onToggleUi: (() -> Unit)? = null
    var onChapterEnd: ((Boolean) -> Unit)? = null  // true=last page, false=first page
    var onPageLongPress: ((Int) -> Unit)? = null  // long press on page

    // Tap + long press detection via GestureDetector (TSY approach)
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (width == 0 || height == 0) return false
            val fx = e.x / width
            val fy = e.y / height
            val action = navigation.getAction(fx, fy)
            when (action) {
                NavigationAction.PREV -> { if (isRtl) nextPage() else prevPage() }
                NavigationAction.NEXT -> { if (isRtl) prevPage() else nextPage() }
                NavigationAction.MENU -> { onToggleUi?.invoke() }
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val pageIdx = if (isRtl) pageList.size - 1 - currentPage else currentPage
            onPageLongPress?.invoke(pageIdx)
            try { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } catch (_: Exception) {}
        }
    })
    private var navigation: ViewerNavigation = DefaultNavigation
    private var centerGapType: Int = 0
    private var doublePageReverseEnabled: Boolean = false
    private var doublePageSplitEnabled: Boolean = false

    private fun isCurrentPageZoomed(): Boolean {
        return try {
            val rv = viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return false
            val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return false
            val pos = lm.findFirstVisibleItemPosition()
            if (pos == -1 || pos == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return false
            val holder = rv.findViewHolderForAdapterPosition(pos) as? PagerViewHolder ?: return false
            val ssiv = holder.ssiv ?: return false
            ssiv.visibility == View.VISIBLE && ssiv.scale > 1.01f
        } catch (_: Exception) { false }
    }

    private val fadeOutRunnable = Runnable { invalidate() }

    init {
        viewPager = ViewPager2(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            offscreenPageLimit = 2
            isUserInputEnabled = true
            // 初始不设置 PageTransformer，等 setup 完成后再应用
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    // Convert adapter position to page index
                    val pageIdx = if (doublePageMode) {
                        if (shiftDoublePage && pageList.size > 1) {
                            if (position == 0) 0 else 1 + (position - 1) * 2
                        } else position * 2
                    } else position
                    val realPos = if (isRtl) pageList.size - 1 - pageIdx else pageIdx
                    com.mangareader.viewer.ArchiveEngine.d("PagerView", "onPageSelected pos=$position pageIdx=$pageIdx realPos=$realPos rtl=$isRtl double=$doublePageMode total=${pageList.size} curPage=$currentPage")
                    if (realPos in pageList.indices && realPos != currentPage) {
                        currentPage = realPos; onPageChanged?.invoke(currentPage)
                        if (currentPage == pageList.size - 1) onChapterEnd?.invoke(true)
                        if (currentPage == 0) onChapterEnd?.invoke(false)
                    }
                }
                override fun onPageScrollStateChanged(state: Int) {
                    isScrolling = state != ViewPager2.SCROLL_STATE_IDLE
                    if (!isScrolling) {
                        preloadAround(currentPage)
                    }
                }
            })
        }
        addView(viewPager)
        isFocusable = true; isFocusableInTouchMode = true
    }

    var flipAnimationEnabled = true
        set(value) {
            field = value
            viewPager.setPageTransformer(if (value) FlipPageTransformer() else NoOpTransformer())
        }

    private class NoOpTransformer : ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {}
    }

    private class FlipPageTransformer : ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            val absPos = kotlin.math.abs(position)
            page.apply {
                // 3D翻转效果
                cameraDistance = 12000f
                rotationY = position * -30f
                scaleX = 1f - absPos * 0.15f
                scaleY = 1f - absPos * 0.15f
                alpha = 1f - absPos * 0.4f
                translationX = -position * width * 0.1f
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        super.dispatchTouchEvent(ev)
        gestureDetector.onTouchEvent(ev)
        return true
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
    }

    override fun setup(engine: ArchiveEngine, treePath: String, pages: List<ArchiveEngine.PageMeta>, startPage: Int, vertical: Boolean, clickFlip: Int, rtl: Boolean) {
        this.navigation = DefaultNavigation
        this.useSSIV = true
        val pagesChanged = this.pageList.size != pages.size || this.treePath != treePath
        val rtlChanged = this.isRtl != rtl
        val engineChanged = this.engine != engine
        this.engine = engine; this.treePath = treePath; this.pageList = pages; this.isRtl = rtl
        if (pagesChanged || engineChanged) {
            // 页数或引擎变化：完全重建
            this.currentPage = startPage
            loadScope.cancel()
            loadScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            bitmapCache.clear()
            viewPager.setPageTransformer(NoOpTransformer())
            pagerAdapter = PagerAdapter()
            viewPager.adapter = pagerAdapter
            viewPager.setCurrentItem((if (isRtl) pages.size - 1 - startPage else startPage).coerceIn(0, pagerAdapter?.itemCount?.minus(1) ?: 0), false)
            if (flipAnimationEnabled) {
                viewPager.postDelayed({ viewPager.setPageTransformer(FlipPageTransformer()) }, 300)
            }
        } else if (rtlChanged) {
            // 仅RTL变化：保留缓存，只更新位置映射
            this.currentPage = startPage
            val pos = if (doublePageMode) startPage / 2 else (if (isRtl) pages.size - 1 - startPage else startPage)
            viewPager.setCurrentItem(pos.coerceIn(0, pagerAdapter?.itemCount?.minus(1) ?: 0), false)
        } else {
            this.currentPage = startPage
            val pos = if (doublePageMode) startPage / 2 else (if (isRtl) pages.size - 1 - startPage else startPage)
            viewPager.setCurrentItem(pos.coerceIn(0, pagerAdapter?.itemCount?.minus(1) ?: 0), false)
        }
        if (pagesChanged || engineChanged) preloadAround(startPage)
    }

    fun setNavigationMode(mode: Int) {
        this.navigation = DefaultNavigation
    }

    fun setCenterGapType(type: Int) {
        this.centerGapType = type
    }

    fun setLandscapeZoom(enabled: Boolean) {
        this.landscapeZoomEnabled = enabled
    }

    fun setTrimWhiteBorder(trim: Boolean, threshold: Int = trimThreshold, whiteRatio: Float = trimWhiteRatio) {
        if (trimWhiteBorder == trim && trimThreshold == threshold && trimWhiteRatio == whiteRatio) return
        this.trimWhiteBorder = trim
        this.trimThreshold = threshold
        this.trimWhiteRatio = whiteRatio
        bitmapCache.clear()
        pagerAdapter?.notifyDataSetChanged()
    }

    fun setPanNavigation(pan: Boolean) {
        this.panNavigation = pan
    }

    fun setDoublePageReverse(reverse: Boolean) {
        this.doublePageReverseEnabled = reverse
        pagerAdapter?.notifyDataSetChanged()
    }

    fun setDoublePageSplit(split: Boolean) {
        if (this.doublePageSplitEnabled == split) return
        this.doublePageSplitEnabled = split
        pagerAdapter?.notifyDataSetChanged()
    }

    fun setShiftDoublePage(enabled: Boolean) {
        if (shiftDoublePage == enabled) return
        shiftDoublePage = enabled
        bitmapCache.clear()
        pagerAdapter?.notifyDataSetChanged()
        val newPos = if (doublePageMode) {
            if (shiftDoublePage && pageList.size > 1) {
                if (currentPage == 0) 0 else 1 + (currentPage - 1) / 2
            } else currentPage / 2
        } else currentPage
        val finalPos = newPos.coerceIn(0, pagerAdapter?.itemCount?.minus(1) ?: 0)
        viewPager.post { viewPager.setCurrentItem(finalPos, false) }
    }

    /** 注入放大后的 bitmap 到缓存并刷新当前页显示 */
    fun injectUpscaledBitmap(pageIndex: Int, bitmap: Bitmap) {
        // 不主动回收旧bitmap，让GC自然处理（避免ViewHolder还在使用时被回收导致黑屏）
        bitmapCache[pageIndex] = bitmap
        val pos = if (isRtl) pageList.size - 1 - pageIndex else pageIndex
        if (pos >= 0 && pos < pageList.size) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                // 先清理旧 ViewHolder 状态，避免 OOM
                try {
                    val rv = viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return@post
                    val holder = rv.findViewHolderForAdapterPosition(pos) as? PagerViewHolder ?: return@post
                    holder.clear()
                } catch (_: Exception) {}
                pagerAdapter?.notifyItemChanged(pos, "loaded")
            }
        }
    }

    fun setPageLayout(layout: Int) {
        this.pageLayoutMode = layout
    }

    fun setDoublePageMode(enabled: Boolean) {
        if (doublePageMode == enabled) return
        doublePageMode = enabled
        bitmapCache.clear()
        pagerAdapter?.notifyDataSetChanged()
        val newPos = if (doublePageMode) currentPage / 2 else currentPage
        val finalPos = newPos.coerceIn(0, pagerAdapter?.itemCount?.minus(1) ?: 0)
        viewPager.post { viewPager.setCurrentItem(finalPos, false) }
    }

    fun setDoublePageModeAndShift(doubleEnabled: Boolean, shiftEnabled: Boolean) {
        if (doublePageMode == doubleEnabled && shiftDoublePage == shiftEnabled) return
        val savedPage = currentPage
        doublePageMode = doubleEnabled
        shiftDoublePage = shiftEnabled
        bitmapCache.clear()
        pagerAdapter?.notifyDataSetChanged()
        val newPos = if (doublePageMode) {
            if (shiftDoublePage && pageList.size > 1) {
                if (savedPage == 0) 0 else 1 + (savedPage - 1) / 2
            } else savedPage / 2
        } else savedPage
        val finalPos = newPos.coerceIn(0, pagerAdapter?.itemCount?.minus(1) ?: 0)
        viewPager.postDelayed({ viewPager.setCurrentItem(finalPos, false) }, 100)
    }

    override fun goToPage(page: Int) {
        if (page < 0 || page >= pageList.size) return
        val pos = if (doublePageMode) {
            if (shiftDoublePage && pageList.size > 1) {
                if (page == 0) 0 else 1 + (page - 1) / 2
            } else page / 2
        } else if (isRtl) pageList.size - 1 - page else page
        viewPager.setCurrentItem(pos.coerceIn(0, pagerAdapter?.itemCount?.minus(1) ?: 0), false)
        preloadAround(page)
    }

    fun nextPage() {
        // navigateToPan: 缩放时先平移到边缘再翻页
        if (panNavigation && tryPanIfZoomed(forward = !isRtl)) return
        if (doublePageMode) {
            if (shiftDoublePage && currentPage == 0) goToPage(1)
            else if (currentPage < pageList.size - 1) goToPage(currentPage + 2)
        } else {
            if (currentPage < pageList.size - 1) goToPage(currentPage + 1)
        }
    }
    fun prevPage() {
        // navigateToPan: 缩放时先平移到边缘再翻页
        if (panNavigation && tryPanIfZoomed(forward = isRtl)) return
        if (doublePageMode) {
            if (shiftDoublePage && currentPage <= 1) goToPage(0)
            else if (currentPage > 0) goToPage(currentPage - 2)
        } else {
            if (currentPage > 0) goToPage(currentPage - 1)
        }
    }

    /**
     * navigateToPan: 如果当前页SSIV已缩放且还有可平移空间，执行平移动画并返回true。
     * 返回false表示无法平移，应正常翻页。
     */
    private fun tryPanIfZoomed(forward: Boolean): Boolean {
        return try {
            val rv = viewPager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return false
            val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return false
            val pos = lm.findFirstVisibleItemPosition()
            if (pos == -1 || pos == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return false
            val holder = rv.findViewHolderForAdapterPosition(pos) as? PagerViewHolder ?: return false
            val ssiv = holder.ssiv ?: return false
            if (ssiv.visibility != View.VISIBLE || ssiv.scale <= ssiv.minScale + 0.01f) return false
            val remaining = android.graphics.RectF()
            ssiv.getPanRemaining(remaining)
            val canPan = if (forward) remaining.right > 1f else remaining.left > 1f
            if (canPan) {
                val targetX = ssiv.center!!.x + if (forward) ssiv.width.toFloat() / ssiv.scale else -ssiv.width.toFloat() / ssiv.scale
                val target = PointF(targetX, ssiv.center!!.y)
                ssiv.animateCenter(target)
                    ?.withDuration(250)
                    ?.withInterruptible(true)
                    ?.start()
                true
            } else false
        } catch (_: Exception) { false }
    }

    private fun loadPageAsync(idx: Int) {
        if (idx < 0 || idx >= pageList.size) return
        val cached = bitmapCache[idx]
        if (cached != null && !cached.isRecycled) return
        val meta = pageList[idx]
        loadScope.launch {
            ensureNativeArchive()
            val screenMax = maxOf(width, height).coerceAtLeast(800)
            var bmp = withContext(Dispatchers.IO) {
                var result: Bitmap? = null
                // 文件夹来源直接走 Java loadPage，跳过 native 引擎（native 对文件夹返回错误数据）
                if (meta.source == "dir") {
                    result = engine?.loadPage(pageList[idx], treePath)
                } else {
                    // 所有格式统一走 Java BitmapFactory（正确处理 CMYK/ICC 色彩空间）
                    result = engine?.loadPage(pageList[idx], treePath)
                }
                result
            }
            com.mangareader.viewer.ArchiveEngine.d("PagerView", "loaded idx=$idx bmp=${bmp?.width}x${bmp?.height} source=${meta.source}")
            // Apply trim white border
            if (bmp != null && !bmp.isRecycled && trimWhiteBorder && bmp.width > 10 && bmp.height > 10) {
                try {
                    val trim = com.mangareader.viewer.ImageTrimmer.trimWhiteBorders(bmp, threshold = trimThreshold, whiteRatio = trimWhiteRatio, factor = 0.15f)
                    bmp = com.mangareader.viewer.ImageTrimmer.applyTrim(bmp, trim)
                } catch (_: Exception) {}
            }
            if (bmp != null && !bmp.isRecycled) {
                bitmapCache[idx] = bmp
                withContext(Dispatchers.Main) {
                    // Convert page index to adapter position
                    val adapterPos = if (doublePageMode) {
                        if (shiftDoublePage && pageList.size > 1) {
                            if (idx == 0) 0 else 1 + (idx - 1) / 2
                        } else idx / 2
                    } else if (isRtl) pageList.size - 1 - idx else idx
                    if (adapterPos >= 0 && adapterPos < (pagerAdapter?.itemCount ?: 0)) pagerAdapter?.notifyItemChanged(adapterPos, "loaded")
                }
            } else {
                bitmapCache.remove(idx)
            }
        }
    }

    private fun preloadAround(centerIdx: Int) {
        if (isScrolling) return
        if (doublePageMode) {
            // In double page mode, load both pages of current spread and adjacent spreads
            val leftIdx = centerIdx - (centerIdx % 2)
            loadPageAsync(leftIdx)
            if (leftIdx + 1 < pageList.size) loadPageAsync(leftIdx + 1)
            if (leftIdx - 2 >= 0) { loadPageAsync(leftIdx - 2); loadPageAsync(leftIdx - 1) }
            if (leftIdx + 2 < pageList.size) { loadPageAsync(leftIdx + 2); if (leftIdx + 3 < pageList.size) loadPageAsync(leftIdx + 3) }
        } else {
            loadPageAsync(centerIdx)
            for (i in 1..2) {
                val fwd = centerIdx + i
                if (fwd < pageList.size) loadPageAsync(fwd)
                val bwd = centerIdx - i
                if (bwd >= 0) loadPageAsync(bwd)
            }
        }
    }

    private suspend fun ensureNativeArchive() {
        val eng = engine ?: return
        val path = withContext(Dispatchers.IO) { try { eng.getRealFileForPath(treePath) } catch (_: Exception) { "" } }
        if (path.isEmpty()) return
        if (path == nativeArchivePath && nativeArchive != null) return
        archiveMutex.withLock {
            if (path == nativeArchivePath && nativeArchive != null) return
            nativeArchive?.close()
            nativeArchive = withContext(Dispatchers.IO) { try { com.mangareader.native.NativeEngine.openArchive(path) } catch (_: Exception) { null } }
            nativeArchivePath = path
        }
    }

    override fun release() {
        loadScope.cancel()
        nativeArchive?.close()
        nativeArchive = null
        nativeArchivePath = ""
        bitmapCache.values.forEach { try { it.recycle() } catch (_: Exception) {} }
        bitmapCache.clear()
    }

    override fun trimMemory(level: Int) {
        when {
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                loadScope.cancel(); loadScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                bitmapCache.clear()
            }
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                loadScope.cancel(); loadScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                // Keep only current page in cache
                val keep = currentPage
                bitmapCache.keys.removeAll { it != keep }
            }
        }
    }

    private fun mergeBitmaps(left: Bitmap, right: Bitmap, gapPx: Int = 0): Bitmap {
        val w = left.width + right.width + gapPx
        val h = maxOf(left.height, right.height)
        val merged = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(merged)
        canvas.drawColor(pageBgColor)
        canvas.drawBitmap(left, 0f, (h - left.height) / 2f, null)
        canvas.drawBitmap(right, (left.width + gapPx).toFloat(), (h - right.height) / 2f, null)
        return merged
    }

    inner class PagerAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<PagerViewHolder>() {
        override fun getItemCount() = if (doublePageMode) {
            if (shiftDoublePage && pageList.size > 1) 1 + (pageList.size / 2) else (pageList.size + 1) / 2
        } else pageList.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagerViewHolder {
            val container = android.widget.FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(pageBgColor)
                val marginPx = (imageMarginH * parent.context.resources.displayMetrics.density).toInt()
                setPadding(marginPx, 0, marginPx, 0)
            }
            val iv = ImageView(parent.context).apply {
                id = android.R.id.content
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.VISIBLE
            }
            val ssiv = PagerSSIV(parent.context).apply {
                id = com.mangareader.R.id.ssiv_pager
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                isZoomEnabled = true
                isPanEnabled = true
                isQuickScaleEnabled = true
                setMaxScale(5f)
                setMaxTileSize(512)
                setMinimumTileDpi(160)
                setMinimumScaleType(1) // SCALE_TYPE_FIT_CENTER
                setDoubleTapZoomDuration(300)
                visibility = View.GONE
            }
            container.addView(iv)
            container.addView(ssiv)
            return PagerViewHolder(container)
        }

        override fun onBindViewHolder(holder: PagerViewHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains("loaded")) {
                onBindViewHolder(holder, position)
                return
            }
            onBindViewHolder(holder, position)
        }

        override fun onBindViewHolder(holder: PagerViewHolder, position: Int) {
            // Apply margin and background color immediately so settings take effect
            val container = holder.itemView as android.widget.FrameLayout
            val marginPx = (imageMarginH * container.context.resources.displayMetrics.density).toInt()
            container.setPadding(marginPx, 0, marginPx, 0)
            container.setBackgroundColor(pageBgColor)

            val debugLabel = container.findViewById<android.widget.TextView>(0x7F000001)
            debugLabel?.visibility = View.GONE

            if (doublePageMode) {
                // Shift mode: position 0 shows first page alone, position 1+ shows merged pairs starting from page 1
                val leftIdx = if (shiftDoublePage && pageList.size > 1) {
                    if (position == 0) 0 else 1 + (position - 1) * 2
                } else {
                    if (isRtl) pageList.size - 1 - position * 2 else position * 2
                }

                // In shift mode, position 0 is a single page
                if (shiftDoublePage && position == 0 && pageList.size > 1) {
                    val bmp = bitmapCache[leftIdx]
                    if (bmp != null && !bmp.isRecycled) {
                        val needFitWidth = bmp.width > holder.itemView.width
                        holder.setImage(bmp, useSSIV, landscapeZoomEnabled, fitWidth = needFitWidth)
                    } else {
                        bitmapCache.remove(leftIdx)
                        holder.clear()
                        if (!isScrolling) loadPageAsync(leftIdx)
                    }
                    return
                }

                val rightIdx = if (isRtl) leftIdx - 1 else leftIdx + 1
                val gapPx = if (centerGapType in 1..3) (16 * holder.itemView.context.resources.displayMetrics.density).toInt() else 0

                // wide image split: 全图加载到SSIV，视口定位显示左/右半边
                if (doublePageSplitEnabled) {
                    val leftBmp = bitmapCache[leftIdx]
                    if (leftBmp != null && !leftBmp.isRecycled && leftBmp.width.toFloat() / leftBmp.height > 1.5f) {
                        val showRight = doublePageReverseEnabled
                        holder.setImageSplit(leftBmp, showRight, useSSIV, landscapeZoomEnabled)
                        return
                    }
                }

                // Normal double-page: merge two pages
                val leftBmp = bitmapCache[leftIdx]
                val rightBmp = if (rightIdx in 0 until pageList.size) bitmapCache[rightIdx] else null
                if (leftBmp != null && !leftBmp.isRecycled) {
                    try {
                        val merged = if (rightBmp != null && !rightBmp.isRecycled) {
                            if (doublePageReverseEnabled) mergeBitmaps(rightBmp, leftBmp, gapPx)
                            else mergeBitmaps(leftBmp, rightBmp, gapPx)
                        } else leftBmp
                        holder.setImage(merged, useSSIV, landscapeZoomEnabled, fitWidth = true)
                    } catch (_: OutOfMemoryError) {
                        holder.setImage(leftBmp, useSSIV, landscapeZoomEnabled, fitWidth = true)
                    }
                    return
                }
                // Not loaded yet: load both
                bitmapCache.remove(leftIdx)
                bitmapCache.remove(rightIdx)
                holder.clear()
                if (!isScrolling) {
                    loadPageAsync(leftIdx)
                    if (rightIdx < pageList.size) loadPageAsync(rightIdx)
                }
            } else {
                val realIdx = if (isRtl) pageList.size - 1 - position else position
                val bmp = bitmapCache[realIdx]
                if (bmp != null && !bmp.isRecycled) {
                    // 双页裁剪：全图加载到SSIV，视口定位显示左/右半边
                    if (doublePageSplitEnabled && bmp.width > bmp.height) {
                        val showRight = doublePageReverseEnabled
                        holder.setImageSplit(bmp, showRight, useSSIV, landscapeZoomEnabled)
                    } else {
                        val needFitWidth = bmp.width > holder.itemView.width
                        holder.setImage(bmp, useSSIV, landscapeZoomEnabled, fitWidth = needFitWidth)
                    }
                    return
                }
                // bitmap已回收或不存在，清除缓存重新加载
                bitmapCache.remove(realIdx)
                holder.clear()
                if (!isScrolling) loadPageAsync(realIdx)
            }
        }
        override fun onViewRecycled(holder: PagerViewHolder) {
            holder.clear()
            holder.itemView.findViewById<ImageView>(android.R.id.content)?.setImageDrawable(null)
        }
    }

    class PagerViewHolder(itemView: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {
        private var heldBitmap: Bitmap? = null
        internal var ssiv: PagerSSIV? = itemView.findViewById(com.mangareader.R.id.ssiv_pager)
        private val iv: ImageView? = itemView.findViewById(android.R.id.content)

        private fun createSsiv(): PagerSSIV {
            return PagerSSIV(itemView.context).apply {
                id = com.mangareader.R.id.ssiv_pager
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                isZoomEnabled = true
                isPanEnabled = true
                isQuickScaleEnabled = true
                setMaxScale(5f)
                setMaxTileSize(512)
                setMinimumTileDpi(160)
                setMinimumScaleType(1) // SCALE_TYPE_FIT_CENTER
                setDoubleTapZoomDuration(300)
                visibility = View.GONE
            }
        }

        fun setImage(bmp: Bitmap, useSsiv: Boolean, landscapeZoom: Boolean = true, fitWidth: Boolean = false) {
            if (bmp.isRecycled) return
            heldBitmap = bmp
            if (useSsiv) {
                val container = itemView as android.widget.FrameLayout
                // 移除旧SSIV，创建全新实例（模仿TSY，避免复用导致的渲染状态残留）
                ssiv?.let { container.removeView(it) }
                val newSsiv = createSsiv()
                container.addView(newSsiv)
                ssiv = newSsiv

                newSsiv.visibility = View.VISIBLE
                iv?.visibility = View.GONE
                // 先设listener，再setImage，确保onReady能捕获到
                newSsiv.setOnImageEventListener(object : com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.DefaultOnImageEventListener() {
                    override fun onReady() {
                        newSsiv.setOnImageEventListener(null)
                        applyScale(newSsiv, bmp, fitWidth, landscapeZoom)
                    }
                    override fun onImageLoadError(e: Exception) {
                        newSsiv.setOnImageEventListener(null)
                    }
                })
                newSsiv.setImage(com.davemorrissey.labs.subscaleview.ImageSource.bitmap(bmp))
            } else {
                // 不使用 SSIV：用 ImageView FIT_CENTER
                ssiv?.visibility = View.GONE
                iv?.visibility = View.VISIBLE
                iv?.scaleType = ImageView.ScaleType.FIT_CENTER
                try { iv?.setImageBitmap(bmp) } catch (_: Exception) {}
            }
        }

        /**
         * 裁剪双页视口定位：全图加载到SSIV，用视口显示左/右半边。
         * 用户可以通过navigateToPan平移到另一半。
         */
        fun setImageSplit(bmp: Bitmap, showRight: Boolean, useSsiv: Boolean, landscapeZoom: Boolean) {
            if (bmp.isRecycled) return
            heldBitmap = bmp
            if (useSsiv) {
                val container = itemView as android.widget.FrameLayout
                ssiv?.let { container.removeView(it) }
                val newSsiv = createSsiv()
                container.addView(newSsiv)
                ssiv = newSsiv
                newSsiv.visibility = View.VISIBLE
                iv?.visibility = View.GONE
                newSsiv.setOnImageEventListener(object : com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.DefaultOnImageEventListener() {
                    override fun onReady() {
                        newSsiv.setOnImageEventListener(null)
                        applySplitScale(newSsiv, bmp, showRight)
                    }
                    override fun onImageLoadError(e: Exception) {
                        newSsiv.setOnImageEventListener(null)
                    }
                })
                newSsiv.setImage(com.davemorrissey.labs.subscaleview.ImageSource.bitmap(bmp))
            } else {
                val halfW = bmp.width / 2
                val srcX = if (showRight) halfW else 0
                val splitBmp = Bitmap.createBitmap(bmp, srcX, 0, halfW, bmp.height)
                ssiv?.visibility = View.GONE
                iv?.visibility = View.VISIBLE
                iv?.scaleType = ImageView.ScaleType.FIT_CENTER
                try { iv?.setImageBitmap(splitBmp) } catch (_: Exception) {}
            }
        }

        private fun applySplitScale(ssiv: PagerSSIV, bmp: Bitmap, showRight: Boolean) {
            if (!ssiv.isReady || ssiv.width <= 0) {
                ssiv.post { applySplitScale(ssiv, bmp, showRight) }
                return
            }
            val targetScale = ssiv.width.toFloat() / (bmp.width / 2f)
            val centerX = if (showRight) bmp.width * 3f / 4f else bmp.width / 4f
            val centerY = bmp.height / 2f
            ssiv.setScaleAndCenter(targetScale, android.graphics.PointF(centerX, centerY))
        }

        private fun applyScale(ssiv: PagerSSIV, bmp: Bitmap, fitWidth: Boolean, landscapeZoom: Boolean) {
            if (!ssiv.isReady || ssiv.width <= 0) {
                ssiv.post { applyScale(ssiv, bmp, fitWidth, landscapeZoom) }
                return
            }
            // 等 layout 完成后再设 scale（避免 fitToLower 在 layout 时覆盖）
            ssiv.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    ssiv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    val w = ssiv.width.toFloat()
                    if (w > 0 && bmp.width > 0) {
                        val scale = w / bmp.width
                        ssiv.setScaleAndCenter(scale, android.graphics.PointF(bmp.width / 2f, bmp.height / 2f))
                    }
                }
            })
            // landscapeZoom：宽图自动放大适配高度
            if (landscapeZoom && bmp.width > bmp.height) {
                ssiv.handler?.postDelayed({
                    if (ssiv.isReady) {
                        val targetScale = ssiv.height.toFloat() / bmp.height.toFloat()
                        val center = ssiv.center ?: android.graphics.PointF(bmp.width / 2f, bmp.height / 2f)
                        ssiv.animateScaleAndCenter(targetScale, center)
                            ?.withDuration(500)?.withInterruptible(true)?.start()
                    }
                }, 1000)
            }
        }
        fun clear() {
            heldBitmap = null
            iv?.setImageBitmap(null)
            try { ssiv?.resetScaleAndCenter() } catch (_: Exception) {}
            ssiv?.visibility = View.GONE
            iv?.visibility = View.VISIBLE
        }
    }
}
