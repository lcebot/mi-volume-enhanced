package io.github.lcebot.mivolumeenhanced

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** One finding reported by a hooked process. */
data class ReportItem(val id: String, val state: String, val detail: String, val count: Int) {
    val isProblem get() = state == "WARNING" || state == "FAILED"

    /** Problems from the hook log are keyed by a message; feature ids never contain a space. */
    val isProblemMessage get() = isProblem && ' ' in id
}

data class ScopeReport(val version: String, val pid: Int, val time: Long, val items: List<ReportItem>)

/** [alive] is null while a probe is waiting for this scope's answer. */
data class ScopeState(val alive: Boolean?, val report: ScopeReport?) {
    /** Problems in the report of a scope that is loaded now; a stale report's do not count. */
    val problems: List<ReportItem> get() = if (alive == true) report?.items?.filter { it.isProblem }.orEmpty() else emptyList()
}

/**
 * Whether the module is loaded in each scope right now. A probe pings every scope; only a process
 * that answers counts as loaded, so turning the module off shows immediately instead of leaving a
 * stale "loaded" behind. The last report of each scope is kept to show while a probe runs.
 */
object ScopeStatus {
    private const val TIMEOUT_MS = 1_500L
    private val main = Handler(Looper.getMainLooper())
    private val states = MutableStateFlow(Protocol.SCOPES.associateWith { ScopeState(null, null) })
    val flow: StateFlow<Map<String, ScopeState>> = states.asStateFlow()

    @Volatile private var nonce = 0L

    fun probe(context: Context) {
        val app = context.applicationContext
        nonce = System.nanoTime()
        states.value = Protocol.SCOPES.associateWith { ScopeState(null, states.value[it]?.report ?: cached(app, it)) }
        for (scope in Protocol.SCOPES) {
            // A ping that cannot be sent leaves that scope unanswered, and the timeout reports it.
            try {
                app.sendBroadcast(
                    Intent(Protocol.ACTION_PING).setPackage(scope)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        .putExtra(Protocol.EXTRA_NONCE, nonce),
                )
            } catch (_: RuntimeException) {
            }
        }
        val round = nonce
        main.postDelayed({
            if (round != nonce) return@postDelayed
            states.value = states.value.mapValues { (_, s) -> if (s.alive == null) s.copy(alive = false) else s }
        }, TIMEOUT_MS)
    }

    fun accept(context: Context, intent: Intent) {
        val process = intent.getStringExtra(Protocol.EXTRA_PROCESS) ?: return
        if (process !in Protocol.SCOPES) return
        val json = intent.getStringExtra(Protocol.EXTRA_REPORT)
        val report = json?.let(::parse)
        if (json != null && report != null) {
            context.getSharedPreferences(CACHE, Context.MODE_PRIVATE).edit().putString(process, json).apply()
        }
        // A startup report (nonce 0) proves the scope is loaded as much as an answer does.
        val answer = intent.getLongExtra(Protocol.EXTRA_NONCE, 0L)
        val current = states.value[process]
        val alive = if (answer == 0L || answer == nonce) true else current?.alive
        states.value = states.value + (process to ScopeState(alive, report ?: current?.report))
    }

    /**
     * Plain text for a bug report about one scope. The header names the app version and device,
     * because a report pasted into an issue is read without either.
     */
    fun reportText(context: Context, scope: String): String = buildString {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        appendLine("${context.getString(R.string.app_name)} $version, Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        val state = states.value[scope]
        append("$scope: " + when (state?.alive) { true -> "loaded"; false -> "not loaded"; null -> "checking" })
        state?.report?.let { r ->
            append(", module ${r.version}, pid ${r.pid}")
            r.items.forEach { appendLine().append("  ${it.id} [${it.state}] ${it.detail}" + if (it.count > 0) " (${it.count}x)" else "") }
        }
    }

    private fun cached(context: Context, scope: String): ScopeReport? =
        context.getSharedPreferences(CACHE, Context.MODE_PRIVATE).getString(scope, null)?.let(::parse)

    private fun parse(json: String): ScopeReport? = runCatching {
        val root = JSONObject(json)
        val items = root.optJSONArray("items")
        ScopeReport(
            version = root.optString("version"),
            pid = root.optInt("pid"),
            time = root.optLong("time"),
            items = (0 until (items?.length() ?: 0)).map { i ->
                val o = items!!.getJSONObject(i)
                ReportItem(o.optString("id"), o.optString("state"), o.optString("detail"), o.optInt("count"))
            },
        )
    }.getOrNull()

    private const val CACHE = "scope_reports"
}

/** Startup reports and probe answers arrive here, also while no screen is open. */
class HookStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Protocol.ACTION_HOOK_ALIVE, Protocol.ACTION_PONG -> ScopeStatus.accept(context, intent)
        }
    }
}
