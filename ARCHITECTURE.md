# ArcDrift Architecture

ArcDrift is a polyglot architecture control plane, cross-language contract verifier, Model Context Protocol (MCP) server, and IntelliJ IDEA plugin.

## Module Map

| Module | Role | Key Packages |
| --- | --- | --- |
| `:arcdrift-core` | Pure-JVM domain model, AST/PSI parsers, repo scanner, contract bridge, cycle/rule engines, deterministic disk refactoring, SARIF/Sonar reporting, and `ServiceLoader` SPI | `dev.arcdrift.core.{model,ast,scanner,contracts,analysis,rules,codec,refactoring,reporting,spi}` |
| `:arcdrift-mcp` | JSON-RPC 2.0 MCP server (`stdio`) and OpenAI-compatible Self-Healing Agent client (`AgentHttpTransport`, `AgentToolExecutor`) | `dev.arcdrift.mcp.{protocol,server,transport,agent}` |
| `:arcdrift-cli` | Clikt CLI (`arc scan`, `arc check`, `arc diff`, `arc slice`, `arc mcp`, `arc ui`), local `127.0.0.1` browser UI server (`LocalBrowserUiServer`), zero-JRE `jlink` packaging, and PR Markdown reporter | `dev.arcdrift.cli`, `dev.arcdrift.cli.{reporter,ui}` |
| `:arcdrift-webview` | React + `@xyflow/react` + Dagre interactive C4 canvas, `BoundaryRuleModal`, `AgentWorkspacePanel`, and `AgentMarkdownRenderer` | `src/components/{canvas,markdown}`, `src/bridge`, `src/layout` |
| `:arcdrift-intellij` | JetBrains Platform plugin (`dev.arcdrift.guardrails`) using public IntelliJ UAST/Kotlin PSI APIs, JCEF canvas, debounced VFS auto-refresh, and native editor navigation | `dev.arcdrift.intellij.{psi,refactoring,inspection,actions,service,ui}` |
| `:arcdrift-docs` | Astro / Starlight documentation site (validated by `DocumentationSyncTest` in `:arcdrift-cli`) | `src/content/docs` |

## Clean-Room AST & PSI Pipeline

ArcDrift avoids regex-based code parsing and uses permissive-licensed official parsers:

- **Java:** `com.github.javaparser:javaparser-core` (headless) + IntelliJ UAST (`UastGraphExtractor` / `UastClassExtractor`)
- **Kotlin:** `io.github.bonede:tree-sitter-kotlin` (headless, lock-free) + IntelliJ `KtPsiFileExtractor`
- **TypeScript / TSX:** `io.github.bonede:tree-sitter-typescript`
- **Python:** `io.github.bonede:tree-sitter-python`
- **Specs & Schemas:** `ArcMermaidParser` (`.arc.mmd`), `OpenApiAndTypeSpecExtractor` (`openapi.yaml` / `*.tsp`)

## Core Analysis & Contract Pipeline

1. **Scan:** `PolyglotRepoScanner` parses source files in parallel with a SHA-256 incremental per-file AST cache (`RepoAstCache`), filters paths/hosts/routes via `.arcdriftignore` (`ArcDriftIgnore`), loads OpenAPI/TypeSpec definitions and `.arc.mmd` specs, enriches TypeScript/Python consumers (`RepoContractEnricher`), and builds structural edges (`RepoEdgeBuilder`).
2. **Contract Bridge:** `PolyglotContractBridge` links REST/gRPC/Kafka producers to TypeScript and Python consumers and flags route, HTTP verb, `@JsonProperty`/`@SerialName` wire-alias, response DTO, and `@RequestBody` drift.
3. **Graph & Rules:** `TarjanCycleDetector` annotates strongly connected components; `ArcRuleEngine` evaluates `FORBIDDEN`, `ALLOWED_ONLY`, and `MUST_DEPEND_VIA_INTERFACE` boundary rules.
4. **Refactoring:** `InMemoryGraphRefactoringAdapter` + `DiskSourceRewriter` execute deterministic refactorings (`EXTRACT_INTERFACE`, `RENAME_SYMBOL`, `MOVE_CLASS_OR_PACKAGE`, `CREATE_NODE`, `ADD_DEPENDENCY`) wrapped in `DiskSourceTransaction` for atomic multi-file rollback.

## Extension Points (`dev.arcdrift.core.spi`)

`:arcdrift-core` exposes `ServiceLoader`-discovered interfaces in [`ArcDriftSpi.kt`](core/src/main/kotlin/dev/arcdrift/core/spi/ArcDriftSpi.kt):

- `ContractRegistryProvider` — load upstream federated `.arc-contracts.json` manifests and publish producer manifests.
- `PolicyPackProvider` — supply organization-wide `.arc.mmd` boundary rules.
- `ComplianceTelemetrySink` — record compliance check summaries.

When no external SPI providers are on the classpath, ArcDrift operates 100% locally with zero external dependencies.

## IntelliJ JCEF ↔ Webview Bridge

`JcefCanvasPanel` serves the compiled `:arcdrift-webview` bundle over a `127.0.0.1` loopback server / classpath resource handler (`WebviewAssetServer`) and communicates two-way via `JBCefJSQuery`:

- **Webview → IDE (`OutgoingWebviewMessage`):** `REQUEST_INITIAL_GRAPH`, `APPLY_REFACTORING`, `SAVE_SPEC_RULE`, `NAVIGATE_TO_SOURCE`, `CHANGE_ZOOM_LEVEL`, `SAVE_AGENT_CONFIG`, `FETCH_AGENT_MODELS`, `CANCEL_AGENT_RUN`, `HANDOFF_TO_AGENT`
- **IDE → Webview (`IncomingIdeMessage`):** `GRAPH_UPDATED`, `DIFF_UPDATED`, `THEME_CHANGED`, `NOTIFICATION`, `AGENT_CONFIG_LOADED`, `AGENT_MODELS_LOADED`, `AGENT_STEP`, `AGENT_PROGRESS`
