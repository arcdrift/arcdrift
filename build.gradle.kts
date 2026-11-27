plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.intellij.platform) apply false
    alias(libs.plugins.node.gradle) apply false
}

allprojects {
    group = "dev.arcdrift"
    version = providers.gradleProperty("arcdriftVersion").getOrElse("0.1.0")

    repositories {
        mavenCentral()
    }
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "jacoco")
        tasks.withType<JacocoReport>().configureEach {
            dependsOn(tasks.named("test"))
            reports {
                xml.required.set(true)
                html.required.set(true)
            }
        }
        tasks.withType<Test>().configureEach {
            extensions.configure<JacocoTaskExtension> {
                isIncludeNoLocationClasses = true
                includes = listOf("dev.arcdrift.*")
            }
            finalizedBy(tasks.named("jacocoTestReport"))
        }
    }
}
