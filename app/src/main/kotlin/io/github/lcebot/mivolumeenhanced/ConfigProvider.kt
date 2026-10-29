package io.github.lcebot.mivolumeenhanced

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle

/** Answers the hooked processes' "get" call with the current options. It exposes nothing else. */
class ConfigProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != Protocol.CONFIG_METHOD_GET) return null
        return Prefs(context ?: return null).options.toBundle()
    }

    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?) = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
}
