package io.github.lcebot.mivolumeenhanced.hook.headset

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import android.util.Base64
import io.github.lcebot.mivolumeenhanced.hook.core.HookLog

enum class AncMode { OFF, NOISE_CANCELLING, TRANSPARENCY }

/** How one kind of headset reports and changes its listening mode. */
sealed interface Backend {
    /** Null when the mode cannot be read; the caller keeps the last known one. */
    fun read(device: BluetoothDevice): AncMode?
    fun change(device: BluetoothDevice, mode: AncMode): Boolean
    val supportsTransparency: Boolean get() = true

    /** True when the classification depends on nothing but the headset itself. */
    val stable: Boolean get() = false
}

/** Xiaomi headsets, through the extension's IMiuiHeadsetService binder. */
class MiuiHeadset(private val binder: () -> IBinder?) : Backend {
    override val stable get() = true

    /** The support string's ANC bit; empty or malformed means the headset has no native ANC. */
    fun declaresAnc(support: String?): Boolean {
        val fields = support?.split(",") ?: return false
        return fields.size == 2 && fields[1].length == 24 && fields[1][16] == '1'
    }

    /** Null when the service call failed; an empty string when the headset declares nothing. */
    fun checkSupport(device: BluetoothDevice): String? =
        transact(TX_CHECK_SUPPORT, { writeTypedObject(device, 0) }) { readString().orEmpty() }

    override fun read(device: BluetoothDevice): AncMode? {
        val cached = transact(TX_COMMON_COMMAND, {
            writeInt(COMMAND_GET_CACHED_INFO)
            writeString("")
            writeTypedObject(device, 0)
        }) { readString() }
        // "mode|...|...|..." for native headsets; anything else belongs to another module.
        val fields = cached?.split("|") ?: return null
        if (fields.size != 4) return null
        return AncMode.entries.getOrNull(fields[0].toIntOrNull() ?: return null)
    }

    override fun change(device: BluetoothDevice, mode: AncMode): Boolean =
        transact(TX_CHANGE_ANC_MODE, {
            writeInt(mode.ordinal)
            writeTypedObject(device, 0)
        }) { true } == true

    private fun <T> transact(code: Int, write: Parcel.() -> Unit, read: Parcel.() -> T): T? {
        val service = binder() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.write()
            if (!service.transact(code, data, reply, 0)) return null
            reply.readException()
            reply.read()
        } catch (t: Throwable) {
            HookLog.w("headset service call $code failed", t)
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    companion object {
        const val SERVICE_ACTION = "miui.bluetooth.mible.BluetoothHeadsetService"
        const val SERVICE_PACKAGE = "com.xiaomi.bluetooth"
        private const val DESCRIPTOR = "com.android.bluetooth.ble.app.IMiuiHeadsetService"
        private const val TX_CHECK_SUPPORT = 1
        private const val TX_CHANGE_ANC_MODE = 9
        private const val TX_COMMON_COMMAND = 14
        private const val COMMAND_GET_CACHED_INFO = 109
    }
}

/** AirPods, through the extension's repository provider (opened to SystemUI by BluetoothHooks). */
class AirPods(private val context: Context) : Backend {
    fun supportsNoiseControl(device: BluetoothDevice): Boolean =
        call("check_feature_support", request(device, "NoiseControl", null))
            ?.getBoolean("extra_feature_support", false) == true

    override fun read(device: BluetoothDevice): AncMode? =
        when (call("get_state", request(device, KEY_ANC, null))?.getString("extra_value")) {
            "01" -> AncMode.OFF
            "02" -> AncMode.NOISE_CANCELLING
            "03" -> AncMode.TRANSPARENCY
            else -> null
        }

    override fun change(device: BluetoothDevice, mode: AncMode): Boolean {
        val value = when (mode) {
            AncMode.OFF -> "01"
            AncMode.NOISE_CANCELLING -> "02"
            AncMode.TRANSPARENCY -> "03"
        }
        return call("send_command", request(device, KEY_ANC, value))?.getInt("extra_command_state", 0) == 1
    }

