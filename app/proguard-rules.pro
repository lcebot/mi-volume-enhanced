-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list

# Stack traces have to survive minification, because the LSPosed log is the whole of what a user can
# send back when a hook fails. Without these two, a release build's traces are "Unknown Source" all
# the way down and a report is unactionable.
#
# -renamesourcefileattribute replaces the real file names with a single constant, so keeping
# SourceFile does not leak the source tree into the APK: line numbers still map back through the
# mapping file, which is what retrace needs.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The libxposed entry point, kept whole, with its name preserved so it keeps matching the plain
# name in META-INF/xposed/java_init.list. API 102 creates the module through its public no-arg
# constructor and calls the lifecycle callbacks reflectively, which keeping the class whole covers.
-keep class io.github.lcebot.mivolumeenhanced.hook.HookEntry { *; }

# Hook class names appear in the LSPosed log and in the hook ids, so keep them readable.
-keepnames class io.github.lcebot.mivolumeenhanced.hook.** { *; }

# Nothing in the module writes to logcat on purpose; this keeps a stray call out of release builds.
# Problems reach the LSPosed log through XposedInterface.log, which this does not touch.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
    public static *** println(...);
}
