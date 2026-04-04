import java.io.File
import java.util.Properties
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration

/**
 * Usage:
 *   ./gradlew -I gradle/cache-all-dependencies-java-sdkmanager.init.gradle.kts cacheAllDependencies
 */

private const val SDKMANAGER_MAIN_CLASS = "com.android.sdklib.tool.sdkmanager.SdkManagerCli"

fun Any.invokeZeroArg(name: String): Any? =
    javaClass.methods
        .firstOrNull { it.name == name && it.parameterCount == 0 }
        ?.invoke(this)

fun String.extractVersionLikeValue(): String =
    Regex("""\d+(?:\.\d+)+""")
        .find(this)
        ?.value
        ?: this

fun readLocalPropertiesSdkDir(rootProject: Project): String? {
    val localPropertiesFile = rootProject.file("local.properties")
    if (!localPropertiesFile.isFile) {
        return null
    }

    val properties = Properties()
    localPropertiesFile.inputStream().use(properties::load)
    return properties.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }
}

fun Project.requiredAndroidSdkPackages(): Set<String> {
    val androidExtension = extensions.findByName("android") ?: return emptySet()

    val compileSdkValue =
        when (val value = androidExtension.invokeZeroArg("getCompileSdk")) {
            is Int -> value.toString()
            is Number -> value.toInt().toString()
            is String -> value.takeIf { it.isNotBlank() }
            else -> (androidExtension.invokeZeroArg("getCompileSdkPreview") as? String)?.takeIf { it.isNotBlank() }
        }

    val buildToolsVersion =
        listOf("getBuildToolsVersion", "getBuildToolsRevision")
            .asSequence()
            .mapNotNull { methodName ->
                androidExtension
                    .invokeZeroArg(methodName)
                    ?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?.extractVersionLikeValue()
            }
            .firstOrNull()

    return buildSet {
        val numericCompileSdk = compileSdkValue?.toIntOrNull()
        when {
            numericCompileSdk != null -> {
                add("platforms;android-$numericCompileSdk")
                add("build-tools;${buildToolsVersion ?: "$numericCompileSdk.0.0"}")
            }
            compileSdkValue != null -> {
                logger.warn(
                    "Skipping Android SDK platform/build-tools inference for ${path} because compileSdk '$compileSdkValue' is not numeric.",
                )
            }
        }
    }
}

fun findSdkRoot(rootProject: Project): String =
    sequenceOf(
        readLocalPropertiesSdkDir(rootProject),
        System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() },
        System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() },
    ).filterNotNull()
        .firstOrNull()
        ?: throw GradleException(
            "Android SDK not found. Set sdk.dir in local.properties or set ANDROID_SDK_ROOT or ANDROID_HOME.",
        )

fun findSdkManagerClasspathJar(rootProject: Project): Pair<File, String> {
    val sdkRoot = findSdkRoot(rootProject)
    val sdkRootDir = File(sdkRoot)
    val cmdlineToolsDir = sdkRootDir.resolve("cmdline-tools")

    val candidates = buildList {
        add(cmdlineToolsDir.resolve("latest/lib/sdkmanager-classpath.jar"))
        add(cmdlineToolsDir.resolve("lib/sdkmanager-classpath.jar"))

        if (cmdlineToolsDir.isDirectory) {
            cmdlineToolsDir
                .listFiles()
                ?.filter(File::isDirectory)
                ?.sortedBy { it.name }
                ?.forEach { directory ->
                    if (directory.name != "latest") {
                        add(directory.resolve("lib/sdkmanager-classpath.jar"))
                    }
                }
        }
    }

    val classpathJar = candidates.firstOrNull(File::isFile)
        ?: throw GradleException(
            "Unable to find sdkmanager-classpath.jar under '$sdkRoot'. Expected one of: ${candidates.joinToString()}.",
        )

    return classpathJar to sdkRoot
}

fun installAndroidSdkPackages(project: Project, packages: Set<String>) {
    if (packages.isEmpty()) {
        return
    }

    val (sdkManagerClasspathJar, sdkRoot) = findSdkManagerClasspathJar(project.rootProject)
    project.logger.lifecycle("Installing Android SDK packages via Java: ${packages.joinToString(", ")}")

    project.javaexec {
        environment("ANDROID_SDK_ROOT", sdkRoot)
        environment("ANDROID_HOME", sdkRoot)
        classpath(project.files(sdkManagerClasspathJar))
        mainClass.set(SDKMANAGER_MAIN_CLASS)
        args(listOf("--sdk_root=$sdkRoot", "--install") + packages.sorted())
    }
}

fun resolveConfigurations(project: Project, scope: String, configurations: Iterable<Configuration>) {
    configurations
        .filter(Configuration::isCanBeResolved)
        .sortedBy(Configuration::getName)
        .forEach { configuration ->
            project.logger.lifecycle("Resolving $scope configuration ${project.path}:${configuration.name}")
            configuration.resolve()
        }
}

gradle.projectsEvaluated {
    rootProject.tasks.register("cacheAllDependencies") {
        group = "build setup"
        description =
            "Downloads and caches all resolvable root and module dependencies, plus required Android SDK platform/build-tools packages."

        doLast {
            val androidSdkPackages =
                rootProject.allprojects
                    .flatMap { project -> project.requiredAndroidSdkPackages().toList() }
                    .toSortedSet()

            installAndroidSdkPackages(rootProject, androidSdkPackages)

            rootProject.allprojects
                .sortedBy(Project::getPath)
                .forEach { project ->
                    resolveConfigurations(project, "buildscript", project.buildscript.configurations)
                    resolveConfigurations(project, "project", project.configurations)
                }
        }
    }

    rootProject.tasks.register("downloadAndCacheAllDependencies") {
        group = "build setup"
        description = "Alias for cacheAllDependencies."
        dependsOn("cacheAllDependencies")
    }
}
