package io.github.lcebot.mivolumeenhanced.hook.core

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import java.lang.reflect.Executable

/*
 * Thin wrappers over the libxposed hook builder. Exceptions thrown by a hooker are handled by the
 * framework (module.prop sets exceptionMode=protective): the original call still runs.
 *
 * Every hook gets an id. Installing again with the same id replaces the old hook instead of
 * stacking a second one, which matters when the plugin is reloaded into a new class loader and the
 * same install path runs twice.
 */

private const val ID_PREFIX = "mve:"

fun XposedInterface.after(target: Executable, id: String, action: (Chain, Any?) -> Unit): HookHandle =
    hook(target).setId(ID_PREFIX + id).intercept { chain ->
        val result = chain.proceed()
        action(chain, result)
        result
    }

fun XposedInterface.before(target: Executable, id: String, action: (Chain) -> Unit): HookHandle =
    hook(target).setId(ID_PREFIX + id).intercept { chain ->
        action(chain)
        chain.proceed()
    }

fun XposedInterface.around(target: Executable, id: String, hooker: (Chain) -> Any?): HookHandle =
    hook(target).setId(ID_PREFIX + id).intercept { chain -> hooker(chain) }

/** An id that differs per class loader, for plugin classes that can be loaded more than once. */
fun loaderId(name: String, loader: ClassLoader?): String =
    name + "@" + Integer.toHexString(System.identityHashCode(loader))

/** An id per overload, for hooks installed on every method with a given name. */
fun overloadId(name: String, target: Executable): String =
    name + "#" + Integer.toHexString(target.toGenericString().hashCode())

fun Chain.boolArg(index: Int): Boolean = getArg(index) == true
