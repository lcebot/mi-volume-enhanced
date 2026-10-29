package io.github.lcebot.mivolumeenhanced.hook.core

import android.content.Context
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject

/**
 * What each feature concluded in this process and why, sent to the settings app on request. It
 * records outcomes rather than obfuscated member names: those differ between ROM builds and mean
 * nothing to the person reading the report.
 */
object Diagnostics {
    enum class State { OK, WAITING, MISSING, WARNING, FAILED }

    /** Problems are keyed by their message; past this many, new ones are only counted. */
    private const val MAX_PROBLEMS = 24

    private class Item(var state: State, var detail: String) {
        var count = 0
    }

    private val items = LinkedHashMap<String, Item>()
    private var problems = 0
    private var dropped = 0

    @Volatile
    private var version = ""

    fun ok(id: String, detail: String) = record(id, State.OK, detail)
    fun waiting(id: String, detail: String) = record(id, State.WAITING, detail)
    fun missing(id: String, detail: String) = record(id, State.MISSING, detail)
    /** A feature failed: shown in the report, and the stack trace goes to the LSPosed log once. */
    fun failed(id: String, error: Throwable) {
        val first = synchronized(this) { items[id]?.state != State.FAILED }
        record(id, State.FAILED, error.javaClass.simpleName + (error.message?.let { ": $it" } ?: ""))
        if (first) HookLog.toLsposed(android.util.Log.ERROR, id, error)
    }

    @Synchronized
    fun record(id: String, state: State, detail: String) {
        val item = items.getOrPut(id) { Item(state, detail) }
        item.state = state
        item.detail = detail
    }

    /** Counts one occurrence; [detail] replaces the last one when given. */
    @Synchronized
    fun hit(id: String, detail: String? = null) {
        val item = items.getOrPut(id) { Item(State.OK, detail ?: "") }
        item.count++
        if (detail != null) item.detail = detail
    }

    /**
     * Records a problem reported through [HookLog]. Returns true the first time a message is seen,
     * which is when it is worth writing to the LSPosed log; later ones only raise its count.
     */
    @Synchronized
    fun problem(state: State, message: String, error: Throwable?): Boolean {
        val detail = error?.let { it.javaClass.simpleName + (it.message?.let { m -> ": $m" } ?: "") } ?: ""
        items[message]?.let { item ->
            item.count++
            if (state > item.state) item.state = state
            if (detail.isNotEmpty()) item.detail = detail
            return false
        }
        if (problems >= MAX_PROBLEMS) {
            dropped++
            items.getOrPut("more_problems") { Item(State.WARNING, "") }.apply {
                count = dropped
                this.detail = "$dropped more not listed, see the LSPosed log"
            }
            return true
        }
        problems++
        items[message] = Item(state, detail).apply { count = 1 }
        return true
    }

    fun attachVersion(context: Context) {
        if (version.isNotEmpty()) return
        version = runCatching {
            context.packageManager.getPackageInfo(io.github.lcebot.mivolumeenhanced.Protocol.PACKAGE, 0).versionName
        }.getOrNull() ?: ""
    }

    @Synchronized
    fun report(process: String): String = JSONObject().apply {
        put("process", process)
        put("version", version)
        put("pid", Process.myPid())
        put("time", System.currentTimeMillis())
        put("items", JSONArray().apply {
            items.forEach { (id, item) ->
                put(JSONObject().apply {
                    put("id", id)
                    put("state", item.state.name)
                    put("detail", item.detail)
                    put("count", item.count)
                })
            }
        })
    }.toString()
}
