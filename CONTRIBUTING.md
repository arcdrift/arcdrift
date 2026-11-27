# Contributing to ArcDrift

Thank you for contributing to ArcDrift!

## Prerequisites

- **JDK 21** (required for Kotlin JVM toolchain and IntelliJ Platform Gradle plugin)
- **Node.js 22.23+ (CI uses 24 LTS)** and `npm` (for `arcdrift-webview` and `arcdrift-docs`)

## Building & Testing Locally

Run all unit, integration, and documentation-sync tests before opening a pull request:

```bash
# 1. Webview unit tests, TypeScript check, and Vite bundle
cd webview
npm ci
npm test
npm run build
cd ..

# 2. Documentation site build
cd docs
npm ci
npm run build
cd ..

# 3. Full Gradle test suite (core, mcp, intellij, cli)
./gradlew test

# 4. IntelliJ plugin package & Marketplace compatibility verification
./gradlew :arcdrift-intellij:buildPlugin :arcdrift-intellij:verifyPlugin
```

## Key Conventions

- **Documentation Parity (`DocumentationSyncTest`):** CLI commands/options, MCP tools, and Webview bridge message types are verified against `arcdrift-docs` by `DocumentationSyncTest` in `:arcdrift-cli`. Update the corresponding docs page whenever you change public CLI flags, MCP tools, or bridge messages.
- **No `!!` Non-Null Assertions:** Use safe calls, `requireNotNull`, or early returns in Kotlin; avoid unsafe `as any` or non-null assertions (`!.`) in TypeScript.
- **Clean-Room Dependencies:** `:arcdrift-core` and `:arcdrift-intellij` must only depend on Apache-2.0 / MIT-compatible libraries and public IntelliJ Platform APIs.
- **Single-Responsibility Modules:** Keep UI components and AST/scanner/refactoring classes focused and accompanied by unit tests.