    private fun request(device: BluetoothDevice, key: String, value: String?) = Bundle().apply {
        putString("extra_key", key)
        if (value != null) putString("extra_value", value)
        putParcelable(BluetoothDevice.EXTRA_DEVICE, device)
    }

    private fun call(arg: String, extras: Bundle): Bundle? = runCatching {
        context.contentResolver.call(URI, "airpodsRepository", arg, extras)
    }.onFailure { HookLog.w("AirPods repository $arg", it) }.getOrNull()

    private companion object {
        val URI: Uri = Uri.parse("content://com.android.bluetooth.ble.app.headsetdata.provider/airpodsRepository")
        const val KEY_ANC = "air_anc"
    }
}

/** Sony headsets driven by SonyPods, which publishes a parcelled snapshot in Settings.Global. */
class Sony(private val context: Context) : Backend {
    fun supportsNoiseControl(device: BluetoothDevice) =
        snapshot(device)?.getBoolean("supports_noise_control", false) == true

    override fun read(device: BluetoothDevice): AncMode? = when (snapshot(device)?.getString("nc_mode")) {
        "NOISE_CANCELLING" -> AncMode.NOISE_CANCELLING
        "AMBIENT_SOUND" -> AncMode.TRANSPARENCY
        "OFF" -> AncMode.OFF
        else -> null
    }

    override fun change(device: BluetoothDevice, mode: AncMode): Boolean = broadcast(context,
        Intent("dev.sonypods.action.command")
            .putExtra("command", "set_noise_control")
            .putExtra("value_string", when (mode) {
                AncMode.OFF -> "OFF"
                AncMode.NOISE_CANCELLING -> "NOISE_CANCELLING"
                AncMode.TRANSPARENCY -> "AMBIENT_SOUND"
            }))

    private fun snapshot(device: BluetoothDevice): Bundle? {
        val encoded = runCatching { Settings.Global.getString(context.contentResolver, "sonypods_state_bus_v1") }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val parcel = Parcel.obtain()
        return try {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            val state = parcel.readBundle(Sony::class.java.classLoader)?.getBundle("sony_state")
                ?.takeIf { it.getBoolean("connected", false) } ?: return null
            val owner = state.getString("device_address")
            if (owner != null && !owner.equals(device.address, ignoreCase = true)) null else state
        } catch (t: Throwable) {
            HookLog.w("SonyPods state unreadable", t)
            null
        } finally {
            parcel.recycle()
        }
    }
}

/**
 * Huawei headsets driven by HuaweiPods. It cannot be queried, so the mode is whatever was last set.
 * The route is picked from the device name, which is the key HuaweiPods uses.
 */
class Huawei(private val context: Context, val route: String) : Backend {
    override val stable get() = true
    override val supportsTransparency get() = route in TRANSPARENCY_ROUTES

    override fun read(device: BluetoothDevice): AncMode? = null

    override fun change(device: BluetoothDevice, mode: AncMode): Boolean {
        val status = when (mode) {
            AncMode.OFF -> 1
            AncMode.NOISE_CANCELLING -> 2
            AncMode.TRANSPARENCY -> 3
        }
        val name = deviceName(context, device)
        return broadcast(context, Intent("chen.action.huaweipods.anc_select")
            .putExtra("status", status).putExtra("address", device.address)
            .putExtra("device_name", name).putExtra("device_route", route)) &&
            broadcast(context, Intent("chen.action.huaweipods.refresh_status")
                .putExtra("address", device.address).putExtra("device_name", name)
                .putExtra("device_route", route))
    }

