package net.weero.measix.pilot.ui.components.ai

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.UserQuestion01
import net.weero.measix.pilot.R
import net.weero.measix.pilot.service.ConversationActivity
import kotlin.math.PI
import kotlin.math.sin

/** Compact animated status for background work associated with a conversation row. */
@Composable
fun ConversationActivityIndicator(
    activities: Set<ConversationActivity>,
    modifier: Modifier = Modifier,
) {
    if (activities.isEmpty()) return
    val descriptions = buildList {
        if (ConversationActivity.RESPONSE_GENERATION in activities) {
            add(stringResource(R.string.conversation_activity_generating_response))
        }
        if (ConversationActivity.APPROVAL_REQUIRED in activities) {
            add(stringResource(R.string.conversation_turn_awaiting_approval))
        }
        if (ConversationActivity.TITLE_GENERATION in activities) {
            add(stringResource(R.string.conversation_activity_generating_title))
        }
    }
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = descriptions.joinToString()
        },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ConversationActivity.APPROVAL_REQUIRED in activities) {
            ApprovalRequiredIndicator()
        } else if (ConversationActivity.RESPONSE_GENERATION in activities) {
            ResponseGenerationPulse()
        }
        if (ConversationActivity.TITLE_GENERATION in activities) {
            TitleGenerationSpinner()
        }
    }
}

/** A distinct, stable attention state used by both the drawer and the active conversation footer. */
@Composable
fun ApprovalRequiredIndicator(
    modifier: Modifier = Modifier,
    showLabel: Boolean = false,
) {
    val label = stringResource(R.string.conversation_turn_awaiting_approval)
    Surface(
        modifier = modifier.semantics { contentDescription = label },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (showLabel) 8.dp else 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.UserQuestion01,
                contentDescription = null,
                modifier = Modifier.size(if (showLabel) 20.dp else 13.dp),
            )
            if (showLabel) {
                Text(text = label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ResponseGenerationPulse() {
    val transition = rememberInfiniteTransition(label = "response-generation")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "response-generation-phase",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { index ->
            val wave = ((sin(phase * 2f * PI.toFloat() - index * 1.15f) + 1f) / 2f)
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .graphicsLayer {
                        scaleX = 0.65f + wave * 0.35f
                        scaleY = 0.65f + wave * 0.35f
                        alpha = 0.45f + wave * 0.55f
                    }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun TitleGenerationSpinner() {
    val transition = rememberInfiniteTransition(label = "title-generation")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "title-generation-rotation",
    )
    Icon(
        imageVector = HugeIcons.Refresh01,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier
            .size(13.dp)
            .graphicsLayer { rotationZ = rotation },
    )
}
