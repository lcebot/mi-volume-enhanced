package io.github.lcebot.mivolumeenhanced.hook.systemui

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.util.SparseBooleanArray
import io.github.lcebot.mivolumeenhanced.hook.core.HookLog
import io.github.lcebot.mivolumeenhanced.hook.core.MethodRef

/**
 * Whether a third-party app is playing media right now, by the same rule MiSound uses for its
 * per-app volume list: uid of an app (10000 and up), not the live wallpaper, player started, and
 * media usage or the music stream.
 *
 * The answer is pushed by AudioPlaybackCallback and cached. The volume panel asks several times per
 * show and while animating; querying each time would mean binder calls to AudioService and
 * PackageManager on SystemUI's main thread.
 */
object PlaybackWatcher {
    private const val WALLPAPER = "com.miui.miwallpaper"
    private const val PLAYER_STATE_STARTED = 2
    private val CLIENT_UID = MethodRef("getClientUid")
    private val PLAYER_STATE = MethodRef("getPlayerState")
    private val wallpaperUids = SparseBooleanArray()

    @Volatile var playing = false
        private set

    @Volatile private var listening = false

    fun listen(context: Context, onChange: () -> Unit) {
        if (listening) return
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val packages = context.packageManager
        try {
            playing = evaluate(audio.activePlaybackConfigurations, packages)
            audio.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                    val now = evaluate(configs, packages)
                    if (now == playing) return
                    playing = now
                    onChange()
                }
            }, Handler(Looper.getMainLooper()))
            listening = true
        } catch (t: Throwable) {
            HookLog.w("playback callback", t)
        }
    }

    private fun evaluate(configs: List<AudioPlaybackConfiguration>?, packages: PackageManager): Boolean =
        configs?.any { isActiveMedia(it, packages) } == true

    /** Cheap checks first; only a remaining candidate costs a package lookup, once per uid. */
    private fun isActiveMedia(config: AudioPlaybackConfiguration, packages: PackageManager): Boolean = try {
        val attributes = config.audioAttributes
        val media = attributes.usage == AudioAttributes.USAGE_MEDIA ||
            attributes.volumeControlStream == AudioManager.STREAM_MUSIC
        media && PLAYER_STATE.call(config) == PLAYER_STATE_STARTED &&
            (CLIENT_UID.call(config) as? Int ?: 0).let { uid -> uid >= 10000 && !isWallpaper(packages, uid) }
    } catch (t: Throwable) {
        HookLog.w("playback configuration", t)
        false
    }

    private fun isWallpaper(packages: PackageManager, uid: Int): Boolean {
        synchronized(wallpaperUids) {
            val index = wallpaperUids.indexOfKey(uid)
            if (index >= 0) return wallpaperUids.valueAt(index)
        }
        val wallpaper = runCatching { packages.getNameForUid(uid) }.getOrNull() == WALLPAPER
        synchronized(wallpaperUids) { wallpaperUids.put(uid, wallpaper) }
        return wallpaper
    }
}
