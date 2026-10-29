package io.github.lcebot.mivolumeenhanced.hook.core

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * A field looked up by name on the declaring class or a superclass, remembered for the last class it
 * was resolved on. Volume panel callbacks run every frame, and getDeclaredField copies the member
 * array on each call. Only the last class is kept, so a reloaded plugin re-resolves instead of
 * pinning the old class loader.
 */
class FieldRef(private vararg val names: String) {
    private class Entry(val owner: Class<*>, val field: Field?)

    @Volatile
    private var entry: Entry? = null

    fun resolve(type: Class<*>): Field? {
        entry?.let { if (it.owner === type) return it.field }
        val found = type.hierarchy().firstNotNullOfOrNull { c ->
            names.firstNotNullOfOrNull { n -> runCatching { c.getDeclaredField(n) }.getOrNull() }
        }?.apply { isAccessible = true }
        entry = Entry(type, found)
        return found
    }

    fun get(target: Any?): Any? {
        target ?: return null
        val field = resolve(target.javaClass) ?: return null
        return runCatching { field.get(target) }.getOrNull()
    }

    fun set(target: Any?, value: Any?): Boolean {
        target ?: return false
        val field = resolve(target.javaClass) ?: return false
        return runCatching { field.set(target, value) }.isSuccess
    }
}

/** Same caching as [FieldRef], for a method with a fixed signature. */
class MethodRef(val name: String, private vararg val parameterTypes: Class<*>) {
    private class Entry(val owner: Class<*>, val method: Method?)

    @Volatile
    private var entry: Entry? = null

    fun resolve(type: Class<*>): Method? {
        entry?.let { if (it.owner === type) return it.method }
        val found = type.hierarchy().firstNotNullOfOrNull { c ->
            runCatching { c.getDeclaredMethod(name, *parameterTypes) }.getOrNull()
        }?.apply { isAccessible = true }
        entry = Entry(type, found)
        return found
    }

    /** Returns false when the method does not exist; exceptions from the method itself propagate. */
    fun invoke(target: Any?, vararg args: Any?): Boolean {
        target ?: return false
        val method = resolve(target.javaClass) ?: return false
        method.invoke(target, *args)
        return true
    }

    fun call(target: Any?, vararg args: Any?): Any? {
        target ?: return null
        return resolve(target.javaClass)?.invoke(target, *args)
    }

    /** For calls whose failure is only worth a log line. */
    fun invokeQuietly(target: Any?, vararg args: Any?) {
        try {
            if (!invoke(target, *args)) HookLog.w("$name is missing on ${target?.javaClass?.name}")
        } catch (t: Throwable) {
            HookLog.w("$name failed", t)
        }
    }
}

fun Class<*>.hierarchy(): Sequence<Class<*>> =
    generateSequence(this) { it.superclass }.takeWhile { it != Any::class.java }

fun Class<*>.method(name: String, parameterCount: Int): Method? =
    declaredMethods.firstOrNull { it.name == name && it.parameterCount == parameterCount }
        ?.apply { isAccessible = true }

fun ClassLoader.find(name: String): Class<*>? =
    runCatching { Class.forName(name, false, this) }.getOrNull()

/** The first non-static field whose type is assignable to [type]. */
fun <T> Any.fieldOfType(type: Class<T>): T? =
    javaClass.declaredFields.firstOrNull { !Modifier.isStatic(it.modifiers) && type.isAssignableFrom(it.type) }
        ?.let { f -> runCatching { f.isAccessible = true; type.cast(f.get(this)) }.getOrNull() }

/** The current process's Application, before any hook has seen it. */
fun currentApplication(): android.content.Context? = runCatching {
    Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication")
        .apply { isAccessible = true }.invoke(null) as? android.content.Context
}.getOrNull()
