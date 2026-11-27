# ArcDrift Benchmarks & Accuracy

ArcDrift is evaluated against **10 pinned open-source repositories** spanning Java Spring Boot, Spring WebFlux, Kotlin Spring Boot, Python FastAPI, Angular, Vue, React TypeScript, OpenAPI 3, and TypeSpec (`*.tsp`).

## Aggregate 10-Repository Corpus Metrics

| Metric | Measured Result |
| --- | --- |
| **Repositories Evaluated** | **10** public OSS codebases (at pinned commits) |
| **Total Source Files Scanned** | **7,637** |
| **Total Graph Nodes & Edges** | **7,634** nodes · **19,956** edges |
| **Producer & Consumer Endpoints** | **1,465** producers · **848** consumers |
| **Linked Cross-Language Contracts** | **433** single-repo (**449** with multi-repo federation) |
| **2-Hop MCP Slice Token Reduction** | **89.1%** mean reduction vs. raw source across all 10 repos |
| **Controlled Mutation Recall** | **8 / 8 (`100%`)** across route typos, response DTO drift, and `@RequestBody` drift |
| **Deterministic Refactoring Accuracy** | **5 / 5 (`100%`)** command completion, **0 PSI syntax errors** across 16 `.kt` files |

## Per-Repository Breakdown

| Repository (Pinned Commit) | Stack | Files | Linked Contracts | Clean-HEAD Findings | 2-Hop Slice Reduction |
| --- | --- | --- | --- | --- | --- |
| `spring-petclinic/spring-petclinic-reactjs @ 4a6c26d` | Java Spring Boot + React (`fetch`) | `118` | `8` | `5` *(real upstream route bugs; `0` FP)* | `71.8%` |
| `spring-petclinic/spring-petclinic-kotlin @ d63e007` | Kotlin Spring Boot `@Controller` | `24` | `0` | `0` | — |
| `spring-petclinic/spring-petclinic-angular @ e992ba8` | Angular (`HttpClient` + `Observable<T>`) | `97` | `7` *(federated)* | `9` federated *(real `Pet.owner` vs `PetDto.ownerId` delta)* | `67.4%` |
| `jhipster/jhipster-sample-app-react @ 8853599` | Java Spring Boot + React Redux (`axios`) | `215` | `11` | `0` | `90.5%` |
| `jhipster/jhipster-sample-app @ 6b000b5` | Java Spring Boot + Angular (`HttpClient`) | `349` | `13` | `2` *(`user.service.ts` + `User.authorities` delta)* | `92.1%` |
| `fastapi/full-stack-fastapi-template @ aabb901` | Python FastAPI + React (`@hey-api/openapi-ts`) | `125` | `15` | `0` | `94.8%` |
| `Netflix/dispatch @ dd2837e` | Python FastAPI + TypeScript/Vue | `664` | `43` | `8` *(external plugin webhook calls)* | `93.4%` |
| `kafbat/kafka-ui @ 73e5d40` | Java Spring WebFlux + TypeSpec (`*.tsp`) + React | `1,050` | `16` | `0` | `96.8%` |
| `gotson/komga @ db2ad64` | Kotlin Spring Boot + Vue + `@hey-api/openapi-ts` | `973` | `42` | `10` *(across 240 consumer endpoints)* | `93.0%` |
| `tolgee/tolgee-platform @ 5fdc27a` | Kotlin Spring Boot + React (`useApiQuery`) | `4,022` | `285` (`294` fed.) | `13` single-repo (`0` with `tolgee-cloud` federation) | `97.0%` |

## Reproducing Locally

The benchmark harness lives in [`GateG4RealWorldBenchmarkTest.kt`](cli/src/test/kotlin/dev/arcdrift/cli/GateG4RealWorldBenchmarkTest.kt) and runs as part of `:arcdrift-cli:test`:

```bash
./gradlew :arcdrift-cli:test --tests "dev.arcdrift.cli.GateG4RealWorldBenchmarkTest"
```
