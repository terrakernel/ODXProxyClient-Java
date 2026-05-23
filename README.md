# ODXProxy Java/Kotlin Client

![License](https://img.shields.io/badge/License-MIT-green)
[![Maven Central](https://img.shields.io/maven-central/v/io.odxproxy/odxproxyclient-java?color=blue)](https://central.sonatype.com/artifact/io.odxproxy/odxproxyclient-java)
![JDK](https://img.shields.io/badge/JDK-17%20toolchain%20%E2%80%94%20Java%208%20bytecode-orange)
![Android](https://img.shields.io/badge/Android-API%2024%2B-brightgreen)

A high-performance, thread-safe Kotlin/Java client for connecting to Odoo via the **ODXProxy Gateway**.
Written in Kotlin for type safety, compiled to **Java 8 bytecode** for seamless interop with Java, Android (API 24+), JavaFX, and Spring Boot.

---

## Why this library exists

Odoo's JSON-RPC API is **polymorphic** in a way no generic JSON deserializer handles well:
- Relational fields return `[id, "Name"]` *or* `false` *or* `null`.
- Optional scalars (string, date, ref) return the value *or* the literal boolean `false` instead of `null`.

A naive Retrofit / Gson / Jackson client crashes on the first `false` where it expected `String`. This library wraps the gateway transport and ships purpose-built decoders (`OdxMany2One`, `OdxVariant<T>`) that absorb these quirks so consumer code stays clean.

---

## Quick start

### Install (Gradle)

```kotlin
dependencies {
    implementation("io.odxproxy:odxproxyclient-java:0.1.0")
}
```

### Initialize (once, at app startup)

```java
// In Application.onCreate(), main(), Application.start(), etc.
OdxInstanceInfo instance = new OdxInstanceInfo(
    "https://my-odoo.com",   // Odoo URL
    1,                       // Odoo user id
    "my_database",           // db name
    "odoo_user_api_key"      // Odoo API key
);

OdxProxyClientInfo config = new OdxProxyClientInfo(
    instance,
    "odx_proxy_api_key",           // gateway key
    "https://gateway.odxproxy.io"  // gateway URL
);

OdxProxy.init(config);
```

`OdxProxy.init()` is **idempotent-or-throws**: calling it twice raises `IllegalStateException`. The library is a process-wide singleton.

### Use it

```java
OdxProxy.searchRead(
    "res.partner",
    Arrays.asList(Arrays.asList("customer_rank", ">", 0)),
    new OdxClientKeywordRequest(Arrays.asList("name", "email"), null, 5, 0, null),
    null,                  // request id — null = auto-generate ULID
    JsonObject.class       // result element type
).thenAccept(response -> {
    response.getResult().forEach(partner -> {
        System.out.println(partner.get("name"));
    });
});
```

---

## API reference

All methods are `@JvmStatic` on `io.odxproxy.OdxProxy` and return `CompletableFuture<OdxServerResponse<T>>`.

| Method | Odoo action | Returns |
|---|---|---|
| `search(model, domain, kw, id)` | `search` | `List<Int>` of matching ids |
| `searchRead(model, domain, kw, id, T.class)` | `search_read` | `List<T>` |
| `read(model, ids, kw, id, T.class)` | `read` | `List<T>` |
| `searchCount(model, domain, kw, id)` | `search_count` | `Int` |
| `create(model, [vals…], kw, id, T.class)` | `create` | id of new record (typically `Integer`) |
| `write(model, ids, values, kw, id)` | `write` | `Boolean` |
| `remove(model, ids, kw, id)` | `unlink` | `Boolean` |
| `fieldsGet(model, kw, id, T.class)` | `fields_get` | schema map (use `JsonObject.class`) |
| `callMethod(model, fn, params, kw, id, T.class)` | `call_method` | depends on the Odoo method |

`kw` is an `OdxClientKeywordRequest(fields, order, limit, offset, context)`.

> The `id` parameter is the JSON-RPC request id — pass `null` to auto-generate a ULID.

---

## Odoo polymorphism — the types you **must** use

Odoo's JSON is inconsistent. Use these wrappers in your `@Serializable` models or deserialization will fail.

### `OdxMany2One` — for relational (`many2one`) fields

Odoo returns `[7, "ACME"]`, `false`, or `null` depending on whether the relation is set.

```java
OdxMany2One company = partner.getCompany();
Integer id = company.getId();     // null if unset
String name = company.getName();  // null if unset
boolean isSet = company.isSet();
```

### `OdxVariant<T>` — for nullable scalars Odoo returns as `false`

Odoo returns the literal boolean `false` for "empty" strings, dates, refs, etc.

```java
OdxVariant<String> ref = partner.getRef();
String value = ref.getValue();   // null if Odoo sent false
```

### `OdxClientKeywordRequest` — pagination + Odoo context

```java
new OdxClientKeywordRequest(
    Arrays.asList("id", "name", "email"),   // fields
    "id desc",                              // order
    10,                                     // limit
    0,                                      // offset
    new OdxClientRequestContext(...)        // tz / lang / company
);
```

`search`, `read`, `create`, `write`, `unlink`, and `fields_get` ignore pagination fields (the library strips them automatically). `search_read`, `search_count`, and `call_method` pass them through.

---

## Defining typed models

Always annotate with `@Serializable` (kotlinx-serialization). Use `OdxMany2One` for relations and `OdxVariant<T>` for nullable scalars.

```kotlin
@Serializable
data class Partner(
    val id: Int,
    val name: String,
    @SerialName("company_id") val company: OdxMany2One,   // [id, "Name"] | false
    val email: OdxVariant<String>,                        // "x@y.com" | false
    val ref: OdxVariant<String>
)
```

From Java, this Kotlin data class is a normal POJO with getters (`partner.getName()`, `partner.getCompany().getId()`).

---

## Threading model

| What | Where it runs |
|---|---|
| **Request encoding** | Inline on the calling thread (sub-millisecond after serializer cache warm-up) |
| **Network I/O** | OkHttp dispatcher pool (background) |
| **Response decoding** | OkHttp dispatcher pool (background) |
| **`.thenAccept` / `.thenApply` callbacks** | OkHttp dispatcher pool (background) |

**Implications for Android / JavaFX consumers:**
- Don't block dispatcher threads in your callbacks — they're a finite pool serving all in-flight requests. Hand off long work to your own executor (`.thenAcceptAsync(cb, myExecutor)`).
- Always switch to the UI thread before touching views.

```java
// Android
OdxProxy.searchRead(...).thenAccept(response -> {
    runOnUiThread(() -> myView.setText(response.getResult().get(0).toString()));
});

// JavaFX
OdxProxy.searchRead(...).thenAccept(response -> {
    Platform.runLater(() -> myLabel.setText("Loaded"));
});
```

**Thread-safety:** the library is fully safe for concurrent use. The `OdxProxy` singleton, the underlying `OdxProxyClient`, the OkHttp `Dispatcher` + `ConnectionPool`, and the internal `KSerializer` caches are all designed for concurrent access. You can fire hundreds of overlapping requests from any threads without external synchronization.

---

## Errors

Failures complete the `CompletableFuture` exceptionally — `.get()` throws `ExecutionException`, `.exceptionally(...)` receives it.

| Cause | `cause` of the `ExecutionException` |
|---|---|
| Odoo / gateway returned a JSON-RPC error envelope (200 *or* non-2xx) | `OdxServerErrorException` with `.code`, `.message`, `.data` (raw `JsonElement`) |
| HTTP error with no JSON body | `OdxServerErrorException` with HTTP status code |
| Socket / DNS / TLS failure | `java.io.IOException` |
| Serialization failure (response shape mismatch) | `IOException` wrapping the kotlinx exception |

Always handle both `result` and `error` paths — Odoo can return HTTP 200 with an error envelope (e.g., `AccessDenied`), which the library surfaces as `OdxServerErrorException`.

---

## For Android consumers specifically

- **Minimum API: 24 (Android 7.0 Nougat).** This is driven by `CompletableFuture`, not Kotlin or OkHttp.
- For API 24–25, enable **core library desugaring** so `java.time.Duration` resolves at runtime:
  ```kotlin
  android {
      compileOptions {
          isCoreLibraryDesugaringEnabled = true
      }
  }
  dependencies {
      coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
  }
  ```
- `OdxProxy` state is in-process. After Android kills the app process, call `OdxProxy.init()` again from `Application.onCreate()`.
- Cancelling the returned `CompletableFuture` does **not** cancel the underlying HTTP request — the request still runs to completion, the callback just won't fire. This is standard JDK `CompletableFuture` behavior.

---

## Architecture (for contributors and LLMs reading the source)

Three layers under `src/main/kotlin/io/odxproxy/`:

1. **`OdxProxy`** — `@JvmStatic` static facade. Stateless. Generates a ULID per request when the caller passes `null` as `id`. Delegates to the client singleton.
2. **`client/OdxProxyClient`** — process-wide singleton via `AtomicReference`. Owns the shared `OkHttpClient` (tuned dispatcher + 16-conn keepalive pool), the `Json` codec (`ignoreUnknownKeys`, `isLenient`, `explicitNulls = false`), and two `ConcurrentHashMap` serializer caches (element and `List<element>`) populated via atomic `computeIfAbsent`. POSTs to `${gatewayUrl}/api/odoo/execute` with `X-Api-Key`. Request bodies are stream-encoded into an Okio `Buffer` (no intermediate Java `String`) and responses are stream-decoded via `Json.decodeFromStream`.
3. **`model/`** — `Models.kt` holds the request/response envelopes plus `toJsonElement` for converting arbitrary Java/Kotlin containers to `JsonElement` (so Java callers can pass plain `Map`/`List`). `OdooTypes.kt` holds `OdxMany2One` and `OdxVariant<T>` plus their custom serializers — these are the core defense against polymorphic Odoo JSON. `OdxIdSerializer` accepts the JSON-RPC `id` as either string or number and normalizes to `String`.

`exception/OdxServerErrorException` is what every failure surfaces as (HTTP + JSON-RPC error envelopes both).

The library compiles with `kotlin { explicitApi() }` — every public declaration must have an explicit `public` modifier and explicit return type.

---

## Build / test

```bash
./gradlew build                                # compile + test
./gradlew test                                 # run all JUnit 5 tests
./gradlew test --tests "io.odxproxy.OdxProxyIntegrationTest"
./gradlew publishToMavenLocal
```

Tests live at `src/main/test/kotlin` (non-standard; the `build.gradle.kts` sourceSet config points the test task there). The live integration test (`OdxProxyLiveTest`) self-skips unless `odx-test.properties` exists at the repo root with valid gateway credentials.

---

## What an LLM reading consumer code should enforce

If you're an AI assistant generating code that uses this library, these rules prevent the most common runtime crashes:

| Anti-pattern | Why it breaks | Correct form |
|---|---|---|
| `val company: String?` for a `many2one` field | Odoo sends `[id, "Name"]` array or `false` — kotlinx-serialization throws | `val company: OdxMany2One` |
| `val email: String?` when Odoo can send `false` for empty | `false` is not a valid `String` decode | `val email: OdxVariant<String>` |
| `new OdxProxyClient(...)` | Constructor is internal-only; consumers must use the facade | Use `OdxProxy.<method>(...)` |
| Calling `OdxProxy.<method>` before `OdxProxy.init(...)` | Throws `IllegalStateException` | Always init in `Application.onCreate()` / `main()` first |
| Reading `response.getResult()` without handling errors | Gateway can return HTTP 200 with a JSON-RPC error envelope; the future will be completed exceptionally | Use `.exceptionally(...)` or wrap `.get()` in try/catch for `OdxServerErrorException` |
| Hand-rolling HTTP to the gateway with OkHttp/Retrofit | Defeats the polymorphism defense, the singleton transport, and the cached serializers | Use `OdxProxy.<method>(...)` |
| `Json { ... }.decodeFromString(...)` on raw responses | Bypasses `OdxServerResponse<T>` envelope handling | Let the library decode; consume `OdxServerResponse<T>.result` |
| Blocking inside `.thenAccept` (e.g., file I/O, DB call, `Thread.sleep`) | Holds an OkHttp dispatcher thread, throttling other requests | Use `.thenAcceptAsync(cb, myExecutor)` |

---

## License

MIT — see `LICENSE`.
