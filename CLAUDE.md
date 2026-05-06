# ants-platform-java — Claude Code orientation

Java SDK for the Agentic Ants platform. Maven Central artifact `ai.agenticants:ants-platform-java`, currently `0.1.3`. Two surfaces:

1. **Fern-generated REST client** (`com.ants.platform.client.*`) — typed wrappers over every `api.agenticants.ai` REST endpoint.
2. **Hand-written Guardrails / Tracer module** (`com.ants.platform.guardrails.*`) — opinionated client for the AI-policy / guardrails APIs with violation exception types and trace utilities.

Single Maven module. Java 8+ runtime target. OkHttp + Jackson + JUnit 5.

## Layout

```
src/main/java/com/ants/platform/
  client/
    AntsPlatformClient.java          Top-level entrypoint.
    AntsPlatformClientBuilder.java   Builder (.url, .credentials, ...).
    core/                            Generated runtime (auth, errors, request layer).
    resources/                       Generated resource clients (prompts, traces, datasets, etc.).
  guardrails/
    AntsGuardrailsClient.java        Hand-written guardrails client.
    AntsTracer.java                  Lightweight tracer that emits to the platform.
    GuardrailResult.java, Violation.java, GuardrailViolationException.java
    GuardrailTraceUtils.java
    TracePayload.java
    providers/                       Per-provider adapters (OpenAI, etc.).
  examples/                          Runnable usage samples.

src/test/java/com/ants/platform/    JUnit 5 tests.
pom.xml                             Group ai.agenticants, artifact ants-platform-java, version 0.1.3.
mvnw, mvnw.cmd                      Maven wrapper.
```

## Commands

```sh
./mvnw clean install                                      # build + run tests + install locally
./mvnw test
./mvnw -DskipTests package                                # jar only
./mvnw release:prepare -DreleaseVersion=0.1.4             # tag + bump pom version
./mvnw -P central deploy                                  # publish to Maven Central (GPG-signed)
```

## Usage shape (kept here so reviewers don't have to dig the README)

```java
AntsPlatformClient client = AntsPlatformClient.builder()
    .url("https://api.agenticants.ai")
    .credentials("pk-ap-...", "sk-ap-...")
    .build();

PromptMetaListResponse prompts = client.prompts().list();
```

## Things to know

- **`client/core` and `client/resources` are Fern-generated.** Regenerate them by running Fern in `agentic-ants-lf-fork` and copying `generated/java` into `src/main/java/com/ants/platform/client/`. Hand edits get clobbered. The `guardrails/` package is hand-written and stable — fine to edit.
- **Group ID and artifact ID are correct in `pom.xml`** (`ai.agenticants:ants-platform-java`). The README has shipped with stale `version 0.1.1` examples — current is `0.1.3`.
- **POM still carries Langfuse-era contact metadata** (`support@ants-platform.com`, `https://github.com/ants-platform/ants-platform-java`). Cosmetic, but flag if anyone notices in Maven Central — should eventually move to `engineering@agenticants.ai` / our actual GitHub org.
- **Maven Central deploy** requires the `central` profile (GPG signing plugin block in `pom.xml`). Sonatype credentials live in `~/.m2/settings.xml`.
- **No `pom.xml` parent.** Self-contained. Don't introduce a Spring-Boot starter parent.
- **Backend version contract**: SDK `0.1.x` tracks backend `agentic-ants-lf-fork` `0.1.x`. Breaking REST changes in the backend require a synchronized SDK release across all three languages (java, python, javascript).

## Approval boundaries

`./mvnw test`, `./mvnw clean install` (local repo only), file edits — fine. `./mvnw release:prepare`, `./mvnw -P central deploy` (publishes to Maven Central — irreversible), `git commit/push`, version bumps that ship — ask first.
