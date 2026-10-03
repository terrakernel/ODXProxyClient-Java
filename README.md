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
    implementation("io.odxproxy:odxproxyclient-java:0.9.0")
}
```

### Install (Maven)

```xml
<dependency>
    <groupId>io.odxproxy</groupId>
    <artifactId>odxproxyclient-java</artifactId>
    <version>0.9.0</version>
</dependency>
```

> Nothing else to declare. From 0.1.2 this library depends on OkHttp 5 via the
> `com.squareup.okhttp3:okhttp-jvm` coordinate rather than plain `okhttp`, because the latter is a
> Kotlin Multiplatform metadata stub that contains no classes and whose POM does not point Maven at
> the real jar. Gradle resolves that stub correctly through module metadata; Maven does not, and a
> consumer who ends up with it gets a `NoClassDefFoundError` at runtime. Depending on `okhttp-jvm`
> means transitive resolution works for both build tools.

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
    // params = execute_kw's positional args, so the domain is wrapped once more
    Arrays.asList(Arrays.asList(Arrays.asList("customer_rank", ">", 0))),
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
| `search(model, params, kw, id)` | `search` | `List<Int>` of matching ids |
| `searchRead(model, params, kw, id, T.class)` | `search_read` | `List<T>` |
| `read(model, ids, kw, id, T.class)` | `read` | `List<T>` |
| `searchCount(model, params, kw, id)` | `search_count` | `Int` |
| `create(model, [vals…], kw, id, T.class)` | `create` | id of new record (typically `Integer`) |
| `write(model, ids, values, kw, id)` | `write` | `Boolean` |
| `remove(model, ids, kw, id)` | `unlink` | `Boolean` |
| `fieldsGet(model, kw, id, T.class)` | `fields_get` | schema map (use `JsonObject.class`) |
| `callMethod(model, fn, params, kw, id, T.class)` | `call_method` | depends on the Odoo method |

`kw` is an `OdxClientKeywordRequest(fields, order, limit, offset, context)`.

`params` is passed to Odoo's `execute_kw` as its positional arguments, so for `search`, `searchRead` and `searchCount` the domain is its first element: `listOf(listOf(listOf("is_company", "=", true)))`, or `listOf(emptyList<Any>())` to match everything. Passing the bare domain makes Odoo fail with `ValueError: Domain() invalid item`.

> The `id` parameter is the JSON-RPC request id — pass `null` to auto-generate a ULID.

---

## v2 API — Odoo 19+ (JSON-2)

`io.odxproxy.OdxProxyV2` talks to ODXProxy's `/v2/odoo/*` endpoints (**ODXProxy 0.9.0+**), which reach Odoo over its **JSON-2** API instead of `/jsonrpc`. It uses the **same `OdxProxy.init(...)` singleton** and instance. There is nothing new to set up, and it's still one Odoo instance per process.

**When to use which:**

| Odoo version | `OdxProxy` (v1) | `OdxProxyV2` |
|---|---|---|
| 18 or older | ✅ only option | ❌ (`isJson2Unavailable`) |
| 19 – 21 | ✅ | ✅ |
| 22 and newer | ❌ (`/jsonrpc` removed) | ✅ only option |

`OdxProxyV2.isSupported()` checks the configured instance once and caches the answer.

```kotlin
// Optional: a default Odoo context for every v2 call (v1 calls are not affected)
OdxProxy.init(OdxProxyClientInfo(
    OdxInstanceInfo("https://erp.example.com", 2, "prod", "ODOO_USER_API_KEY"),
    "PROXY_X_API_KEY",
    "https://gateway.odxproxy.io",
    mapOf("lang" to "en_US", "tz" to "Asia/Jakarta", "allowed_company_ids" to listOf(1)),
))

val partners = OdxProxyV2.searchRead(
    "res.partner", Partner::class.java,
    domain = listOf(listOf("is_company", "=", true)),
    fields = listOf("name", "email"), limit = 20, order = "name asc",
).get().result

val ids = OdxProxyV2.search("res.partner", listOf(listOf("customer_rank", ">", 0)), limit = 10).get().result
val count = OdxProxyV2.searchCount("res.partner", emptyList()).get().result
val rows = OdxProxyV2.read("res.partner", Partner::class.java, listOf(3, 4), fields = listOf("name")).get().result

val newIds = OdxProxyV2.create("res.partner", listOf(mapOf("name" to "Acme"), mapOf("name" to "Globex"))).get().result // [41, 42]
val one = OdxProxyV2.createOne("res.partner", mapOf("name" to "Initech")).get().result                           // 43
OdxProxyV2.write("res.partner", newIds!!, mapOf("comment" to "via v2")).get()
OdxProxyV2.remove("res.partner", newIds).get()

OdxProxyV2.callMethod("account.move", "action_post", Boolean::class.javaObjectType, ids = listOf(7)).get()
OdxProxyV2.callMethod("res.partner", "name_search", JsonArray::class.java,
    kwargs = mapOf("name" to "Acm", "limit" to 5)).get()
```

From Java, optional arguments are trailing `@JvmOverloads` parameters. Pass `null` for ones you skip:

```java
List<Object> domain = List.of(List.of("is_company", "=", true));
OdxProxyV2.searchRead("res.partner", Partner.class, domain, List.of("name"), null, 20)
    .thenAccept(res -> render(res.getResult()));
```

| Method | Odoo method | Arguments sent | `result` |
|---|---|---|---|
| `search(model, domain, offset?, limit?, order?, context?, id?)` | `search` | `domain`, `offset`, `limit`, `order` | `List<Int>` |
| `searchRead(model, T.class, domain?, fields?, offset?, limit?, order?, context?, id?)` | `search_read` | `domain`, `fields`, `offset`, `limit`, `order` | `List<T>` |
| `searchCount(model, domain, limit?, context?, id?)` | `search_count` | `domain`, `limit` | `Int` |
| `read(model, T.class, ids, fields?, load?, context?, id?)` | `read` | `ids`, `fields`, `load` | `List<T>` |
| `fieldsGet(model, T.class, allfields?, attributes?, context?, id?)` | `fields_get` | `allfields`, `attributes` | `T` (use `JsonObject`) |
| `create(model, [vals…], context?, id?)` | `create` | `vals_list` | `List<Int>`, always |
| `createOne(model, vals, context?, id?)` | `create` | `vals_list: [vals]` | `Int` |
| `write(model, ids, vals, context?, id?)` | `write` | `ids`, `vals` | `Boolean` |
| `remove(model, ids, context?, id?)` | `unlink` | `ids` | `Boolean` |
| `callMethod(model, method, T.class, ids?, kwargs?, context?, id?)` | *method* | `ids` (if given) + `kwargs` | `T` |
| `version(url?, id?)` | – | `POST /v2/odoo/version` | `OdxV2VersionInfo` (`.major`) |
| `isSupported(url?)` | – | uses `version` | `CompletableFuture<Boolean>` (cached) |

**How v2 differs from v1:**

- **Named arguments only.** There are no `params` and no `OdxClientKeywordRequest`. Each argument is sent under Odoo's Python parameter name. In `callMethod`, `ids` is for record methods only, and every other argument goes in `kwargs` under its Python name. Odoo rejects unknown names, and `ids` on `@api.model` methods, with `odooStatus == 422`.
- **The domain is the list itself:** `listOf(listOf("is_company", "=", true))`. v1's extra wrapping list is gone.
- **`null` arguments are omitted**, so Odoo's own defaults apply.
- **`create` always returns a list of ids**, even for one record. Use `createOne` for an `Int`.
- **The key must be an Odoo API key**, not a password. On Odoo 20+ its scope must be `rpc` (the default), and keys of non-admin users expire. `userId` is not sent, because Odoo derives the user from the key.
- **Context:** `OdxProxyClientInfo.defaultContext` is merged into every v2 call, and a call's `context` keys win. Odoo applies no company selection unless `allowed_company_ids` is sent.
- **Multi-database hosts:** the database is selected by header and filtered by the server's `dbfilter`. If the host picks the database from its hostname, the instance `url` must be that database's own hostname. Otherwise calls fail with `isJson2Unavailable`.
- **Binary fields on Odoo 20+** read as `{"content", "filename"?, "size"}` instead of a base64 string. This is an Odoo 20 change and applies to v1 too.

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

`OdxServerErrorException` also carries the response's `httpStatus` and these helpers (codes are constants such as `OdxServerErrorException.JSON2_UNAVAILABLE`):

| Helper | Meaning |
|---|---|
| `odooStatus` | For an Odoo-side error, Odoo's HTTP status, which the proxy forwards as `code` (always on v2; on v1 only when Odoo itself answered non-2xx). `401` Odoo API key invalid/expired, which is not the same as `AUTH_FAILED` (the proxy key). `403` access rights or private method, `404` unknown model/method or missing record, `409` lock conflict, `422` validation error or bad arguments, `5xx` server error. `null` otherwise. |
| `odooErrorName` | Odoo's exception class from `data.name`, e.g. `odoo.exceptions.ValidationError`. |
| `isLicenseError` | Code `0` **on HTTP 403**. Odoo 19+ also uses code `0` for every `/jsonrpc` error, on HTTP 200, so don't test `code == 0` alone. |
| `isJson2Unavailable` | **v2:** `-32006`, meaning no JSON-2 on that Odoo (≤18, use v1), or the database isn't selectable on that host (`dbfilter`). |
| `isInvalidRequest` | **v2:** `-32007`, meaning an invalid model/method name, or a db/api key that isn't valid as an HTTP header. Odoo was not contacted. |
| `isRetryable` | `true` for `UPSTREAM_CONNECT` and Odoo `409`. Timeouts are excluded, because the call may already have run. |

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
| v1: passing the bare domain as `params`, e.g. `listOf(listOf("field", "=", value))` | `params` are `execute_kw`'s positional args, so Odoo reads each condition as a whole domain and fails with `ValueError: Domain() invalid item` | `listOf(listOf(listOf("field", "=", value)))` |
| v2: passing v1-style nested params, e.g. `listOf(listOf(listOf(...)))` as `domain`, or `"args"` in `kwargs` | JSON-2 takes named arguments only. Unknown names and extra nesting fail with `odooStatus == 422` | `domain = listOf(listOf("field", "=", value))`; every other argument under its Odoo Python name |
| v2: camelCasing Odoo argument or field names (`valsList`, `allFields`, `isCompany`) | Odoo matches names exactly | Use Odoo's names verbatim: `vals_list`, `allfields`, `is_company` |
| v2: sending `ids` to `search`, `create`, `fields_get`, or another `@api.model` method | Odoo rejects it with 422 | Use the dedicated method; in `callMethod`, pass `ids` only for record methods |
| Treating `code == 0` as a license error | Odoo 19+ v1 errors are also code 0 (on HTTP 200) | Use `isLicenseError` |

---

## License

MIT — see `LICENSE`.
