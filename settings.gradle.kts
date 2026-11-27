rootProject.name = "arcdrift"

include(
    ":arcdrift-core",
    ":arcdrift-mcp",
    ":arcdrift-webview",
    ":arcdrift-cli",
    ":arcdrift-intellij",
    ":arcdrift-docs"
)

// Gradle project names keep the `arcdrift-` prefix (unambiguous artifact/jar names such as
// `arcdrift-core.jar`), while the on-disk directories use the short module name.
listOf("core", "mcp", "webview", "cli", "intellij", "docs").forEach { module ->
    project(":arcdrift-$module").projectDir = file(module)
}
