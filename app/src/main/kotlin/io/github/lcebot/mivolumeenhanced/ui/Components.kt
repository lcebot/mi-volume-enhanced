@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.mivolumeenhanced.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.lcebot.mivolumeenhanced.R

/**
 * The small header above each block of settings. Marked as a heading so a screen reader can jump
 * between sections instead of walking every row of one to reach the next.
 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Dimens.SpacingSection, bottom = Dimens.SpacingHeading)
            .semantics { heading() },
    )
}

/**
 * A block of M3 Expressive segmented list items. Material picks each row's shape from its place in
 * the block, so only the outer corners are large; rows are declared through [GroupScope.item] so
 * the block knows which one is first and last.
 */
@Composable
fun Group(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: GroupScope.() -> Unit,
) {
    val items = GroupScope().apply(content).items
    Column(modifier.fillMaxWidth()) {
        if (title != null) SectionTitle(title, Modifier.padding(horizontal = 16.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        ) {
            items.forEachIndexed { i, item -> item(ListItemDefaults.segmentedShapes(index = i, count = items.size)) }
        }
    }
}

class GroupScope internal constructor() {
    internal val items = mutableListOf<@Composable (ListItemShapes) -> Unit>()

    fun item(content: @Composable (shapes: ListItemShapes) -> Unit) {
        items += content
    }
}

/**
 * The whole row toggles the switch and is announced as one. The thumb carries a check while on,
 * so the state does not rest on colour alone.
 */
@Composable
fun SwitchItem(
    shapes: ListItemShapes,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supporting: String? = null,
) {
    val haptics = rememberHaptics()
    SegmentedListItem(
        onClick = {
            haptics.tick()
            onCheckedChange(!checked)
        },
        shapes = shapes,
        modifier = Modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        },
        // Material tops the trailing element of a tall item, but a settings switch belongs to the
        // whole row, so it stays centred whatever the supporting text's length.
        verticalAlignment = Alignment.CenterVertically,
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                thumbContent = if (checked) {
                    { Icon(painterResource(R.drawable.ic_check), null, Modifier.size(SwitchDefaults.IconSize)) }
                } else null,
            )
        },
        content = { Text(title) },
    )
}

/** One choice of a single-choice list. Choosing the current one again gives no tick, since nothing changes. */
@Composable
fun RadioItem(shapes: ListItemShapes, title: String, selected: Boolean, onSelect: () -> Unit, supporting: String? = null) {
    val haptics = rememberHaptics()
    SegmentedListItem(
        onClick = {
            if (!selected) haptics.tick()
            onSelect()
        },
        shapes = shapes,
        modifier = Modifier.semantics {
            role = Role.RadioButton
            this.selected = selected
        },
        verticalAlignment = Alignment.CenterVertically,
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        supportingContent = supporting?.let { { Text(it) } },
        content = { Text(title) },
    )
}

/** A row that does one thing when pressed, with its icon leading. */
@Composable
fun ActionItem(shapes: ListItemShapes, title: String, icon: Int, onClick: () -> Unit, supporting: String? = null) {
    val haptics = rememberHaptics()
    SegmentedListItem(
        onClick = haptics.ticking(onClick),
        shapes = shapes,
        verticalAlignment = Alignment.CenterVertically,
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        supportingContent = supporting?.let { { Text(it) } },
        content = { Text(title) },
    )
}

/**
 * The module status dot. It pulses only while every scope is loaded, so a steady dot is itself
 * information: something is still being checked, or something needs attention.
 */
@Composable
fun StatusDot(color: Color, pulsing: Boolean, modifier: Modifier = Modifier) {
    val alpha = if (pulsing) {
        rememberInfiniteTransition(label = "status dot").animateFloat(
            initialValue = 1f,
            targetValue = PULSE_LOW,
            animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
            label = "status dot alpha",
        ).value
    } else {
        1f
    }
    Box(
        modifier
            .size(DOT_SIZE)
            .alpha(alpha)
            .background(color, CircleShape),
    )
}

private val DOT_SIZE = 10.dp

// A slow breath, not a blink: the dot sits in the top bar the whole time the app is open.
private const val PULSE_MS = 1400
private const val PULSE_LOW = 60f / 255f
