# syntax=docker/dockerfile:1.7

# Stage 1: Build zero-JRE standalone ArcDrift CLI (includes bundled React Flow UI + Tree-sitter grammars)
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /workspace

COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties THIRD-PARTY-NOTICES.md ./
COPY gradle ./gradle
COPY core ./core
COPY mcp ./mcp
COPY webview ./webview
COPY cli ./cli
# Only the Gradle build script is needed for project configuration (host node_modules/dist are excluded by .dockerignore)
COPY docs/build.gradle.kts ./docs/build.gradle.kts

# Gradle 9 requires every included project directory to exist; the IntelliJ plugin module is not part of this image.
RUN mkdir -p ./intellij \
    && chmod +x ./gradlew \
    && ./gradlew :arcdrift-cli:buildJlinkDist --no-daemon

# Stage 2: Minimal glibc runtime image (required for Tree-sitter JNI native bindings)
FROM ubuntu:22.10 AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates libstdc++6 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system arcdrift \
    && useradd --system --gid arcdrift --home-dir /home/arcdrift --create-home arcdrift

COPY --from=builder /workspace/cli/build/jlink/arc /opt/arcdrift

ENV PATH="/opt/arcdrift/bin:${PATH}"

WORKDIR /repo
USER arcdrift

ENTRYPOINT ["arc"]
CMD ["--help"]
