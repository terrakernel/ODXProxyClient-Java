# ODXProxy Java/Kotlin Client - AI Developer Guide

## 1. Framework Overview
This is a high-performance, zero-reflection Kotlin/Java library for connecting to Odoo via the ODXProxy Gateway.
It uses **Kotlinx Serialization** and **OkHttp** internally but exposes a **Static Facade** for consumers.

**Core Principles:**
1.  **Entry Point:** Always use `io.odxproxy.OdxProxy`. Do not instantiate `OdxProxyClient` manually.
2.  **Async Model:** All operations return `java.util.concurrent.CompletableFuture<T>`.
3.  **Thread Safety:** Serialization and Network calls are offloaded to background threads automatically.
4.  **Strict Typing:** Odoo's polymorphic JSON is handled via specific wrapper types (`OdxMany2One`, `OdxVariant`).

---

## 2. Key Data Types (CRITICAL)

The AI must strictly use these types to prevent serialization crashes caused by Odoo's inconsistent JSON (e.g., returning `false` instead of `null`).

| Type | Use Case | Behavior |
| :--- | :--- | :--- |
| **`OdxMany2One`** | Relational fields (`many2one`) | Handles `[id, "Name"]`, `false`, or `null`. Provides `.id` (Int?) and `.name` (String?). |
| **`OdxVariant<T>`** | Nullable fields (e.g., `string`, `date`) | Handles cases where Odoo returns `false` (boolean) instead of `null` or the expected type. |
| **`OdxClientKeywordRequest`** | Pagination & Options | Handles `limit`, `offset`, `order`, `fields`, and `context`. |

---

## 3. Usage Patterns

### A. Initialization (Singleton)
Must be called once before usage.

```kotlin
// Kotlin
val instance = OdxInstanceInfo("https://odoo.my-server.com", 1, "my-db", "odoo-api-key")
val config = OdxProxyClientInfo(instance, "odx-proxy-key", "https://gateway.odxproxy.io")
OdxProxy.init(config)
```

### B. Defining Models (Kotlinx Serialization)
Always use @Serializable

```kotlin
@Serializable
data class Partner(
    val id: Int,
    val name: String,
    @SerialName("company_id") val company: OdxMany2One, // Handles [1, "MyCompany"] or false
    val email: OdxVariant<String> // Handles "mail@test.com" or false
)
```

### C. Search & Read (The standard pattern)
Use OdxProxy.searchRead or OdxProxy.read

```kotlin
val keywords = OdxClientKeywordRequest(
    fields = listOf("name", "email", "company_id"),
    limit = 10,
    order = "id desc"
)

// Returns CompletableFuture<OdxServerResponse<List<Partner>>>
OdxProxy.searchRead(
    model = "res.partner",
    params = listOf(listOf("customer_rank", ">", 0)), // Domain
    keyword = keywords,
    id = null, // Auto-generate Request ID
    resultType = Partner::class.java // Required for Generic Reification
).thenAccept { response ->
    response.result?.forEach { partner ->
        println(partner.name)
    }
}
```

### D. Writing Data

Use OdxProxy.write
```kotlin
val updateVals = mapOf("name" to "New Name")

OdxProxy.write(
    model = "res.partner",
    ids = listOf(10, 11),
    values = updateVals,
    keyword = OdxClientKeywordRequest(),
    id = null
).join() // or .get()
```

## 3. ANTI PATTERNS (DO NOT DO THIS)

### 1. NO: val company: String? for Many2One fields.
Why: Odoo might send [id, name] array or false. This will crash serialization.
Fix: Use val company: OdxMany2One.

### 2. NO: val email: String? if the field can be unset in Odoo.
Why: Odoo often sends false (Boolean) for empty strings.
Fix: Use val email: OdxVariant<String>.

### 3. NO: new OdxProxyClient(...)
Why: The client is internal.
Fix: Use OdxProxy.method(...).

### 4. NO: Parsing response.result directly without checking response.error.
Why: Odoo can return HTTP 200 OK but contain a logic error in the JSON body. The library wraps this, but always be aware of OdxServerErrorException.

