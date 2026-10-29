package io.github.lcebot.mivolumeenhanced.hook.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.github.lcebot.mivolumeenhanced.Options
import io.github.lcebot.mivolumeenhanced.Protocol
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The user's options as seen from a hooked process.
 *
 * The settings app's provider is the only source. Reading it can fail early in boot (the app's
 * credential-protected storage is still locked, or the provider process is not up yet), so a failed
 * read is retried, and the last good answer is kept in this process's device-protected storage to
 * cover the time until the first read succeeds. Listeners always run on the main thread.
 */
object HookOptions {
    private const val CACHE = "mivolumeenhanced_options"
    private val RETRY_SECONDS = longArrayOf(2, 5, 10, 30, 60, 120)

    @Volatile
    var current = Options()
        private set

    private val listeners = CopyOnWriteArrayList<(Options) -> Unit>()
    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mve-options").apply { isDaemon = true } }

    @Volatile
    private var context: Context? = null

    fun addListener(listener: (Options) -> Unit) {
        listeners += listener
    }

    fun install() {
        ProcessContext.whenReady { app ->
            context = app
            readCache(app)?.let { current = it }
            val reload = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) = load(0)
            }
            app.registerReceiver(reload, IntentFilter(Protocol.ACTION_CONFIG_CHANGED), Context.RECEIVER_EXPORTED)
            app.registerReceiver(reload, IntentFilter(Intent.ACTION_USER_UNLOCKED), Context.RECEIVER_NOT_EXPORTED)
            load(0)
        }
    }

    private fun load(attempt: Int) {
        io.execute {
            val app = context ?: return@execute
            val result = runCatching {
                app.contentResolver.call(Protocol.CONFIG_URI, Protocol.CONFIG_METHOD_GET, null, null)
            }
            val bundle = result.getOrNull()
            if (bundle == null) {
                val delay = RETRY_SECONDS.getOrNull(attempt)
                // Early in boot the provider is often not up yet; only running out of retries is a problem.
                if (delay != null) {
                    Diagnostics.waiting("options", "settings provider unavailable, retry ${attempt + 1}")
                    io.schedule({ load(attempt + 1) }, delay, TimeUnit.SECONDS)
                } else {
                    HookLog.w("settings unreadable, using the cached ones", result.exceptionOrNull())
                }
                return@execute
            }
            update(app, Options.fromBundle(bundle))
        }
    }

    private fun update(app: Context, options: Options) {
        writeCache(app, options)
        if (options == current) return
        current = options
        Diagnostics.ok("options", options.toString())
        ProcessContext.main.post { listeners.forEach { runCatching { it(options) }.onFailure { e -> HookLog.e("options listener", e) } } }
    }

    private fun prefs(app: Context) =
        app.createDeviceProtectedStorageContext().getSharedPreferences(CACHE, Context.MODE_PRIVATE)

    private fun readCache(app: Context): Options? = runCatching {
        val p = prefs(app)
        if (!p.contains(Options.KEY_ANC_ROW)) return null
        val d = Options()
        Options(
            ancRow = p.getBoolean(Options.KEY_ANC_ROW, d.ancRow),
            cycleIncludesOff = p.getBoolean(Options.KEY_CYCLE_INCLUDES_OFF, d.cycleIncludesOff),
            island = p.getBoolean(Options.KEY_ISLAND, d.island),
            appVolumeEntry = p.getBoolean(Options.KEY_APP_VOLUME_ENTRY, d.appVolumeEntry),
            overflowPlacement = Options.placementOf(p.getString(Options.KEY_OVERFLOW_PLACEMENT, null)),
        )
    }.getOrNull()

    private fun writeCache(app: Context, o: Options) {
        // The cache only speeds up the first read after a reboot; failing to write it costs nothing
        // that the provider read does not cover.
        try {
            prefs(app).edit()
                .putBoolean(Options.KEY_ANC_ROW, o.ancRow)
                .putBoolean(Options.KEY_CYCLE_INCLUDES_OFF, o.cycleIncludesOff)
                .putBoolean(Options.KEY_ISLAND, o.island)
                .putBoolean(Options.KEY_APP_VOLUME_ENTRY, o.appVolumeEntry)
                .putString(Options.KEY_OVERFLOW_PLACEMENT, o.overflowPlacement.name)
                .apply()
        } catch (_: RuntimeException) {
        }
    }
}
