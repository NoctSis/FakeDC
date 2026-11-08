package io.github.libxposed.api;

import java.lang.reflect.Executable;

import io.github.libxposed.api.XposedInterface.HookBuilder;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

/**
 * Compile-only stub mirroring the REAL libxposed API 102 base class.
 *
 * Real signature (from HyperCeiler bytecode + official docs):
 *   public abstract class XposedModule extends XposedInterfaceWrapper
 *       implements XposedModuleInterface
 *   - framework instantiates via public no-arg constructor
 *   - hook()/deoptimize()/log() inherited from XposedInterface
 *
 * So the module subclass must NOT declare a Context-based constructor.
 */
public abstract class XposedModule implements XposedInterface {

    public XposedModule() {
    }

    @Override public HookBuilder hook(Executable origin) {
        return null; // provided by framework at runtime
    }

    @Override public boolean deoptimize(Executable origin) {
        return false;
    }

    @Override public android.content.SharedPreferences getRemotePreferences(String group) {
        return null; // provided by framework at runtime
    }

    @Override public void log(int priority, String tag, String message) {
    }

    @Override public void log(String tag, String message) {
    }

    public void onModuleLoaded(ModuleLoadedParam param) {
    }

    public void onPackageLoaded(PackageLoadedParam param) {
    }

    public void onPackageReady(PackageReadyParam param) {
    }

    public void onSystemServerStarting(SystemServerStartingParam param) {
    }
}
