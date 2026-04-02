import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask

plugins {
    base
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

detekt {
    buildUponDefaultConfig = true
    allRules = true
    config.setFrom(files("detekt.yml"))
    baseline = file("detekt-baseline.xml")
}

tasks.withType<Detekt>().configureEach {
    setSource(files(rootDir))
    include("**/*.kt", "**/*.kts")
    exclude("**/build/**", "**/.gradle/**")
    reports {
        html.required.set(true)
        sarif.required.set(true)
        md.required.set(true)
    }
}

tasks.withType<DetektCreateBaselineTask>().configureEach {
    setSource(files(rootDir))
    include("**/*.kt", "**/*.kts")
    exclude("**/build/**", "**/.gradle/**")
}

tasks.named("check") {
    dependsOn(tasks.named("detekt"))
}
