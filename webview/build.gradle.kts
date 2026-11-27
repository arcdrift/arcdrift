import com.github.gradle.node.npm.task.NpmTask

plugins {
    base
    alias(libs.plugins.node.gradle)
}

node {
    version.set(libs.versions.nodejs)
    download.set(true)
    val sharedNodeDir = file("/tmp/arcdrift-nodejs").takeIf { it.exists() }
        ?: rootProject.layout.buildDirectory.dir("nodejs").get().asFile
    val sharedNpmDir = file("/tmp/arcdrift-npm").takeIf { it.exists() }
        ?: rootProject.layout.buildDirectory.dir("npm").get().asFile
    workDir.set(sharedNodeDir)
    npmWorkDir.set(sharedNpmDir)
}

val buildWebview by tasks.registering(NpmTask::class) {
    dependsOn(tasks.npmInstall)
    args.set(listOf("run", "build"))
    inputs.dir("src")
    inputs.file("package.json")
    inputs.file("tsconfig.json")
    inputs.file("vite.config.ts")
    inputs.file("index.html")
    outputs.dir("dist")
}

val testWebview by tasks.registering(NpmTask::class) {
    dependsOn(tasks.npmInstall)
    args.set(listOf("run", "test"))
    inputs.dir("src")
    inputs.file("package.json")
    inputs.file("tsconfig.json")
    inputs.file("vite.config.ts")
}

tasks.check {
    dependsOn(testWebview)
}

tasks.assemble {
    dependsOn(buildWebview)
}
