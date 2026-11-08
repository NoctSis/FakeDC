package io.github.libxposed.api;

import java.lang.reflect.Executable;
import java.util.List;

/**
 * Compile-only stub mirroring the REAL libxposed API 102 surface.
 *
 * The method/nested-type layout matches exactly what a real working API 102
 * module (HyperCeiler) references in its bytecode:
 *   - Lio/github/libxposed/api/XposedInterface$Chain;
 *   - Lio/github/libxposed/api/XposedInterface$Hooker;
 *   - Lio/github/libxposed/api/XposedInterface$HookBuilder;
 *   - Lio/github/libxposed/api/XposedInterface$HookHandle;
 *   - XposedInterface.hook(Ljava/lang/reflect/Executable;)
 *   - XposedInterface.deoptimize(Ljava/lang/reflect/Executable;)
 *
 * The framework provides the real implementation at runtime; the module's dex
 * must NOT contain these classes. So a stub with matching signatures is enough
 * to compile, and the emitted bytecode links against the runtime framework.
 *
 * KEY CORRECTION vs. the earlier broken stub:
 *   - The real Chain has NO setArg()/setResult().
 *   - To override call arguments you pass a new array to proceed()/proceedWith().
 */
public interface XposedInterface {

    /** Register a hook on a method/constructor. Takes a java.lang.reflect.Executable. */
    HookBuilder hook(Executable origin);

    /** Disable JIT inlining of a method so that hooks reliably fire. */
    boolean deoptimize(Executable origin);

    /** Cross-process access to the module's own SharedPreferences (API 102 remote prefs). */
    android.content.SharedPreferences getRemotePreferences(String group);

    void log(int priority, String tag, String message);

    void log(String tag, String message);

    interface HookBuilder {
        HookBuilder setId(String id);
        HookBuilder setPriority(int priority);
        HookBuilder setExceptionMode(ExceptionMode mode);
        HookHandle intercept(Hooker hooker);
    }

    interface Hooker {
        Object intercept(Chain chain) throws Throwable;
    }

    /**
     * Call context. getArgs() returns an IMMUTABLE list; to change arguments
     * build a new Object[] and hand it to proceed()/proceedWith().
     */
    interface Chain {
        Object getThisObject();

        Object getArg(int index);

        List<Object> getArgs();

        Executable getExecutable();

        Object proceed() throws Throwable;

        /** Continue with new arguments, same `this`. */
        Object proceed(Object[] newArgs) throws Throwable;

        /** Continue with a new `this`, same arguments. */
        Object proceedWith(Object thisObject) throws Throwable;

        /** Continue with a new `this` and new arguments. */
        Object proceedWith(Object thisObject, Object[] newArgs) throws Throwable;
    }

    interface HookHandle {
        Executable getExecutable();

        String getId();

        void unhook();

        void replaceHook(Hooker newHooker);
    }

    enum ExceptionMode {
        DEFAULT
    }
}
