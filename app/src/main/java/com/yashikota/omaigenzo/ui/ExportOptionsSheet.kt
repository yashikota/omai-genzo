package com.yashikota.omaigenzo.ui

import android.text.format.Formatter
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yashikota.omaigenzo.PhotoItem
import com.yashikota.omaigenzo.data.ExportContent
import com.yashikota.omaigenzo.data.ExportOptions
import com.yashikota.omaigenzo.data.ExportPlanner
import com.yashikota.omaigenzo.data.ExportProgress

/**
 * Lets the user decide what an export writes before choosing where. The summary line updates live so
 * the count and size are never a surprise after the folder picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportOptionsSheet(
    photos: List<PhotoItem>,
    initial: ExportOptions,
    onDismiss: () -> Unit,
    onConfirm: (ExportOptions) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var content by rememberSaveable { mutableStateOf(initial.content) }
    var writeXmp by rememberSaveable { mutableStateOf(initial.writeXmpSidecar) }
    var minRating by rememberSaveable { mutableStateOf(initial.minRating) }
    var skipExisting by rememberSaveable { mutableStateOf(initial.skipExisting) }
    val options = ExportOptions(content, writeXmp, minRating, skipExisting)

    val context = LocalContext.current
    val hasRaw = remember(photos) { photos.any { it.isRawFile() } }
    val eligibleCount =
        remember(photos, minRating) { ExportPlanner.eligible(photos, minRating).size }
    val sizeLabel = remember(photos, minRating) {
        Formatter.formatShortFileSize(context, ExportPlanner.estimatedBytes(photos, options))
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                "キープ写真の書き出し",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (eligibleCount > 0) "$eligibleCount 枚・約 $sizeLabel" else "条件に合う写真がありません",
                style = MaterialTheme.typography.bodyMedium,
                color = if (eligibleCount >
                    0
                ) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )

            SectionLabel("書き出すファイル")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val choices = listOf(
                    ExportContent.RAW_AND_JPEG to "RAW+JPEG",
                    ExportContent.RAW_ONLY to "RAWのみ",
                    ExportContent.JPEG_ONLY to "JPEGのみ",
                )
                choices.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = content == value,
                        onClick = { content = value },
                        shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                    ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Text(
                text = "片方しかない写真は、ある方のファイルを書き出します",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            SectionLabel("評価で絞り込む")
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (stars in 0..PhotoItem.MAX_RATING) {
                    FilterChip(
                        selected = minRating == stars,
                        onClick = { minRating = stars },
                        label = { Text(if (stars == 0) "すべて" else "$stars+") },
                        leadingIcon = if (stars > 0) {
                            {
                                Icon(
                                    Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = RatingGold,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            ToggleRow(
                title = "XMPサイドカーを付ける",
                subtitle = if (hasRaw) "RAWと同名の .xmp に評価を書き込みます（Lightroom・Capture One 対応）" else "RAW写真がないため作成されません",
                checked = writeXmp && hasRaw && content != ExportContent.JPEG_ONLY,
                enabled = hasRaw && content != ExportContent.JPEG_ONLY,
                onCheckedChange = { writeXmp = it },
            )
            ToggleRow(
                title = "同名のファイルはスキップ",
                subtitle = "途中で止めても、同じフォルダへもう一度書き出せば続きから保存できます",
                checked = skipExisting,
                onCheckedChange = { skipExisting = it },
            )

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onConfirm(options) },
                enabled = eligibleCount > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("書き出し先を選ぶ", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(
                        alpha = 0.38f,
                    )
                },
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        // The row handles the toggle so the whole line is the touch target.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Blocking progress with a cancel button; the export keeps its finished files when cancelled. */
@Composable
fun ExportProgressDialog(progress: ExportProgress, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("中止") } },
        title = { Text("書き出し中…") },
        text = {
            Column {
                if (progress.totalPhotos > 0) {
                    LinearProgressIndicator(progress = {
                        progress.fraction
                    }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (progress.totalPhotos >
                        0
                    ) {
                        "${progress.completedPhotos} / ${progress.totalPhotos} 枚"
                    } else {
                        "準備中…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (progress.currentFileName.isNotEmpty()) {
                    Text(
                        text = progress.currentFileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    )
}
