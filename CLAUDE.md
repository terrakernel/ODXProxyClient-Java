# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Kotlin/JVM client library for the ODXProxy Gateway — a proxy that fronts Odoo instances. Published to Maven Central as `io.odxproxy:odxproxyclient-java`. Written in Kotlin but compiled to Java 8 bytecode (`jvmTarget = "1.8"`, `sourceCompatibility = 1.8`) for Java 8+ consumer interop (Android, JavaFX, Spring Boot). Toolchain JDK 17.

## Build / Test

```bash
./gradlew build                                # compile + test
./gradlew test                                 # run all JUnit 5 tests
./gradlew test --tests "io.odxproxy.OdxProxyMockTest"          # single class
./gradlew test --tests "io.odxproxy.OdxProxyMockTest.someName" # single method
./gradlew publishToMavenLocal                  # local publish
```

Tests live under `src/main/test/kotlin` (note: under `main/`, not the conventional `src/test/`). `OdxProxyMockTest` uses OkHttp `MockWebServer`; `OdxProxyLiveTest` hits a real gateway.

A `pom.xml` exists alongside `build.gradle.kts` — **Gradle is the source of truth**. The Maven file mirrors it for IDEs/consumers that prefer Maven metadata.

## Architecture

Three layers, all in `src/main/kotlin/io/odxproxy/`:

1. **`OdxProxy` (singleton facade)** — `OdxProxy.kt`. Static `@JvmStatic` entrypoints (`search`, `searchRead`, `read`, `searchCount`, `create`, `write`, `remove`, `fieldsGet`, `callMethod`). Each method builds an `OdxClientRequest` with a string `action` ("search_read", "unlink", "call_method", …) and delegates to the client. Request IDs auto-generated as ULIDs when caller passes `null`.

2. **`OdxProxyClient` (transport)** — `client/OdxProxyClient.kt`. Process-wide singleton guarded by `AtomicReference`; `init()` throws if called twice, `getInstance()` throws if not initialized. Holds the OkHttp client (10s connect, 45s read/write) and a single `Json` configured with `ignoreUnknownKeys = true`, `explicitNulls = false`, `isLenient = true`. All requests POST to `${gatewayUrl}/api/odoo/execute` with `X-Api-Key` header. Both **serialization and network I/O run off-caller-thread** via `CompletableFuture.runAsync` — the public contract is that nothing blocks the caller (UI-thread safe). Result type is resolved at runtime via `serializersModule.serializer(Class<T>)`, so `T` must be `@Serializable` (or a built-in kotlinx type like `JsonObject`).

3. **Model layer** — `model/`:
   - `Models.kt`: request/response envelopes (`OdxClientRequest`, `OdxServerResponse<T>`, `OdxClientKeywordRequest`, `OdxInstanceInfo`). `OdxIdSerializer` accepts JSON id as either string or number and normalizes to `String`. `toJsonElement(Any?)` recursively converts arbitrary Java/Kotlin containers (`List`, `Map`, `Array`, primitives) into `JsonElement` — this is how Java callers can pass plain `Map`/`List` as `params` without writing serializers.
   - `OdooTypes.kt`: **the core defense against Odoo's polymorphic JSON.** `OdxMany2One` decodes `false | null | [id, "Name"] | []` into a uniform `(id, name)` pair. `OdxVariant<T>` wraps fields that Odoo may return as `false` instead of the declared type — when the value is the literal boolean `false` it decodes to `OdxVariant(null)` rather than throwing. When mapping Odoo records to POJOs, always use these wrappers for relational / nullable string fields. **Do not** use raw `String?` / `Int?` for fields Odoo can return as `false`.

`exception/Exceptions.kt` defines `OdxServerErrorException` with two constructors: one for parsed JSON-RPC error envelopes, one for raw HTTP failures.

`kotlin { explicitApi() }` is on — every public declaration needs explicit `public` and an explicit return type.

## Pagination Quirk

`OdxClientKeywordRequest.resetPagination()` zeroes `fields/order/limit/offset` and is called inside `OdxProxy` for actions where pagination is meaningless (`search`, `read`, `create`, `write`, `unlink`, `fields_get`). `search_read`, `search_count`, and `call_method` pass the keyword through unchanged. Preserve this distinction when adding new actions.

## LLM guidance for consumers

The README's "What an LLM reading consumer code should enforce" section is the consumer-facing AI guide (previously a separate `AI_SDK_GUIDE.md`, now folded in). When you change the public surface — new wrapper types, new anti-patterns, new actions — update that table.
