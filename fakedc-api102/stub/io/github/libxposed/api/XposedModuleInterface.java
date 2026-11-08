package io.github.libxposed.api;

/**
 * Compile-only stub mirroring the REAL libxposed API 102 param interfaces.
 * Method set matches what a real API 102 module (HyperCeiler) invokes.
 */
public interface XposedModuleInterface {

    interface ModuleLoadedParam {
        String getProcessName();

        boolean isSystemServer();
    }

    /**
     * Default classloader ready (API 29+). Exposes getDefaultClassLoader(),
     * NOT getClassLoader() (that one is on PackageReadyParam).
     */
    interface PackageLoadedParam {
        String getPackageName();

        boolean isFirstPackage();

        android.content.pm.ApplicationInfo getApplicationInfo();

        ClassLoader getDefaultClassLoader();
    }

    /** Custom classloader + AppComponentFactory ready. */
    interface PackageReadyParam {
        String getPackageName();

        boolean isFirstPackage();

        android.content.pm.ApplicationInfo getApplicationInfo();

        ClassLoader getClassLoader();
    }

    /** System server is about to start critical services. */
    interface SystemServerStartingParam {
        ClassLoader getClassLoader();
    }
}
