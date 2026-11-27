plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly(kotlin("stdlib"))
    testImplementation(kotlin("stdlib"))
    api(libs.kotlinx.serialization.json)
    implementation(libs.javaparser.core)
    implementation(libs.treesitter.core)
    implementation(libs.treesitter.kotlin)
    implementation(libs.treesitter.typescript)
    implementation(libs.treesitter.tsx)
    implementation(libs.treesitter.python)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
    useJUnitPlatform()
}
