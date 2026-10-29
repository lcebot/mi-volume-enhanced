package io.github.lcebot.mivolumeenhanced.hook.headset

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.widget.Toast
import io.github.lcebot.mivolumeenhanced.Protocol
import io.github.lcebot.mivolumeenhanced.hook.core.Diagnostics
import io.github.lcebot.mivolumeenhanced.hook.core.HookLog
import io.github.lcebot.mivolumeenhanced.hook.core.HookOptions
import io.github.lcebot.mivolumeenhanced.hook.core.MethodRef
import io.github.lcebot.mivolumeenhanced.hook.core.localized
import io.github.lcebot.mivolumeenhanced.hook.systemui.ModeIsland
import java.util.concurrent.CopyOnWriteArrayList

/** What the ANC row shows. [available] is false when no supported headset is connected. */
data class AncState(val available: Boolean, val mode: AncMode, val cycleIncludesOff: Boolean)

/**
 * Finds the connected headset that supports noise control, reads and changes its mode, and
 * publishes the result to every ANC row in SystemUI. All Bluetooth and binder work runs on its own
 * thread; listeners are called on the main thread.
 */
class AncController private constructor(private val context: Context) {
    private val main = Handler(context.mainLooper)
    private val worker = Handler(HandlerThread("mve-anc").apply { start() }.looper)
    private val listeners = CopyOnWriteArrayList<(AncState) -> Unit>()

    private val miui = MiuiHeadset { service }
    private val airPods = AirPods(context)
    private val sony = Sony(context)
    private val oppo = Oppo(context)

    @Volatile private var service: IBinder? = null
    @Volatile private var binding = false
    @Volatile private var device: BluetoothDevice? = null
    @Volatile private var backend: Backend = miui
    @Volatile private var mode = AncMode.OFF

    /**
     * Address of the headset whose support was already confirmed. Every panel show refreshes, and
     * repeating checkSupport each time is wasted IPC. Only backends whose support depends on the
     * headset alone are cached; any Bluetooth broadcast clears it.
     */
    @Volatile private var confirmed: String? = null
    private var epoch = 0

