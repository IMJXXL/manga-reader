package com.mangareader.native

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.animation.doOnEnd
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mangareader.viewer.ArchiveEngine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.abs

class WebtoonReaderView(context: Context) : FrameLayout(context), ReaderViewDelegate {

    val recycler = WebtoonRv(context)
    private val webtoonFrame = WebtoonFrame(context).apply {
        addView(recycler, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
    private var engine: ArchiveEngine? = null
    private var treePath = ""
    private var pageList: List<ArchiveEngine.PageMeta> = emptyList()
    private var currentPage = 0
    private val loadScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val archiveMutex = Mutex()
    private var archive: com.mangareader.native.NativeEngine.Archive? = null
    private var archivePath = ""
    @Volatile private var scrolling = false
    @Volatile private var setupScrollPending = false
    private var adapter: PageAdapter? = null
    private val bitmapCache = java.util.LinkedHashMap<Int, Bitmap>(16, 0.75f, true)
    var imageMarginH: Int = 0
        set(value) {
            field = value
            val marginPx = (value * context.resources.displayMetrics.density).toInt()
            webtoonFrame.setPadding(marginPx, 0, marginPx, 0)
        }
    var trimWhiteBorder: Boolean = false
    var trimThreshold: Int = 220
    var trimWhiteRatio: Float = 0.90f
    var rotationAdapt: Boolean = false
    var rotationReverse: Boolean = false
    var pageBgColor: Int = android.graphics.Color.parseColor("#E8E8E8")

    /** 应用裁剪白边和旋转适应处理 */
    private fun processBitmap(bmp: Bitmap?): Bitmap? {
        if (bmp == null || bmp.isRecycled || bmp.width <= 10 || bmp.height <= 10) return bmp
        var result = bmp
        if (trimWhiteBorder) {
            try {
                val trim = com.mangareader.viewer.ImageTrimmer.trimWhiteBorders(result!!, threshold = trimThreshold, whiteRatio = trimWhiteRatio, factor = 0.15f)
                result = com.mangareader.viewer.ImageTrimmer.applyTrim(result!!, trim)
            } catch (_: Exception) {}
        }
        if (result != null && !result.isRecycled && rotationAdapt) {
            if (result!!.width > result.height) {
                val matrix = android.graphics.Matrix()
                if (rotationReverse) matrix.postRotate(-90f) else matrix.postRotate(90f)
                result = Bitmap.createBitmap(result!!, 0, 0, result!!.width, result!!.height, matrix, true)
            }
        }
        return result
    }
    var gapEnabled: Boolean = false
        set(value) {
            field = value
            val gapPx = if (value) (15 * context.resources.displayMetrics.density).toInt() else 0
            for (i in 0 until (recycler.adapter?.itemCount ?: 0)) {
                val vh = recycler.findViewHolderForAdapterPosition(i) ?: continue
                val lp = vh.itemView.layoutParams as? RecyclerView.LayoutParams ?: continue
                lp.bottomMargin = gapPx
                vh.itemView.layoutParams = lp
            }
        }

    override var onPageChanged: ((Int) -> Unit)? = null
    override var onToggleUi: (() -> Unit)? = null

    init {
        addView(webtoonFrame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        adapter = PageAdapter()
        recycler.layoutManager = LinearLayoutManager(context).apply { isItemPrefetchEnabled = false }
        recycler.itemAnimator = null
        recycler.adapter = adapter
        recycler.tapListener = { onToggleUi?.invoke() }
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                scrolling = state != RecyclerView.SCROLL_STATE_IDLE
                if (!scrolling) {
                    setupScrollPending = false
                    updatePage()
                    loadScope.coroutineContext.cancelChildren()
                    preloadAround(currentPage, jump = false)
                }
            }
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (setupScrollPending) return
                val prevPage = currentPage
                updatePage()
                if (currentPage != prevPage) {
                    preloadAround(currentPage, jump = false)
                }
            }
        })
    }

    private fun updatePage() {
        if (pageList.isEmpty()) return
        val lm = recycler.layoutManager as? LinearLayoutManager ?: return
        val f = lm.findFirstVisibleItemPosition()
        val l = lm.findLastVisibleItemPosition()
        if (f == RecyclerView.NO_POSITION) return
        val cy = height / 2; var best = f; var bd = Int.MAX_VALUE
        for (i in f..l) {
            val v = lm.findViewByPosition(i) ?: continue
            val d = abs((v.top + v.bottom) / 2 - cy)
            if (d < bd) { bd = d; best = i }
        }
        if (best in pageList.indices && best != currentPage) {
            currentPage = best; onPageChanged?.invoke(currentPage)
        }
    }

    override fun setup(engine: ArchiveEngine, treePath: String, pages: List<ArchiveEngine.PageMeta>, startPage: Int, vertical: Boolean, clickFlip: Int, rtl: Boolean) {
        val changed = this.pageList.size != pages.size || this.treePath != treePath
        this.engine = engine; this.treePath = treePath; this.pageList = pages; this.currentPage = startPage
        if (changed) {
            loadScope.coroutineContext.cancelChildren()
            synchronized(bitmapCache) { bitmapCache.clear() }
            adapter?.notifyDataSetChanged()
        }
        recycler.post { recycler.scrollToPosition(startPage); preloadAround(startPage) }
    }

    override fun goToPage(p: Int) {
        if (p < 0 || p >= pageList.size) return
        val distance = kotlin.math.abs(p - currentPage)
        currentPage = p
        recycler.scrollToPosition(p)
        preloadAround(p, jump = distance > 5)
    }
    fun nextPage() { if (currentPage < pageList.size - 1) goToPage(currentPage + 1) }
    fun prevPage() { if (currentPage > 0) goToPage(currentPage - 1) }

    fun injectUpscaledBitmap(pageIndex: Int, bitmap: Bitmap) {
        synchronized(bitmapCache) { bitmapCache[pageIndex] = bitmap }
        recycler.post {
            adapter?.notifyItemChanged(pageIndex, "ld")
        }
    }

    private fun loadPageAsync(idx: Int, onResult: ((Bitmap?) -> Unit)? = null) {
        if (idx < 0 || idx >= pageList.size) { onResult?.invoke(null); return }
        synchronized(bitmapCache) { bitmapCache[idx]?.let { onResult?.invoke(it); return } }
        val meta = pageList[idx]
        if (meta.source == "pdf" && treePath.isNotEmpty()) {
            try {
                val sharedKey = engine?.getPdfSharedKey(treePath, meta.index)
                if (sharedKey != null) {
                    engine?.getCachedBitmap(sharedKey)?.let { bmp ->
                        synchronized(bitmapCache) { bitmapCache[idx] = bmp }
                        onResult?.invoke(bmp)
                        return
                    }
                }
            } catch (_: Exception) {}
        }
        val sm = maxOf(width, height).coerceAtLeast(1)
        loadScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                var result: Bitmap? = null
                if (meta.source in listOf("mobi", "azw", "azw3", "pdb", "prc")) {
                    result = loadMobiPageAsync(meta)
                }
                // 所有格式统一走 Java BitmapFactory（正确处理 CMYK/ICC 色彩空间）
                if (result == null) {
                    result = engine?.loadPage(pageList[idx], treePath)
                }
                result
            }
            val processed = processBitmap(bmp)
            if (processed != null) {
                synchronized(bitmapCache) { bitmapCache[idx] = processed }
                recycler.post { adapter?.notifyItemChanged(idx, "ld") }
            }
            onResult?.invoke(processed)
        }
    }

    private fun loadMobiPageAsync(meta: ArchiveEngine.PageMeta): Bitmap? {
        val e = engine ?: return null
        try {
            val p = e.parsePathForMobi(treePath) ?: return null
            val tree = android.net.Uri.parse("content://${p.first}/tree/${android.net.Uri.encode(p.second)}")
            val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, p.third)
            val cacheKey = uri.toString()
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val fd = pfd.detachFd()
            try {
                com.mangareader.native.NativeEngine.openMobiWithFd(cacheKey, fd)
            } catch (_: Exception) {}
            val rawData = com.mangareader.native.NativeEngine.readMobiPage(cacheKey, meta.localIdx)
            pfd.close()
            if (rawData != null) {
                val sm = maxOf(width, height).coerceAtLeast(1)
                return com.mangareader.native.NativeEngine.decodeRaw(rawData, sm, sm)
                    ?: android.graphics.BitmapFactory.decodeByteArray(rawData, 0, rawData.size)
            }
        } catch (_: Exception) {}
        return null
    }

    private fun preloadAround(center: Int, jump: Boolean = false) {
        val lm = recycler.layoutManager as? LinearLayoutManager ?: return
        val firstVisible = lm.findFirstVisibleItemPosition()
        val lastVisible = lm.findLastVisibleItemPosition()
        val forwardPages = if (jump) 1 else 3
        val backwardPages = if (jump) 1 else 3
        val loadList = mutableListOf<Int>()
        val currentCached = synchronized(bitmapCache) { bitmapCache[currentPage] }
        if (currentCached == null) loadList.add(currentPage)
        for (i in firstVisible..lastVisible) {
            if (i != currentPage && i in pageList.indices) loadList.add(i)
        }
        for (i in 1..forwardPages) {
            val fwd = currentPage + i
            if (fwd in pageList.indices && fwd !in loadList) loadList.add(fwd)
        }
        for (i in 1..backwardPages) {
            val bwd = currentPage - i
            if (bwd in pageList.indices && bwd !in loadList) loadList.add(bwd)
        }
        loadScope.launch {
            for (idx in loadList) {
                val cached = synchronized(bitmapCache) { bitmapCache[idx] }
                if (cached != null) continue
                val bmp = withContext(Dispatchers.IO) {
                    var result: Bitmap? = null
                    val meta = pageList[idx]
                    try {
                        if (meta.source in listOf("mobi", "azw", "azw3", "pdb", "prc")) {
                            result = loadMobiPageAsync(meta)
                        }
                        // 所有格式统一走 Java BitmapFactory（正确处理 CMYK/ICC 色彩空间）
                        if (result == null) {
                            result = engine?.loadPage(pageList[idx], treePath)
                        }
                    } catch (_: Exception) {}
                    result
                }
                if (bmp != null) {
                    val processed = processBitmap(bmp)
                    synchronized(bitmapCache) { bitmapCache[idx] = processed ?: bmp }
                    recycler.post { adapter?.notifyItemChanged(idx, "ld") }
                }
            }
        }
    }

    private suspend fun ensureArchive() {
        val e = engine ?: return
        val p = withContext(Dispatchers.IO) { try { e.getRealFileForPath(treePath) } catch (_: Exception) { "" } }
        if (p.isEmpty()) return
        if (p == archivePath && archive != null) return
        archiveMutex.withLock {
            if (p == archivePath && archive != null) return
            archive?.close()
            archive = withContext(Dispatchers.IO) { try { com.mangareader.native.NativeEngine.openArchive(p) } catch (_: Exception) { null } }
            archivePath = p
        }
    }

    override fun release() {
        loadScope.coroutineContext.cancelChildren()
        archive?.close(); archive = null; archivePath = ""
        try {
            val e = engine ?: return
            val p = e.getRealFileForPath(treePath)
            if (p.isNotEmpty()) {
                val tree = android.net.Uri.parse("content://${treePath.substringAfter("a:").substringBefore("|")}/tree/${android.net.Uri.encode(treePath.substringAfter("|").substringBefore("|"))}")
                val docId = treePath.substringAfterLast("|").substringBeforeLast(".")
                val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                com.mangareader.native.NativeEngine.pdfClose(uri.toString())
            }
        } catch (_: Exception) {}
    }

    override fun trimMemory(level: Int) {
        when {
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                loadScope.coroutineContext.cancelChildren()
                synchronized(bitmapCache) { bitmapCache.clear() }
            }
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                loadScope.coroutineContext.cancelChildren()
                synchronized(bitmapCache) {
                    val keep = currentPage
                    bitmapCache.keys.retainAll { it == keep }
                }
            }
        }
    }

    inner class PageAdapter : RecyclerView.Adapter<PageVH>() {
        override fun getItemCount() = pageList.size
        override fun onCreateViewHolder(parent: android.view.ViewGroup, vt: Int): PageVH {
            val gapPx = if (gapEnabled) (15 * parent.context.resources.displayMetrics.density).toInt() else 0
            val frame = android.widget.FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = gapPx
                }
            }
            val iv = android.widget.ImageView(parent.context).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(pageBgColor)
                adjustViewBounds = true
            }
            frame.addView(iv, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
            return PageVH(frame, iv)
        }
        override fun onBindViewHolder(h: PageVH, pos: Int, payloads: MutableList<Any>) {
            if (payloads.contains("ld")) {
                val cached = synchronized(bitmapCache) { bitmapCache[pos] }
                if (cached != null && !cached.isRecycled && h.isBound) {
                    h.set(cached)
                }
                return
            }
            onBindViewHolder(h, pos)
        }
        override fun onBindViewHolder(h: PageVH, pos: Int) {
            h.hasImage = false
            h.isBound = true
            h.boundPosition = pos
            val cached = synchronized(bitmapCache) { bitmapCache[pos] }
            if (cached != null && !cached.isRecycled && cached.width > 1) {
                h.set(cached)
            } else {
                loadPageAsync(pos)
            }
        }
        override fun onViewRecycled(h: PageVH) { h.clear() }
    }

    class PageVH(frame: android.widget.FrameLayout, val iv: android.widget.ImageView) : RecyclerView.ViewHolder(frame) {
        private var heldBitmap: Bitmap? = null
        var hasImage = false
        var isBound = true
        var boundPosition = -1
        fun set(b: Bitmap) {
            heldBitmap = b
            iv.setImageBitmap(b)
            hasImage = true
        }
        fun clear() { heldBitmap = null; hasImage = false; isBound = false; boundPosition = -1; iv.setImageBitmap(null) }
    }
}