    companion object {
        private val ROUTES = mapOf(
            "freebuds3" to "HUAWEI_FREEBUDS3",
            "freebuds4e" to "HUAWEI_FREEBUDS4E",
            "freebuds5" to "HUAWEI_FREEBUDS5",
            "freebuds5i" to "HUAWEI_FREEBUDS5I",
            "freebudsse4anc" to "HUAWEI_FREEBUDS_SE4_ANC",
            "freebuds6i" to "HUAWEI_FREEBUDS6I",
            "freebudspro3" to "HUAWEI_FREEBUDS_PRO3",
            "freebudspro4" to "HUAWEI_FREEBUDS_PRO4",
            "freebudspro5" to "HUAWEI_FREEBUDS_PRO5",
            "freebuds7i" to "HUAWEI_FREEBUDS7I",
        )
        private val TRANSPARENCY_ROUTES = setOf(
            "HUAWEI_FREEBUDS5I", "HUAWEI_FREEBUDS_SE4_ANC", "HUAWEI_FREEBUDS6I",
            "HUAWEI_FREEBUDS_PRO3", "HUAWEI_FREEBUDS_PRO5", "HUAWEI_FREEBUDS7I",
        )
        private val WITHOUT_ANC = setOf("freeclip", "freeclip2", "freearc", "eyewear", "eyewear2", "eyewear3")

        fun routeFor(normalizedName: String): String? = ROUTES[normalizedName.removePrefix("huawei")]
        fun lacksAnc(normalizedName: String) = normalizedName.removePrefix("huawei") in WITHOUT_ANC
    }
}

/**
 * OPPO headsets driven by OppoPods. The upstream build (Leaf-lsgtky) and the 1812z fork share
 * the package moe.chenxy.oppopods and the chen.action.oppopods.* broadcasts, so one backend serves
 * both. The mode comes from the state broadcasts the Bluetooth extension forwards.
 */
class Oppo(private val context: Context) : Backend {
    override fun read(device: BluetoothDevice): AncMode? = null

    override fun change(device: BluetoothDevice, mode: AncMode): Boolean = broadcast(context,
        Intent("chen.action.oppopods.anc_select")
            .putExtra("status", modeToStatus(mode))
            .putExtra("address", device.address))

    /** The upstream build only reconnects RFCOMM when asked; without it a dropped link stays silent. */
    fun requestStatus() = broadcast(context,
        Intent("chen.action.oppopods.refresh_status").putExtra("allow_rfcomm_reconnect", true))

    companion object {
        const val ACTION_STATE = "chen.action.oppopods.pods_anc_select"
        const val PACKAGE = "moe.chenxy.oppopods"

        /**
         * 1 off, 2 noise cancelling, 3 transparency, 4 adaptive; 5 to 8 are the cancellation levels
         * only the 1812z fork reports. The panel has no separate state for those.
         */
        fun statusToMode(status: Int): AncMode? = when (status) {
            1 -> AncMode.OFF
            2, 4, 5, 6, 7, 8 -> AncMode.NOISE_CANCELLING
            3 -> AncMode.TRANSPARENCY
            else -> null
        }

        fun modeToStatus(mode: AncMode) = when (mode) {
            AncMode.OFF -> 1
            AncMode.NOISE_CANCELLING -> 2
            AncMode.TRANSPARENCY -> 3
        }
    }
}

/** Third-party headset modules all run inside the Bluetooth process (com.android.bluetooth). */
private fun broadcast(context: Context, intent: Intent): Boolean = runCatching {
    context.sendBroadcast(intent.setPackage("com.android.bluetooth").addFlags(Intent.FLAG_RECEIVER_FOREGROUND))
}.onFailure { HookLog.w("broadcast ${intent.action} failed", it) }.isSuccess

fun deviceName(context: Context, device: BluetoothDevice): String? = runCatching {
    if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
        return null
    }
    device.name?.takeIf { it.isNotBlank() } ?: device.alias
}.getOrNull()

/** Lower case letters and digits only, the form the device-name tables use. */
fun normalizedName(context: Context, device: BluetoothDevice): String? =
    deviceName(context, device)?.lowercase()?.filter { it.isLetterOrDigit() }
