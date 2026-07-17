package com.mangareader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.viewer.ArchiveEngine
import com.mangareader.viewer.NaturalComparator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTocSheet(
    displayItems: List<ArchiveEngine.Item>,
    currentNavStack: List<String>,
    viewing: Boolean,
    onDismiss: () -> Unit,
    onChapterNavigate: (chapterPath: String) -> Unit,
    onItemOpen: (ArchiveEngine.Item) -> Unit
) {
    var tocSortAsc by remember { mutableStateOf(true) }
    val sortedDisplayItems = remember(displayItems, tocSortAsc) {
        val base = displayItems.sortedWith(compareByDescending<ArchiveEngine.Item> { it.dir }.thenComparing({ it.name }, NaturalComparator))
        if (tocSortAsc) base else base.reversed()
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.fillMaxWidth().height(400.dp).padding(16.dp)) {
            if (sortedDisplayItems.size > 1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("目录 (${sortedDisplayItems.size}章)", color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { tocSortAsc = !tocSortAsc }) {
                        Icon(Icons.Default.Sort, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(4.dp))
                        Text(if (tocSortAsc) "正序" else "倒序", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(sortedDisplayItems.size) { idx ->
                        val item = sortedDisplayItems[idx]
                        val cur = currentNavStack.lastOrNull() ?: ""
                        val isCurrent = viewing && item.subPath.isNotEmpty() && (cur.endsWith(item.subPath) && (cur.length == item.subPath.length || cur[cur.length - item.subPath.length - 1] == '/' || cur[cur.length - item.subPath.length - 1] == '\\'))
                        Column(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    if (item.dir && item.subPath.isNotEmpty()) {
                                        val currentPath = currentNavStack.firstOrNull() ?: ""
                                        if (currentPath.startsWith("z:")) {
                                            val inner = currentPath.removePrefix("z:")
                                            val sepIdx = inner.indexOf("//")
                                            if (sepIdx >= 0) {
                                                var archivePath = inner.substring(0, sepIdx)
                                                if (!archivePath.startsWith("a:")) archivePath = "a:$archivePath"
                                                val chapterPath = "z:${archivePath}//${item.subPath}"
                                                onChapterNavigate(chapterPath)
                                            }
                                        }
                                    } else if (!item.dir) {
                                        onItemOpen(item)
                                    }
                                    onDismiss()
                                }
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                if (item.dir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                contentDescription = if (item.dir) "文件夹" else "文件",
                                tint = if (item.dir) Color(0xFFFFB74D) else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(item.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            } else {
                Text("无目录（单本漫画）", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
        }
    }
}
