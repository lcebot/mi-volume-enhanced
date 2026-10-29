@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.mivolumeenhanced.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.lcebot.mivolumeenhanced.Protocol
import io.github.lcebot.mivolumeenhanced.R
import io.github.lcebot.mivolumeenhanced.ReportItem
import io.github.lcebot.mivolumeenhanced.ScopeState
import io.github.lcebot.mivolumeenhanced.ScopeStatus

/**
 * What each scope reported, shown when the status chip is tapped: whether it answered, and which
 * hooks are in place with what each feature last concluded.
 *
 * A bottom sheet rather than a page, because it is supplementary content to glance at and dismiss.
 * Report ids and details stay as the hooks wrote them, since the point of a card is to be copied
 * into a bug report.
 */
@Composable
fun StatusSheet(scopes: Map<String, ScopeState>, onDismiss: () -> Unit, onCopy: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.sheet_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = Dimens.SheetGutter),
        )
        // Under the title, not at the foot: a sheet arrives from the bottom edge, so its last line is
        // the one a partly open sheet cuts off.
        Text(
            text = stringResource(R.string.sheet_tap_to_copy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = Dimens.SheetGutter,
                end = Dimens.SheetGutter,
                top = 2.dp,
                bottom = Dimens.SpacingGroup,
            ),
        )
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = Dimens.PageGutter),
        ) {
            Protocol.SCOPES.forEachIndexed { i, pkg ->
                ScopeCard(pkg, scopes[pkg], i, Protocol.SCOPES.size, onCopy)
            }
        }
    }
}

/**
 * One scope. The whole card copies its report, and the click is labelled, because nothing in the
 * card's own text says what pressing it does.
 */
@Composable
private fun ScopeCard(pkg: String, state: ScopeState?, index: Int, count: Int, onCopy: (String) -> Unit) {
    val haptics = rememberHaptics()
    val copyLabel = stringResource(R.string.card_copy)
    val shapes = ListItemDefaults.segmentedShapes(index = index, count = count)
    val report = state?.report
    val context = LocalContext.current
    SegmentedListItem(
        shapes = shapes,
        // A plain item made clickable, rather than the clickable item overload, because only
        // clickable takes a label for the action; the clip keeps the ripple on the item's shape.
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Dimens.SheetGutter, end = Dimens.SheetGutter, bottom = ListItemDefaults.SegmentedGap)
            .clip(shapes.shape)
            .clickable(onClickLabel = copyLabel, onClick = haptics.ticking { onCopy(ScopeStatus.reportText(context, pkg)) }),
        supportingContent = {
            // A field is closer to its neighbours than the fields are to the name, which is what
            // makes them read as one block under it.
            Column(
                verticalArrangement = Arrangement.spacedBy(Dimens.SpacingSeam),
                modifier = Modifier.padding(top = Dimens.SpacingSeam),
            ) {
                val (status, statusColor) = when (state?.alive) {
                    true -> R.string.status_loaded to MaterialTheme.colorScheme.onSurface
                    false -> R.string.status_not_loaded to MaterialTheme.colorScheme.error
                    null -> R.string.status_checking to MaterialTheme.colorScheme.onSurfaceVariant
                }
                FieldRow(stringResource(R.string.field_status), stringResource(status), statusColor)
                if (report == null) {
                    FieldRow(
                        stringResource(R.string.field_report),
                        stringResource(R.string.report_none),
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FieldRow(stringResource(R.string.field_version), report.version, MaterialTheme.colorScheme.onSurface)
                    FieldRow(stringResource(R.string.field_pid), report.pid.toString(), MaterialTheme.colorScheme.onSurface, mono = true)
                    report.items.forEach { ReportRow(it) }
                }
            }
        },
        content = { Text(stringResource(scopeName(pkg)), style = MaterialTheme.typography.titleMedium) },
    )
}

/**
 * A report item as a field. A feature's id is the label and its verdict the value; ids are
 * monospace because they are identifiers to search the source for, not words. A problem is keyed
 * by its message, which is too long for the label column, so there the verdict is the label and
 * the message leads the value.
 */
@Composable
private fun ReportRow(item: ReportItem) {
    val (verdict, color) = when (item.state) {
        "OK" -> R.string.state_ok to MaterialTheme.colorScheme.onSurface
        "WAITING" -> R.string.state_waiting to MaterialTheme.colorScheme.tertiary
        "MISSING" -> R.string.state_missing to MaterialTheme.colorScheme.onSurfaceVariant
        "WARNING" -> R.string.state_warning to MaterialTheme.colorScheme.tertiary
        else -> R.string.state_failed to MaterialTheme.colorScheme.error
    }
    val count = if (item.count > 0) " (${item.count})" else ""
    val detail = if (item.detail.isNotEmpty()) "\n" + item.detail else ""
    if (item.isProblemMessage) {
        FieldRow(stringResource(verdict), item.id + count + detail, color)
    } else {
        FieldRow(item.id, stringResource(verdict) + count + detail, color, monoLabel = true)
    }
}

/**
 * The label column has a minimum width, not a fixed one: at a large font scale a fixed column clips
 * the label, and a label pushing its value right is a smaller problem than one that cannot be read.
 */
@Composable
private fun FieldRow(label: String, value: String, color: Color, mono: Boolean = false, monoLabel: Boolean = false) {
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontFamily = if (monoLabel) FontFamily.Monospace else null,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .widthIn(min = 84.dp)
                .padding(end = 8.dp)
                .alignByBaseline(),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else null,
            color = color,
            modifier = Modifier.weight(1f).alignByBaseline(),
        )
    }
}
