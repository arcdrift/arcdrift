plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.arcdrift.cli.MainKt")
    applicationName = "arc"
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(project(":arcdrift-core"))
    implementation(project(":arcdrift-mcp"))
    implementation(libs.kotlinx.serialization.json)
    api(libs.clikt)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.processResources {
    dependsOn(":arcdrift-webview:buildWebview")
    from(project(":arcdrift-webview").file("dist")) {
        into("webview")
    }
    from(rootProject.file("THIRD-PARTY-NOTICES.md")) {
        into("META-INF")
    }
}

tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version.toString())
    }
}

tasks.distTar {
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
}

val java21Launcher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}

val buildJlinkDist = tasks.register("buildJlinkDist") {
    group = "distribution"
    description = "Builds a self-contained zero-JRE ArcDrift CLI distribution using JDK 21 jlink."
    dependsOn(tasks.jar)

    val jlinkOutDir = layout.buildDirectory.dir("jlink/arc")
    val runtimeClasspath = configurations.runtimeClasspath
    val jarFile = tasks.jar.flatMap { it.archiveFile }
    val appMainClass = application.mainClass

    inputs.files(runtimeClasspath, jarFile)
    outputs.dir(jlinkOutDir)

    doLast {
        val outDir = jlinkOutDir.get().asFile
        val runtimeDir = outDir.resolve("runtime")
        val libDir = outDir.resolve("lib")
        val binDir = outDir.resolve("bin")

        project.delete(outDir)
        libDir.mkdirs()
        binDir.mkdirs()

        val jdkHome = java21Launcher.get().metadata.installationPath.asFile
        val jlinkExeName = if (System.getProperty("os.name").lowercase().contains("win")) "jlink.exe" else "jlink"
        val jlinkBin = jdkHome.resolve("bin").resolve(jlinkExeName)
        require(jlinkBin.isFile) { "Could not locate jlink at ${jlinkBin.absolutePath}" }

        val modules = listOf(
            "java.base",
            "java.logging",
            "java.management",
            "java.xml",
            "java.naming",
            "java.desktop",
            "java.net.http",
            "jdk.httpserver",
            "jdk.crypto.ec",
            "jdk.unsupported"
        ).joinToString(",")

        val proc = ProcessBuilder(
            jlinkBin.absolutePath,
            "--add-modules", modules,
            "--strip-debug",
            "--no-man-pages",
            "--no-header-files",
            "--compress=zip-6",
            "--output", runtimeDir.absolutePath
        ).redirectErrorStream(true).start()
        val jlinkOutput = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        if (exitCode != 0) {
            throw GradleException("jlink failed with exit code $exitCode:\n$jlinkOutput")
        }

        project.copy {
            from(jarFile)
            from(runtimeClasspath)
            into(libDir)
        }

        val mainCls = appMainClass.get()
        val unixScript = binDir.resolve("arc")
        unixScript.writeText(
            """
            #!/usr/bin/env sh
            set -eu
            SOURCE="${'$'}0"
            while [ -L "${'$'}SOURCE" ]; do
              LINK_DIR="${'$'}(CDPATH= cd -- "${'$'}(dirname -- "${'$'}SOURCE")" && pwd)"
              SOURCE="${'$'}(readlink "${'$'}SOURCE")"
              case "${'$'}SOURCE" in
                /*) ;;
                *) SOURCE="${'$'}LINK_DIR/${'$'}SOURCE" ;;
              esac
            done
            SCRIPT_DIR="${'$'}(CDPATH= cd -- "${'$'}(dirname -- "${'$'}SOURCE")" && pwd)"
            APP_HOME="${'$'}(CDPATH= cd -- "${'$'}SCRIPT_DIR/.." && pwd)"
            exec "${'$'}APP_HOME/runtime/bin/java" -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp "${'$'}APP_HOME/lib/*" $mainCls "${'$'}@"
            """.trimIndent() + "\n",
            Charsets.UTF_8
        )
        unixScript.setExecutable(true, false)

        val winScript = binDir.resolve("arc.bat")
        winScript.writeText(
            """
            @echo off
            setlocal
            set "SCRIPT_DIR=%~dp0"
            for %%I in ("%SCRIPT_DIR%..") do set "APP_HOME=%%~fI"
            "%APP_HOME%\runtime\bin\java.exe" -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -cp "%APP_HOME%\lib\*" $mainCls %*
            set "ARC_EXIT_CODE=%ERRORLEVEL%"
            endlocal & exit /b %ARC_EXIT_CODE%
            """.trimIndent() + "\r\n",
            Charsets.UTF_8
        )
    }
}

/**
 * Release platform classifier (`linux-x86_64`, `linux-arm64`, `darwin-arm64`, `darwin-x86_64`, `windows-x86_64`)
 * matching the asset names expected by `action.yml` and `packaging/homebrew/arc.rb`.
 * Override with `-ParcdriftPlatform=<os>-<arch>` when cross-packaging.
 */
val arcdriftPlatform: String = providers.gradleProperty("arcdriftPlatform").orNull ?: run {
    val osName = System.getProperty("os.name").lowercase()
    val os = when {
        osName.contains("mac") || osName.contains("darwin") -> "darwin"
        osName.contains("win") -> "windows"
        else -> "linux"
    }
    val arch = when (System.getProperty("os.arch").lowercase()) {
        "aarch64", "arm64" -> "arm64"
        else -> "x86_64"
    }
    "$os-$arch"
}

val jlinkDistTar = tasks.register<Tar>("jlinkDistTar") {
    group = "distribution"
    description = "Archives the zero-JRE jlink ArcDrift CLI distribution as arc-standalone-<version>-<os>-<arch>.tar.gz."
    dependsOn(buildJlinkDist)
    archiveBaseName.set("arc-standalone")
    archiveClassifier.set(arcdriftPlatform)
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
    from(layout.buildDirectory.dir("jlink")) {
        // Gradle 9 archive tasks no longer carry over POSIX modes reliably; keep launcher/runtime executables runnable.
        eachFile {
            if (!isDirectory && file.canExecute()) {
                permissions { unix("rwxr-xr-x") }
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    inputs.dir(project(":arcdrift-docs").file("src"))
    inputs.file(project(":arcdrift-docs").file("astro.config.mjs"))
    inputs.file(project(":arcdrift-webview").file("src/types/bridge.ts"))
}