// ==================== TSY 条漫缩放架构 ====================

/** 自定义 GestureDetector，处理 long tap 确认（避免与 quick-scale 冲突） */
internal open class GestureDetectorWithLongTap(
    context: Context,
    private val listener: Listener,
) : GestureDetector(context, listener) {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longTapTime = ViewConfiguration.getLongPressTimeout().toLong()
    private val doubleTapTime = ViewConfiguration.getDoubleTapTimeout().toLong()
    private var downX = 0f; private var downY = 0f; private var lastUp = 0L
    private var lastDownEvent: MotionEvent? = null
    private val longTapFn = Runnable { listener.onLongTapConfirmed(lastDownEvent!!) }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastDownEvent?.recycle(); lastDownEvent = MotionEvent.obtain(ev)
                if (ev.downTime - lastUp > doubleTapTime) {
                    downX = ev.x; downY = ev.y
                    handler.postDelayed(longTapFn, longTapTime)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) handler.removeCallbacks(longTapFn)
            }
            MotionEvent.ACTION_UP -> { lastUp = ev.eventTime; handler.removeCallbacks(longTapFn) }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> handler.removeCallbacks(longTapFn)
        }
        return super.onTouchEvent(ev)
    }

    internal open class Listener : SimpleOnGestureListener() {
        open fun onLongTapConfirmed(ev: MotionEvent) {}
    }
}

