package com.yashikota.omaigenzo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraRoll
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yashikota.omaigenzo.ImportProgress
import com.yashikota.omaigenzo.ui.theme.*

@Composable
fun FolderSelectScreen(
    onSelectFolderClick: () -> Unit,
    isImporting: Boolean = false,
    importProgress: ImportProgress = ImportProgress(),
) {
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
                // Logo Icon Group
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(DarkSurfaceVariant)
                        .border(2.dp, PrimaryNeon, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraRoll,
                        contentDescription = null,
                        tint = PrimaryNeon,
                        modifier = Modifier.size(44.dp),
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "おまいGENZO!",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    color = PrimaryNeon,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "高速スワイプ写真選別 & LibRaw現像エンジン",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Feature Highlights
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(DarkSurfaceVariant)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FeatureRow(
                        icon = Icons.Default.Swipe,
                        title = "マチアプ風高速スワイプ選別",
                        subtitle = "👉 KEEP | 👈 REJECT | ☝️ 前の画像へ戻る | 👇 スキップ",
                    )
                    HorizontalDivider(color = BorderColor)
                    FeatureRow(
                        icon = Icons.Default.PhotoLibrary,
                        title = "フォルダまるごと安全ローカルコピー",
                        subtitle = "原本フォルダからアプリ内へコピーして超高速選別",
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                if (isImporting || importProgress.isImporting) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = PrimaryNeon,
                            trackColor = BorderColor,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (importProgress.message.isNotEmpty()) importProgress.message else "写真の取り込み・コピー中...",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    Button(
                        onClick = onSelectFolderClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeon),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = DarkBackground,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "写真フォルダを選択して選別開始",
                            color = DarkBackground,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = PrimaryNeon,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(text = title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(text = subtitle, color = TextSecondary, fontSize = 11.sp)
        }
    }
}
