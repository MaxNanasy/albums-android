plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

allprojects {
    tasks.register("resolveAllDependencies") {
        doLast {
            configurations
                .filter { it.isCanBeResolved }
                .filter { cfg ->
                    (cfg.name.endsWith("CompileClasspath") || cfg.name.endsWith("RuntimeClasspath")) &&
                    !cfg.name.endsWith("TestCompileClasspath")
                }
                .forEach { cfg ->
                    println("Resolving $path:${cfg.name}")
                    cfg.incoming.files.files
                }
        }
    }
}