/** 手势检测在未缩放父容器上（TSY WebtoonFrame） */
private class WebtoonFrame(context: Context) : android.widget.FrameLayout(context) {
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { (getChildAt(0) as? WebtoonRv)?.onScaleBegin(); return true }
        override fun onScale(d: ScaleGestureDetector): Boolean { (getChildAt(0) as? WebtoonRv)?.onScale(d.scaleFactor); return true }
        override fun onScaleEnd(d: ScaleGestureDetector) { (getChildAt(0) as? WebtoonRv)?.onScaleEnd() }
    })
    private val flingDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            return (getChildAt(0) as? WebtoonRv)?.zoomFling(vx.toInt(), vy.toInt()) ?: false
        }
    })

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        flingDetector.onTouchEvent(ev)
        // Clamp to RecyclerView bounds
        val rv = getChildAt(0) as? WebtoonRv ?: return super.dispatchTouchEvent(ev)
        val rect = android.graphics.Rect()
        rv.getHitRect(rect); rect.inset(1, 1)
        if (rect.right >= rect.left && rect.bottom >= rect.top) {
            ev.setLocation(ev.x.coerceIn(rect.left.toFloat(), rect.right.toFloat()), ev.y.coerceIn(rect.top.toFloat(), rect.bottom.toFloat()))
        }
        return super.dispatchTouchEvent(ev)
    }
}

