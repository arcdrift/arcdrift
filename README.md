# ⚡ ArcDrift — The AI Architecture Control Plane & Spec-and-Verify Engine

> *"Illuminate your architecture. Keep AI coding agents on the rails."*

**ArcDrift** is a polyglot architecture control plane, cross-language contract verification engine, deterministic PSI refactoring adapter, and Model Context Protocol (MCP) server for **Unified IntelliJ IDEA (`2025.3+`)** and headless **CI/CD & Coding Agent** workflows.

- 🔁 **Catch architectural drift in every PR** — dependency cycles, `.arc.mmd` boundary-rule violations, and blast-radius diffs rendered as Mermaid.
- 🔗 **Catch cross-language contract drift** — REST/Kafka DTO mismatches between Kotlin/Java producers and TypeScript/Python consumers (plus OpenAPI & TypeSpec).
- 🤖 **Give AI coding agents an architecture-aware MCP server** — slices, structural diff proposals, AST-safe refactorings, and self-verification.

---

## ⏱️ Quickstart

### 1. GitHub Action (PR guardrails in 2 minutes)

```yaml
# .github/workflows/architecture.yml
name: Architecture
on: [pull_request]
jobs:
  arcdrift:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      security-events: write
    steps:
      - uses: actions/checkout@v4
      - uses: arcdrift/arcdrift@v0.1.0
        with:
          format: sarif
          output: arcdrift.sarif
      - uses: github/codeql-action/upload-sarif@v3
        if: always()
        with:
          sarif_file: arcdrift.sarif
```

### 2. Standalone CLI (no JDK required)

