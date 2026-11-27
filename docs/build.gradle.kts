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

val buildDocs by tasks.registering(NpmTask::class) {
    dependsOn(tasks.npmInstall)
    args.set(listOf("run", "build"))
    inputs.dir("src")
    inputs.dir("public")
    inputs.file("package.json")
    inputs.file("tsconfig.json")
    inputs.file("astro.config.mjs")
    outputs.dir("dist")
}

tasks.check {
    dependsOn(buildDocs)
}

tasks.assemble {
    dependsOn(buildDocs)
}