/** TSY WebtoonRecyclerView：缩放状态 + zoom drag + double-tap */
class WebtoonRv(ctx: Context) : RecyclerView(ctx) {
    var tapListener: ((MotionEvent) -> Unit)? = null
    var longTapListener: ((MotionEvent) -> Boolean)? = null
    var doubleTapZoom = true

    private var currentScale = DEFAULT_RATE
    private var isZooming = false
    private var halfWidth = 0; private var halfHeight = 0
    var originalHeight = 0; private set
    private var heightSet = false
    private var atFirstPosition = false; private var atLastPosition = false
    private val minRate get() = DEFAULT_RATE

    private val listener = GestureListener()
    private val detector = Detector()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        halfWidth = MeasureSpec.getSize(widthSpec) / 2
        halfHeight = MeasureSpec.getSize(heightSpec) / 2
        if (!heightSet) { originalHeight = MeasureSpec.getSize(heightSpec); heightSet = true }
        super.onMeasure(widthSpec, heightSpec)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        detector.onTouchEvent(e)
        return super.onTouchEvent(e)
    }

    override fun onScrolled(dx: Int, dy: Int) {
        super.onScrolled(dx, dy)
        val lm = layoutManager as? LinearLayoutManager ?: return
        atFirstPosition = lm.findFirstVisibleItemPosition() == 0
        atLastPosition = lm.findLastVisibleItemPosition() == lm.itemCount - 1
    }

    private fun getPositionX(px: Float): Float {
        if (currentScale < 1) return 0f
        return px.coerceIn(-halfWidth * (currentScale - 1), halfWidth * (currentScale - 1))
    }
    private fun getPositionY(py: Float): Float {
        if (currentScale < 1) return 0f
        return py.coerceIn(-halfHeight * (currentScale - 1), halfHeight * (currentScale - 1))
    }
    private fun setScaleRate(rate: Float) { scaleX = rate; scaleY = rate }

    private fun zoom(fromRate: Float, toRate: Float, fromX: Float, toX: Float, fromY: Float, toY: Float) {
        isZooming = true
        val anim = AnimatorSet()
        anim.playTogether(
            ValueAnimator.ofFloat(fromX, toX).apply { addUpdateListener { x = it.animatedValue as Float } },
            ValueAnimator.ofFloat(fromY, toY).apply { addUpdateListener { y = it.animatedValue as Float } },
            ValueAnimator.ofFloat(fromRate, toRate).apply { addUpdateListener { currentScale = it.animatedValue as Float; setScaleRate(currentScale) } }
        )
        anim.duration = 200; anim.interpolator = DecelerateInterpolator(); anim.start()
        anim.doOnEnd {
            isZooming = false; currentScale = toRate
            if (toRate <= DEFAULT_RATE) {
                x = 0f; y = 0f
                setScaleRate(DEFAULT_RATE)
            }
        }
    }

    fun zoomFling(vx: Int, vy: Int): Boolean {
        if (currentScale <= 1f) return false
        val f = 0.4f; val anim = AnimatorSet()
        if (vx != 0) anim.play(ValueAnimator.ofFloat(x, getPositionX(x + f * vx / 2)).apply { addUpdateListener { x = getPositionX(it.animatedValue as Float) } })
        if (vy != 0 && (atFirstPosition || atLastPosition))
            anim.play(ValueAnimator.ofFloat(y, getPositionY(y + f * vy / 2)).apply { addUpdateListener { y = getPositionY(it.animatedValue as Float) } })
        anim.duration = 400; anim.interpolator = DecelerateInterpolator(); anim.start()
        return true
    }

    private fun zoomScrollBy(dx: Int, dy: Int) {
        if (dx != 0) x = getPositionX(x + dx)
        if (dy != 0) y = getPositionY(y + dy)
    }

    fun onScale(scaleFactor: Float) {
        currentScale = (currentScale * scaleFactor).coerceIn(minRate, MAX_SCALE_RATE)
        setScaleRate(currentScale)
        if (currentScale <= DEFAULT_RATE) {
            x = 0f; y = 0f
        } else {
            x = getPositionX(x); y = getPositionY(y)
        }
    }

    fun onScaleBegin() { if (detector.isDoubleTapping) detector.isQuickScaling = true }
    fun onScaleEnd() {
        if (currentScale <= DEFAULT_RATE) {
            currentScale = DEFAULT_RATE
            x = 0f; y = 0f
            setScaleRate(DEFAULT_RATE)
        }
    }

    internal inner class GestureListener : GestureDetectorWithLongTap.Listener() {
        override fun onSingleTapConfirmed(ev: MotionEvent): Boolean { tapListener?.invoke(ev); return false }
        override fun onDoubleTap(ev: MotionEvent): Boolean { detector.isDoubleTapping = true; return false }
        fun onDoubleTapConfirmed(ev: MotionEvent) {
            if (!isZooming && doubleTapZoom) {
                if (scaleX != DEFAULT_RATE) {
                    zoom(currentScale, DEFAULT_RATE, x, 0f, y, 0f)
                } else {
                    val s = 2f
                    zoom(DEFAULT_RATE, s, 0f, (halfWidth - ev.x) * (s - 1), 0f, (halfHeight - ev.y) * (s - 1))
                }
            }
        }
        override fun onLongTapConfirmed(ev: MotionEvent) { longTapListener?.invoke(ev) }
    }

    internal inner class Detector : GestureDetectorWithLongTap(context, listener) {
        private var scrollPointerId = 0; private var downX = 0; private var downY = 0
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var isZoomDragging = false
        var isDoubleTapping = false; var isQuickScaling = false

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { scrollPointerId = ev.getPointerId(0); downX = (ev.x + 0.5f).toInt(); downY = (ev.y + 0.5f).toInt() }
                MotionEvent.ACTION_POINTER_DOWN -> { val i = ev.actionIndex; scrollPointerId = ev.getPointerId(i); downX = (ev.getX(i) + 0.5f).toInt(); downY = (ev.getY(i) + 0.5f).toInt() }
                MotionEvent.ACTION_MOVE -> {
                    if (isDoubleTapping && isQuickScaling) return true
                    val idx = ev.findPointerIndex(scrollPointerId); if (idx < 0) return false
                    val cx = (ev.getX(idx) + 0.5f).toInt(); val cy = (ev.getY(idx) + 0.5f).toInt()
                    var dx = cx - downX; var dy = if (atFirstPosition || atLastPosition) cy - downY else 0
                    if (!isZoomDragging && currentScale > 1f) {
                        var start = false
                        if (abs(dx) > touchSlop) { dx += if (dx < 0) touchSlop else -touchSlop; start = true }
                        if (abs(dy) > touchSlop) { dy += if (dy < 0) touchSlop else -touchSlop; start = true }
                        if (start) isZoomDragging = true
                    }
                    if (isZoomDragging) zoomScrollBy(dx, dy)
                }
                MotionEvent.ACTION_UP -> {
                    if (isDoubleTapping && !isQuickScaling) listener.onDoubleTapConfirmed(ev)
                    isZoomDragging = false; isDoubleTapping = false; isQuickScaling = false
                }
                MotionEvent.ACTION_CANCEL -> { isZoomDragging = false; isDoubleTapping = false; isQuickScaling = false }
            }
            return super.onTouchEvent(ev)
        }
    }
}

private const val DEFAULT_RATE = 1f
private const val MAX_SCALE_RATE = 3f
