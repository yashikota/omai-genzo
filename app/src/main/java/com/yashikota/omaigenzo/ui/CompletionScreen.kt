package com.yashikota.omaigenzo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yashikota.omaigenzo.PhotoItem
import com.yashikota.omaigenzo.SelectionState
import com.yashikota.omaigenzo.ui.theme.*

@Composable
fun CompletionScreen(
    photos: List<PhotoItem>,
    onResetClick: () -> Unit = {},
    onRestartSelection: () -> Unit = onResetClick,
    onGalleryClick: () -> Unit = {},
    onOpenGallery: () -> Unit = onGalleryClick,
    onExportClick: () -> Unit = {},
    onExportAcceptPhotos: () -> Unit = onExportClick,
    onResumePending: () -> Unit = {},
) {
    val totalCount = photos.size
    val acceptCount = photos.count { it.selectionState == SelectionState.ACCEPT }
    val rejectCount = photos.count { it.selectionState == SelectionState.REJECT }
    val pendingCount = photos.count { it.selectionState == SelectionState.PENDING }
    val acceptPercentage = if (totalCount > 0) (acceptCount * 100) / totalCount else 0

    Scaffold(
        containerColor = DarkBackground,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(DarkSurface)
                    .border(1.dp, BorderColor, RoundedCornerShape(24.dp))
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Default.Stars,
                    contentDescription = null,
                    tint = PrimaryNeon,
                    modifier = Modifier.size(72.dp),
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = if (pendingCount == 0) "選別が完了しました！" else "写真を一巡しました",
                    color = TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (pendingCount == 0) "全 $totalCount 枚の判定が終わりました。" else "$pendingCount 枚が保留になっています。",
                    color = TextSecondary,
                    fontSize = 14.sp,
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(DarkSurfaceVariant)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "$acceptCount 枚", color = AcceptGreen, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(text = "キープ ($acceptPercentage%)", color = TextSecondary, fontSize = 12.sp)
                    }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(40.dp)
                            .background(BorderColor),
                    )

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "$rejectCount 枚", color = RejectRed, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(text = "破棄", color = TextSecondary, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = if (pendingCount > 0) onResumePending else onRestartSelection,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeon),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = DarkBackground)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (pendingCount > 0) "保留した写真を続ける" else "別のフォルダを選ぶ",
                        color = DarkBackground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onOpenGallery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryNeon),
                ) {
                    Icon(imageVector = Icons.Default.GridView, contentDescription = null, tint = PrimaryNeon)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ギャラリーで結果を確認",
                        color = PrimaryNeon,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onExportAcceptPhotos,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AcceptGreen),
                ) {
                    Icon(imageVector = Icons.Default.Download, contentDescription = null, tint = AcceptGreen)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "キープ写真を書き出す ($acceptCount 枚)",
                        color = AcceptGreen,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
}