Download `arc-standalone-<version>-<os>-<arch>.tar.gz` for Linux / macOS (x86_64 & arm64) from the [Releases](https://github.com/arcdrift/arcdrift/releases) page, verify it against `SHA256SUMS.txt`, then:

```bash
tar -xzf arc-standalone-0.1.0-linux-x86_64.tar.gz
./arc/bin/arc scan . --output architecture.arc.mmd   # snapshot your architecture as a Git-native Mermaid spec
./arc/bin/arc check .                                # cycles + boundary rules + cross-language contracts
./arc/bin/arc ui .                                   # interactive C4 explorer at http://127.0.0.1:4747
```

Or run it via Docker: `docker build -t arcdrift . && docker run --rm -v "$PWD:/repo" arcdrift check /repo`.

### 3. MCP server for Claude Code / Cursor / Windsurf

```json
{
  "mcpServers": {
    "arcdrift": { "command": "/path/to/arc/bin/arc", "args": ["mcp", "."] }
  }
}
```

### Exit codes

| Code | Meaning |
| :---: | :--- |
| `0` | No cycles, boundary violations, or contract drift |
| `1` | Architectural drift detected (blocks CI) |
| `2` | Invalid arguments or configuration |

---

## 1. Monorepo Architecture

| Module | Description |
| :--- | :--- |
| `arcdrift-core` | Pure Kotlin semantic graph (`ArcGraph`), lock-free parallel + SHA-256 cached AST scanner (`PolyglotRepoScanner` using `javaparser-core`, `tree-sitter-kotlin`, `tree-sitter-typescript`, `tree-sitter-tsx`, and `tree-sitter-python` with `.arcdriftignore` path/host/route rules), Git-native `.arc.mmd` Mermaid bidirectional codec, Tarjan SCC cycle detector, Blast-Radius Differ, Boundary Rule Engine, ArchUnit JUnit 5 code generator, and Cross-Language Contract Bridge (REST/Kafka across Kotlin/Java, FastAPI, TypeScript, and Python with `@JsonProperty` / `@SerialName` wire-alias and cross-file DTO resolution). |
| `arcdrift-mcp` | JSON-RPC 2.0 Model Context Protocol (MCP) Server (`ArcDriftMcpServer`) exposing strictly the 4 architectural governance tools (`get_architecture_slice`, `propose_structural_diff`, `execute_deterministic_refactoring`, `verify_architectural_compliance`) over `stdio`, plus the in-IDE `OpenAiCompatibleAgentClient` for 1-click contract and boundary auto-repair. |
| `arcdrift-webview` | TypeScript 5.8 + React 18 + `@xyflow/react` v12 + `@dagrejs/dagre` (100% MIT) interactive C4 Spec-and-Verify Canvas and docked Self-Healing Agent Workspace (`AgentMarkdownRenderer` with collapsible thought traces and clickable `<FileChip />` navigation), built hermetically via Gradle (`com.github.node-gradle.node`). |
| `arcdrift-intellij` | Clean-room Apache 2.0 IntelliJ Platform plugin (`2025.3+`, build `253+`) providing UAST + `KtPsiFileExtractor` graph extraction, conflict-free `FileDocumentManager` + `WriteCommandAction` / `RenameProcessor` refactoring, real-time editor boundary inspections (`ArcBoundaryInspection`), persistent `PropertiesComponent` + `PasswordSafe` agent settings, and an embedded `JBCefBrowser` canvas (`http://arcdrift.local/index.html`). |
| `arcdrift-cli` | Standalone `arc` command-line executable (`arc scan`, `arc check`, `arc diff`, `arc slice`, `arc mcp`, `arc ui`) with bundled browser React Flow explorer, zero-JRE `jlink` packaging, multi-stage `Dockerfile`, official GitHub Action (`action.yml`), and Homebrew formula (`packaging/homebrew/arc.rb`). |
| `arcdrift-docs` | Astro v7 (`v7.4.12`) + Starlight (`v0.43.1`) end-user documentation site with interactive fullscreen lightbox screenshots and automated code-to-docs synchronization tests (`DocumentationSyncTest`). |

---

## 2. Building & Running Tests

The repository includes a self-contained Gradle wrapper (`./gradlew`) that automatically provisions Node.js (`v24.22.3`) for the webview and documentation builds and targets JDK 21:

```bash
# Run core, MCP, CLI (including DocumentationSyncTest), and Webview test suites
./gradlew :arcdrift-core:test :arcdrift-mcp:test :arcdrift-cli:test :arcdrift-webview:testWebview --no-daemon

# Build the Astro + Starlight documentation site into docs/dist/
./gradlew :arcdrift-docs:buildDocs --no-daemon

# Build the standalone `arc` CLI distribution (requires JDK) or zero-JRE jlink bundle
./gradlew :arcdrift-cli:installDist --no-daemon
./gradlew :arcdrift-cli:buildJlinkDist --no-daemon

# Compile and package the IntelliJ Platform plugin (.zip)
./gradlew :arcdrift-intellij:buildPlugin --no-daemon
```

---

## 3. Using the `arc` CLI & MCP Server

After running `./gradlew :arcdrift-cli:installDist`, the `arc` binary is available at `cli/build/install/arc/bin/arc`:

```bash
# 1. Scan a polyglot repository into a Git-native .arc.mmd Mermaid spec
./cli/build/install/arc/bin/arc scan . --format mmd --output architecture.arc.mmd

# 2. Verify architectural compliance (cycles, boundary rules, TypeScript/Python DTO contracts)
#    and auto-generate a JUnit 5 ArchUnit test class
./cli/build/install/arc/bin/arc check . \
  --spec architecture.arc.mmd \
  --base-package com.acme \
  --generate-archunit src/test/kotlin/com/acme/ArcDriftArchitectureTest.kt

# 3. Generate a GitHub/GitLab Pull Request Blast-Radius Markdown diff
./cli/build/install/arc/bin/arc diff baseline.arc.mmd . --format markdown --depth 3 --output pr-comment.md

# 4. Extract a 2-hop C4 L3 slice around a specific component or package
./cli/build/install/arc/bin/arc slice . --scope Order --level L3_COMPONENT --depth 2

# 5. Start the stdio MCP Server for Claude Code, Cursor, or Antigravity
./cli/build/install/arc/bin/arc mcp .

# 6. Launch the local 127.0.0.1 interactive browser architecture explorer (IDE-agnostic)
./cli/build/install/arc/bin/arc ui . --port 4747
```

### Connecting to Claude Code / Cursor (`mcp.json`)

```json
{
  "mcpServers": {
    "arcdrift": {
      "command": "./cli/build/install/arc/bin/arc",
      "args": ["mcp", "."]
    }
  }
}
```

---

## 4. Contributing, Security & License

- See [`CONTRIBUTING.md`](CONTRIBUTING.md) for development workflow and [`SECURITY.md`](SECURITY.md) for vulnerability reporting.
- Licensed under the [Apache License 2.0](LICENSE). Third-party components are listed in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).
