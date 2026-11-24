package app.soundlink.android

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class RouteSide(
    val name: String,
    val status: String,
    val highlighted: Boolean,
    val dimmed: Boolean = false,
)

private val DEVICE_SIZE = 64.dp
private val HEADPHONE_SIZE = 52.dp
private val EDGE_INSET = 8.dp
private val BUBBLE_GAP = 6.dp
private val LABEL_WIDTH = 84.dp
private val ROUTE_HEIGHT = 128.dp
private const val MOVE_MILLIS = 450
private const val PULSE_MILLIS = 700
private const val DIMMED_ALPHA = 0.45f

/** Link-online dot; Material 3 has no semantic success color. */
internal val LINK_ONLINE = Color(0xFF30B158)

/**
 * Home bubble route: this phone on the left, the Mac on the right, and the headphones
 * bubble resting beside whichever device currently holds them.
 */
@Composable
internal fun RouteBubbles(
    route: HomeRoute,
    local: RouteSide,
    peer: RouteSide,
    localIcon: Int,
    peerIcon: Int,
    summary: String,
) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(ROUTE_HEIGHT).clearAndSetSemantics {
            contentDescription = listOf(summary, "${local.name} ${local.status}", "${peer.name} ${peer.status}").joinToString(", ")
        }
    ) {
        val besideLocal = EDGE_INSET + DEVICE_SIZE + BUBBLE_GAP
        val target = when (route) {
            HomeRoute.LOCAL -> besideLocal
            HomeRoute.PEER -> maxWidth - besideLocal - HEADPHONE_SIZE
            else -> (maxWidth - HEADPHONE_SIZE) / 2
        }
        val x by animateDpAsState(target, tween(MOVE_MILLIS), label = "headphones")
        val pulse by rememberInfiniteTransition(label = "switching").animateFloat(
            initialValue = 1f,
            targetValue = DIMMED_ALPHA,
            animationSpec = infiniteRepeatable(tween(PULSE_MILLIS), RepeatMode.Reverse),
            label = "pulse",
        )
        val peerX = maxWidth - EDGE_INSET - DEVICE_SIZE
        DeviceBubble(localIcon, local, EDGE_INSET)
        DeviceBubble(peerIcon, peer, peerX)
        HeadphoneBubble(
            active = route != HomeRoute.IDLE,
            modifier = Modifier
                .offset(x, (DEVICE_SIZE - HEADPHONE_SIZE) / 2)
                .alpha(if (route == HomeRoute.SWITCHING) pulse else 1f),
        )
    }
}

@Composable
private fun DeviceBubble(icon: Int, side: RouteSide, x: Dp) {
    val colors = MaterialTheme.colorScheme
    val tint = if (side.highlighted) colors.primary else colors.onSurfaceVariant
    Box(
        Modifier.offset(x, 0.dp).size(DEVICE_SIZE).alpha(if (side.dimmed) DIMMED_ALPHA else 1f)
            .background(if (side.highlighted) colors.primary.copy(alpha = 0.16f) else colors.surfaceVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, Modifier.size(28.dp), tint = tint)
    }
    Column(
        Modifier.offset(x + (DEVICE_SIZE - LABEL_WIDTH) / 2, DEVICE_SIZE + 8.dp).width(LABEL_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(side.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(side.status, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            color = if (side.highlighted) colors.primary else colors.onSurfaceVariant)
    }
}

@Composable
private fun HeadphoneBubble(active: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val base = modifier.size(HEADPHONE_SIZE)
    Box(
        if (active) base.background(colors.primary, CircleShape)
        else base.background(colors.surfaceVariant, CircleShape).border(BorderStroke(1.5.dp, colors.outline), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_headphones), null, Modifier.size(26.dp),
            tint = if (active) colors.onPrimary else colors.outline)
    }
}

/** App-to-app link row, kept apart from the headphone route so the two states never blur. */
@Composable
internal fun LinkRow(label: String, state: String, online: Boolean, action: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.size(7.dp).background(if (online) LINK_ONLINE else MaterialTheme.colorScheme.outline, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(state, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        action()
    }
}
