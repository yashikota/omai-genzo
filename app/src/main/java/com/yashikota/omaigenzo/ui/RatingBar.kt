package com.yashikota.omaigenzo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yashikota.omaigenzo.PhotoItem

/** Warm gold that reads on both the dark photo scrim and light surfaces. */
val RatingGold = Color(0xFFFFC53D)

/**
 * Five tappable stars. Tapping the current rating clears it, the same as Lightroom, so there is no
 * separate "0 stars" control. Each star is a full 48dp touch target even when drawn small.
 */
@Composable
fun RatingBar(
    rating: Int,
    onRatingChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    starSize: Dp = 22.dp,
    inactiveColor: Color = Color.White.copy(alpha = 0.45f),
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .selectableGroup()
            .semantics { contentDescription = if (rating > 0) "評価 ★$rating" else "未評価" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (star in 1..PhotoItem.MAX_RATING) {
            val filled = star <= rating
            Icon(
                imageVector = if (filled) Icons.Filled.Star else Icons.Outlined.StarOutline,
                contentDescription = if (star == rating) "★$star（タップで評価を外す）" else "★$star を付ける",
                tint = if (filled) RatingGold else inactiveColor,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(50))
                    .selectable(
                        selected = star == rating,
                        role = Role.RadioButton,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = false, radius = starSize),
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onRatingChange(if (star == rating) 0 else star)
                        },
                    )
                    .size(starSize),
            )
        }
    }
}

/** Compact "★3" chip for thumbnails. Draws nothing for unrated photos. */
@Composable
fun RatingBadge(rating: Int, modifier: Modifier = Modifier) {
    if (rating <= 0) return
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Star,
            contentDescription = null,
            tint = RatingGold,
            modifier = Modifier.size(10.dp),
        )
        Spacer(Modifier.width(2.dp))
        Text(text = "$rating", color = RatingGold, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}
