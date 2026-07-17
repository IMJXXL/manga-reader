package com.mangareader.native

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.mangareader.viewer.ArchiveEngine

interface ReaderViewDelegate {
    fun setup(engine: ArchiveEngine, treePath: String, pages: List<ArchiveEngine.PageMeta>, startPage: Int, vertical: Boolean, clickFlip: Int, rtl: Boolean)
    fun goToPage(page: Int)
    fun release()
    fun trimMemory(level: Int)
    var onPageChanged: ((Int) -> Unit)?
    var onToggleUi: (() -> Unit)?
}

private fun getCombinedPaint(grayscale: Boolean, invertedColors: Boolean): Paint? {
    if (!grayscale && !invertedColors) return null
    return Paint().apply {
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix().apply {
                if (grayscale) setSaturation(0f)
                if (invertedColors) {
                    postConcat(ColorMatrix(floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f,
                    )))
                }
            },
        )
    }
}

@Composable
fun NativeReaderScreen(
    engine: ArchiveEngine,
    treePath: String,
    pages: List<ArchiveEngine.PageMeta>,
    startPage: Int,
    isVertical: Boolean,
    isRtl: Boolean = false,
    grayscale: Boolean = false,
    invertColors: Boolean = false,
    centerGapType: Int = 0,
    landscapeZoom: Boolean = true,
    trimWhiteBorder: Boolean = false,
    trimThreshold: Int = 220,
    trimWhiteRatio: Float = 0.90f,
    panNavigation: Boolean = false,
    pageLayout: Int = 0,
    pageBgColor: Int = android.graphics.Color.parseColor("#111111"),
    doublePageReverse: Boolean = false,
    doublePageSplit: Boolean = false,
    modifier: Modifier = Modifier,
    onPageChanged: (Int) -> Unit = {},
    onToggleUi: () -> Unit = {},
    onViewReady: (webtoonView: View, pagerView: View) -> Unit = { _, _ -> }
) {
    val ctx = LocalContext.current
    var savedPage by remember { mutableIntStateOf(startPage) }
    var firstComposition by remember { mutableStateOf(true) }
    var containerRef by remember { mutableStateOf<FrameLayout?>(null) }

    val webtoonView = remember { WebtoonReaderView(ctx).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) } }
    val pagerView = remember { PagerReaderView(ctx).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) } }

    fun activeDelegate(isVerticalNow: Boolean): ReaderViewDelegate = if (isVerticalNow) webtoonView else pagerView

    // Track last released delegate to avoid double release
    var lastReleasedDelegate by remember { mutableStateOf<ReaderViewDelegate?>(null) }

    DisposableEffect(treePath, isVertical, isRtl) {
        if (firstComposition) {
            savedPage = startPage
            firstComposition = false
        }
        val delegate = activeDelegate(isVertical)
        // Release previous delegate only once
        val prevDelegate = lastReleasedDelegate
        if (prevDelegate != null && prevDelegate !== delegate) {
            prevDelegate.release()
        }
        lastReleasedDelegate = delegate
        delegate.onPageChanged = { page -> savedPage = page; onPageChanged(page) }
        delegate.onToggleUi = onToggleUi
        // 先设置颜色再setup，确保adapter创建时用正确颜色
        if (delegate is PagerReaderView) delegate.pageBgColor = pageBgColor
        if (delegate is WebtoonReaderView) delegate.pageBgColor = pageBgColor
        delegate.setup(engine, treePath, pages, savedPage, isVertical, 0, isRtl)
        // Apply new config parameters to the delegate
        if (delegate is PagerReaderView) {
            delegate.setCenterGapType(centerGapType)
            delegate.setLandscapeZoom(landscapeZoom)
            delegate.setTrimWhiteBorder(trimWhiteBorder, trimThreshold, trimWhiteRatio)
            delegate.setPanNavigation(panNavigation)
        }
        if (delegate is PagerReaderView) {
            delegate.setDoublePageReverse(doublePageReverse)
            delegate.setDoublePageSplit(doublePageSplit)
        }
        if (delegate is WebtoonReaderView) {
            delegate.pageBgColor = pageBgColor
            delegate.trimWhiteBorder = trimWhiteBorder
            delegate.trimThreshold = trimThreshold
            delegate.trimWhiteRatio = trimWhiteRatio
        }
        onDispose { delegate.release(); lastReleasedDelegate = null }
    }

    // Handle pageLayout: SINGLE=0, DOUBLE=1, DOUBLE_SHIFTED=2
    LaunchedEffect(isVertical, isRtl, pageLayout) {
        if (!isVertical && pagerView is PagerReaderView) {
            val doubleMode = pageLayout == 1 || pageLayout == 2
            val shiftMode = pageLayout == 2
            // Single update: set both flags then refresh once
            pagerView.setDoublePageModeAndShift(doubleMode, shiftMode)
        }
    }

    LaunchedEffect(startPage) {
        if (startPage != savedPage) {
            savedPage = startPage
            activeDelegate(isVertical).goToPage(startPage)
        }
    }

    // Apply grayscale/invert via hardware layer paint
    LaunchedEffect(grayscale, invertColors) {
        containerRef?.let { container ->
            val paint = getCombinedPaint(grayscale, invertColors)
            if (paint != null) {
                container.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
            } else {
                container.setLayerType(View.LAYER_TYPE_NONE, null)
            }
        }
    }

    AndroidView(
        factory = {
            val container = FrameLayout(ctx).apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
            container.addView(webtoonView)
            container.addView(pagerView)
            webtoonView.visibility = if (isVertical) View.VISIBLE else View.GONE
            pagerView.visibility = if (isVertical) View.GONE else View.VISIBLE
            containerRef = container
            onViewReady(webtoonView, pagerView)
            container
        },
        update = { container ->
            container.getChildAt(0).visibility = if (isVertical) View.VISIBLE else View.GONE
            container.getChildAt(1).visibility = if (isVertical) View.GONE else View.VISIBLE
        },
        modifier = modifier
    )
}
