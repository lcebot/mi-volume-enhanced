@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.lcebot.mivolumeenhanced.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lcebot.mivolumeenhanced.Options
import io.github.lcebot.mivolumeenhanced.Placement
import io.github.lcebot.mivolumeenhanced.Prefs
import io.github.lcebot.mivolumeenhanced.Protocol
import io.github.lcebot.mivolumeenhanced.R
import io.github.lcebot.mivolumeenhanced.RootShell
import io.github.lcebot.mivolumeenhanced.ScopeState
import io.github.lcebot.mivolumeenhanced.ScopeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MainScreen(prefs: Prefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val topBar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scopes by ScopeStatus.flow.collectAsStateWithLifecycle()
    var options by remember { mutableStateOf(prefs.options) }
    var iconHidden by remember { mutableStateOf(prefs.launcherIconHidden) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }

    fun update(transform: (Options) -> Options) {
        options = transform(options)
        prefs.save(options)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ScopeStatus.probe(context) }

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(topBar.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.app_name), maxLines = 1) },
                actions = { StatusChip(scopes, onClick = { sheetOpen = true }) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                scrollBehavior = topBar,
            )
        },
        snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding()) },
    ) { inner ->
        // Readable content steps aside from a display cutout; the surfaces behind it do not.
        val cutout = WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal).asPaddingValues()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(inner)
                .padding(cutout)
                .padding(horizontal = Dimens.PageGutter)
                .navigationBarsPadding()
                .padding(bottom = Dimens.PageGutter),
        ) {
            Group(title = stringResource(R.string.group_app_volume)) {
                item { shapes ->
                    SwitchItem(
                        shapes, stringResource(R.string.option_app_volume), options.appVolumeEntry,
                        { v -> update { it.copy(appVolumeEntry = v) } },
                        stringResource(R.string.option_app_volume_summary),
                    )
                }
            }

            Group(title = stringResource(R.string.group_anc)) {
                item { shapes ->
                    SwitchItem(
                        shapes, stringResource(R.string.option_anc_row), options.ancRow,
                        { v -> update { it.copy(ancRow = v) } },
                        stringResource(R.string.option_anc_row_summary),
                    )
                }
                item { shapes ->
                    SwitchItem(
                        shapes, stringResource(R.string.option_cycle_off), options.cycleIncludesOff,
                        { v -> update { it.copy(cycleIncludesOff = v) } },
                        stringResource(if (options.cycleIncludesOff) R.string.option_cycle_off_on else R.string.option_cycle_off_off),
                    )
                }
                item { shapes ->
                    SwitchItem(
                        shapes, stringResource(R.string.option_island), options.island,
                        { v -> update { it.copy(island = v) } },
                        stringResource(R.string.option_island_summary),
                    )
                }
            }

            Group(title = stringResource(R.string.group_placement)) {
                for ((placement, title, summary) in PLACEMENTS) {
                    item { shapes ->
                        RadioItem(
                            shapes = shapes,
                            title = stringResource(title),
                            supporting = stringResource(summary),
                            selected = options.overflowPlacement == placement,
                            onSelect = { update { it.copy(overflowPlacement = placement) } },
                        )
                    }
                }
            }

            Group(title = stringResource(R.string.group_module)) {
                item { shapes ->
                    ActionItem(
                        shapes = shapes,
                        title = stringResource(R.string.action_restart),
                        supporting = stringResource(R.string.action_restart_summary),
                        icon = R.drawable.ic_restart,
                        onClick = {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { RootShell.restart(Protocol.SCOPES) }
                                snackbar.showSnackbar(restartMessage(context, result))
                                // Restarted processes report in when they come back; this catches the rest.
                                delay(3_000)
                                ScopeStatus.probe(context)
                            }
                        },
                    )
                }
                item { shapes ->
                    SwitchItem(
                        shapes, stringResource(R.string.option_hide_icon), iconHidden,
                        { hidden ->
                            prefs.launcherIconHidden = hidden
                            iconHidden = prefs.launcherIconHidden
                        },
                        stringResource(R.string.option_hide_icon_summary),
                    )
                }
            }
        }
    }

    if (sheetOpen) {
        StatusSheet(
            scopes = scopes,
            onDismiss = { sheetOpen = false },
            onCopy = { text ->
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
                scope.launch { snackbar.showSnackbar(context.getString(R.string.snack_copied)) }
            },
        )
    }
}

/**
 * The module's state at a glance, and where problems surface: the dot pulses in primary only when
 * every scope answered and none reports a problem. It turns tertiary when some scopes answered or
 * a scope reports a warning, and error when none answered or a feature failed. While a probe is
 * out it stays steady in outline, so a check in progress never reads as a verdict.
 */
@Composable
private fun StatusChip(scopes: Map<String, ScopeState>, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val total = Protocol.SCOPES.size
    val loaded = Protocol.SCOPES.count { scopes[it]?.alive == true }
    val checking = Protocol.SCOPES.any { scopes[it]?.alive == null }
    val problems = Protocol.SCOPES.flatMap { scopes[it]?.problems.orEmpty() }
    val label = when {
        checking -> stringResource(R.string.chip_checking)
        loaded == 0 -> stringResource(R.string.chip_inactive)
        loaded < total -> stringResource(R.string.chip_partial, loaded, total)
        problems.isNotEmpty() -> pluralStringResource(R.plurals.chip_problems, problems.size, problems.size)
        else -> stringResource(R.string.chip_active)
    }
    val healthy = !checking && loaded == total && problems.isEmpty()
    val dot = when {
        checking -> colors.outline
        loaded == 0 || problems.any { it.state == "FAILED" } -> colors.error
        loaded < total || problems.isNotEmpty() -> colors.tertiary
        else -> colors.primary
    }
    val haptics = rememberHaptics()
    AssistChip(
        onClick = haptics.ticking(onClick),
        label = { Text(label) },
        leadingIcon = { StatusDot(dot, pulsing = healthy) },
        colors = AssistChipDefaults.assistChipColors(labelColor = colors.onSurface),
        border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = colors.outlineVariant),
        modifier = Modifier.padding(end = 12.dp),
    )
}

private val PLACEMENTS = listOf(
    Triple(Placement.SIDE, R.string.placement_side, R.string.placement_side_summary),
    Triple(Placement.HIDDEN, R.string.placement_hidden, R.string.placement_hidden_summary),
)

fun scopeName(pkg: String): Int = when (pkg) {
    Protocol.SYSTEM_UI -> R.string.scope_systemui
    Protocol.BLUETOOTH -> R.string.scope_bluetooth
    else -> R.string.scope_misound
}

private fun restartMessage(context: Context, result: RootShell.Result): String = when (result) {
    RootShell.Result.NoRoot -> context.getString(R.string.restart_no_root)
    is RootShell.Result.Done -> listOfNotNull(
        result.restarted.takeIf { it.isNotEmpty() }?.let { names ->
            context.getString(R.string.restart_done, names.joinToString { context.getString(scopeName(it)) })
        },
        result.notRunning.takeIf { it.isNotEmpty() }?.let { names ->
            context.getString(R.string.restart_not_running, names.joinToString { context.getString(scopeName(it)) })
        },
    ).joinToString("\n")
}