    /** OppoPods state reports by address ("" when a report has none), newest last. Worker only. */
    private val oppoReports = LinkedHashMap<String, Pair<AncMode, Long>>()
    private var oppoRescanAt = 0L
    private var oppoProbeAt = 0L

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Protocol.ACTION_OPPO_STATE)
        }
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == Protocol.ACTION_OPPO_STATE) onOppoState(intent) else {
                    invalidate()
                    refresh()
                }
            }
        }, filter, Context.RECEIVER_EXPORTED)
        HookOptions.addListener { worker.post { publish(device, mode) } }
    }

    fun addListener(listener: (AncState) -> Unit) {
        listeners += listener
        listener(state())
        refresh()
    }

    /** The state as last published, for a reader that needs it before its listener is added. */
    fun current(): AncState = state()

    fun removeListener(listener: (AncState) -> Unit) {
        listeners -= listener
    }

    fun refresh() {
        ensureBound()
        worker.removeCallbacks(refreshTask)
        worker.post(refreshTask)
    }

    fun toggle() {
        ensureBound()
        worker.post {
            val target = resolve()
            if (target == null) {
                toast(context.localized("No compatible headset connected", "未找到已连接的兼容耳机"))
                publish(null, AncMode.OFF)
                return@post
            }
            val current = backend.read(target) ?: mode
            val next = next(current)
            Diagnostics.hit("anc_switch", "${backend.javaClass.simpleName}: $current to $next")
            if (!backend.change(target, next)) {
                toast(context.localized("Could not switch the listening mode", "降噪模式切换失败"))
                return@post
            }
            publish(target, next)
            if (HookOptions.current.island) ModeIsland.show(context, next)
            worker.postDelayed(refreshTask, 900)
        }
    }

    fun disconnect() {
        worker.post {
            val target = device?.takeIf { isConnected(it) } ?: resolve()
            if (target == null) {
                toast(context.localized("No headset connected", "未找到已连接的耳机"))
                publish(null, AncMode.OFF)
                return@post
            }
            try {
                val result = DISCONNECT.call(target)
                if (result == false || (result is Int && result != 0)) error("disconnect returned $result")
                worker.postDelayed(refreshTask, 700)
                worker.postDelayed(refreshTask, 1600)
            } catch (t: Throwable) {
                HookLog.e("disconnect failed", t)
                toast(context.localized("Could not disconnect the headset", "断开连接失败"))
            }
        }
    }

    private fun next(current: AncMode): AncMode = when (current) {
        AncMode.NOISE_CANCELLING -> if (backend.supportsTransparency) AncMode.TRANSPARENCY else AncMode.OFF
        AncMode.TRANSPARENCY -> if (HookOptions.current.cycleIncludesOff) AncMode.OFF else AncMode.NOISE_CANCELLING
        AncMode.OFF -> AncMode.NOISE_CANCELLING
    }

    private val refreshTask = Runnable {
        val target = resolve()
        publish(target, target?.let { backend.read(it) } ?: mode)
    }

    private fun state() = AncState(
        available = device != null && HookOptions.current.ancRow,
        mode = mode,
        cycleIncludesOff = HookOptions.current.cycleIncludesOff,
    )

    private fun publish(target: BluetoothDevice?, newMode: AncMode) {
        device = target
        mode = if (target == null) AncMode.OFF else newMode
        val state = state()
        Diagnostics.record(
            "anc", if (state.available) Diagnostics.State.OK else Diagnostics.State.WAITING,
            when {
                !HookOptions.current.ancRow -> "turned off in settings"
                target == null -> "no compatible headset connected"
                else -> "${backend.javaClass.simpleName} mode=${state.mode}"
            },
        )
        main.post { listeners.forEach { it(state) } }
    }

    // ---------------------------------------------------------------- device discovery

    private fun resolve(): BluetoothDevice? {
        if (service == null) return null
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            HookLog.e("SystemUI lacks BLUETOOTH_CONNECT")
            return null
        }
        val startedAt = synchronized(this) { epoch }
        device?.takeIf { isConnected(it) }?.let { cached ->
            if (cached.address == confirmed) return cached
            if (classify(cached)) return cached.also { remember(it, startedAt) }
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        if (!hasAudioConnection(adapter)) return null
        val bonded = runCatching { adapter.bondedDevices }.getOrNull() ?: return null
        return bonded.firstOrNull { isConnected(it) && classify(it) }?.also { remember(it, startedAt) }
    }

    private fun remember(target: BluetoothDevice, startedAt: Int) = synchronized(this) {
        if (epoch == startedAt) confirmed = if (backend.stable) target.address else null
    }

    private fun invalidate() = synchronized(this) {
        epoch++
        confirmed = null
    }

    /** Decides whether [target] has controllable noise control, and sets [backend] when it does. */
    private fun classify(target: BluetoothDevice): Boolean {
        val support = miui.checkSupport(target) ?: return false
        val name = normalizedName(context, target)
        if (!miui.declaresAnc(support)) {
            // Reported by OppoPods itself, so there is nothing to ask it for.
            if (isOppoControlled(target)) return accept(oppo, target, askState = false)
            if (support.isBlank() && airPods.supportsNoiseControl(target)) return accept(airPods, target)
            awaitOppoReport()
            return false
        }
        // Sony headsets get a support string whose first field is their own address.
        if (support.startsWith(target.address + ",", ignoreCase = true)) {
            return if (sony.supportsNoiseControl(target)) accept(sony, target) else false
        }
        name?.let(Huawei::routeFor)?.let { return accept(Huawei(context, it), target) }
        if (name != null && name.contains("oppo")) return accept(oppo, target)
        if (name != null && Huawei.lacksAnc(name)) return false
        return accept(miui, target)
    }

    private fun accept(chosen: Backend, target: BluetoothDevice, askState: Boolean = true): Boolean {
        backend = chosen
        if (chosen is Oppo) {
            oppoReportFor(target)?.let { mode = it.first }
            if (askState) requestOppoStatus()
        }
        return true
    }

    private fun isConnected(target: BluetoothDevice) = IS_CONNECTED.call(target) == true

    /**
     * No connected audio profile means no headset in use, so the isConnected call per bonded device
     * can be skipped. A profile in any other state, or one that cannot be read, still allows it.
     */
    private fun hasAudioConnection(adapter: BluetoothAdapter?): Boolean {
        adapter ?: return false
        return runCatching {
            adapter.isEnabled && AUDIO_PROFILES.any {
                adapter.getProfileConnectionState(it) != BluetoothProfile.STATE_DISCONNECTED
            }
        }.getOrDefault(true)
    }

    // ---------------------------------------------------------------- OppoPods

    private fun onOppoState(intent: Intent) {
        val reported = Oppo.statusToMode(intent.getIntExtra(Protocol.EXTRA_OPPO_STATUS, 0)) ?: return
        val address = intent.getStringExtra(Protocol.EXTRA_OPPO_ADDRESS)
        worker.post {
            val key = address?.uppercase().orEmpty()
            oppoReports.remove(key)
            oppoReports[key] = reported to SystemClock.elapsedRealtime()
            val now = SystemClock.elapsedRealtime()
            oppoReports.entries.removeAll { now - it.value.second > OPPO_FRESH_MS }
            while (oppoReports.size > 4) oppoReports.remove(oppoReports.keys.first())

            val current = device
            if (current == null) {
                // The state may arrive before the headset was classified, for example right after a
                // SystemUI restart: scan again instead of dropping it.
                if (now - oppoRescanAt > OPPO_THROTTLE_MS) {
                    oppoRescanAt = now
                    refresh()
                }
                return@post
            }
            if (!address.isNullOrBlank() && !address.equals(current.address, ignoreCase = true)) return@post
            publish(current, reported)
        }
    }

    /**
     * The address in a fresh report decides; a report without address only counts for a headset
     * named OPPO, since it cannot be attributed otherwise. Stale reports expire, so a removed module
     * cannot keep the row open.
     */
    private fun isOppoControlled(target: BluetoothDevice): Boolean {
        if (oppoReports[target.address.uppercase()]?.let(::fresh) == true) return true
        return oppoReports[""]?.let(::fresh) == true && normalizedName(context, target)?.contains("oppo") == true
    }

    private fun oppoReportFor(target: BluetoothDevice) =
        (oppoReports[target.address.uppercase()] ?: oppoReports[""])?.takeIf(::fresh)

    private fun fresh(report: Pair<AncMode, Long>) = SystemClock.elapsedRealtime() - report.second <= OPPO_FRESH_MS

    private fun awaitOppoReport() = requestOppoStatus()

    private fun requestOppoStatus() {
        val now = SystemClock.elapsedRealtime()
        if (now - oppoProbeAt < OPPO_THROTTLE_MS) return
        oppoProbeAt = now
        oppo.requestStatus()
    }

    // ---------------------------------------------------------------- service binding

    private fun ensureBound() {
        if (service != null || binding) return
        binding = true
        main.post {
            val accepted = runCatching {
                context.bindService(
                    Intent(MiuiHeadset.SERVICE_ACTION).setPackage(MiuiHeadset.SERVICE_PACKAGE),
                    connection, Context.BIND_AUTO_CREATE,
                )
            }.onFailure { HookLog.e("bind headset service", it) }.getOrDefault(false)
            if (!accepted) {
                binding = false
                publish(null, AncMode.OFF)
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            binding = false
            service = binder
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            binding = false
            invalidate()
            publish(null, AncMode.OFF)
        }

        override fun onBindingDied(name: ComponentName) {
            onServiceDisconnected(name)
            ensureBound()
        }
    }

    private fun toast(message: String) = main.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }

    companion object {
        private const val OPPO_FRESH_MS = 120_000L
        private const val OPPO_THROTTLE_MS = 1_500L
        private val AUDIO_PROFILES = intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, BluetoothProfile.LE_AUDIO)
        private val IS_CONNECTED = MethodRef("isConnected")
        private val DISCONNECT = MethodRef("disconnect")

        @Volatile private var instance: AncController? = null

        fun get(context: Context): AncController = instance ?: synchronized(this) {
            instance ?: AncController(context.applicationContext ?: context).also { instance = it }
        }
    }
}
