# ODXProxy Java/Kotlin Client
![Static Badge](https://img.shields.io/badge/License-MIT-green)
[![Maven Central](https://img.shields.io/maven-central/v/io.odxproxy/odxproxyclient-java?color=blue)](https://central.sonatype.com/artifact/io.odxproxy/odxproxyclient-java)
![Static Badge](https://img.shields.io/badge/JDK-17-orange)

A high-performance, zero-reflection, thread-safe client library for connecting to Odoo instances via the ODXProxy Gateway.
Built with Kotlin for robust type safety, but designed for seamless interoperability with Java 8+ (Android, JavaFX, Spring Boot).

## 🚀 Features
⚡ **Zero-Reflection Serialization:** Uses kotlinx.serialization for instant parsing without runtime overhead (No Jackson/Gson bloat).

🛡️ **Odoo-Proof:** Custom data types (OdxMany2One, OdxVariant) automatically handle Odoo's polymorphic JSON (e.g., handling false where null or an object is expected).

🧵 **Non-Blocking by Default:** All operations (Network AND Serialization) run on background threads.
☕ Java Friendly: Returns CompletableFuture for easy integration with standard Java code.

## 🛠 Initialization
**You must initialize the library once** (e.g., in main(), Application.start(), or onCreate()).
```java
import io.odxproxy.OdxProxy;
import io.odxproxy.client.OdxProxyClientInfo;
import io.odxproxy.model.OdxInstanceInfo;

// 1. Define the Target Odoo Instance
OdxInstanceInfo instance = new OdxInstanceInfo(
    "https://my-odoo.com", // Odoo URL
    1,                     // User ID
    "my_database",         // Database Name
    "odoo_user_api_key"    // Odoo User API Key
);

// 2. Configure the Proxy
OdxProxyClientInfo config = new OdxProxyClientInfo(
    instance, 
    "odx_proxy_api_key",          // Your Gateway Key
    "https://gateway.odxproxy.io" // Gateway URL
);

// 3. Initialize
OdxProxy.init(config);
```

# 📚 Helper Classes (Critical)
Odoo's JSON is inconsistent. This library provides specific types to prevent crashes.
**1. OdxMany2One**
Handles relational fields. Odoo might return false (boolean), null, or [id, "Name"].
Java Usage:
```java
OdxMany2One company = partner.getCompany(); // Assuming mapped POJO
Integer id = company.getId();     // Returns ID or null
String name = company.getName();  // Returns Name or null
```
1. OdxVariant<T>
Handles nullable fields where Odoo returns false (boolean) instead of null.
Example: A field ref might be a String "ABC" or false.
Usage:
```java
OdxVariant<String> ref = partner.getRef();
String value = ref.getValue(); // Returns "ABC" or null (safely ignores 'false')
```
## 3. OdxClientKeywordRequest
Used to control pagination, sorting, and context.
```java
OdxClientKeywordRequest options = new OdxClientKeywordRequest(
    Arrays.asList("id", "name", "email"), // Fields
    "id desc",                            // Order
    10,                                   // Limit
    0,                                    // Offset
    new OdxClientRequestContext(...),     // Context (Timezone/Lang)
    null                                  // Domain (Optional explicit override)
);
```
## 💻 Available Methods
All methods are static on OdxProxy and return **CompletableFuture<OdxServerResponse<T>>.**
## 1. Search & Read (Most Common)
Fetches records matching a domain.
```java
import kotlinx.serialization.json.JsonObject; // Use JsonObject for raw data

List<Object> domain = Arrays.asList(
    Arrays.asList("customer_rank", ">", 0),
    Arrays.asList("email", "!=", false)
);

OdxProxy.searchRead(
    "res.partner",
    domain,
    new OdxClientKeywordRequest(Arrays.asList("name", "email"), null, 5, 0, null, null),
    null,
    JsonObject.class // Returns List<JsonObject>
).thenAccept(response -> {
    response.getResult().forEach(partner -> {
        System.out.println(partner.get("name"));
    });
});
```
## 2. Create
Creates a new record.
```java
Map<String, Object> values = new HashMap<>();
values.put("name", "New Customer");
values.put("email", "test@test.com");

OdxProxy.create(
    "res.partner",
    Arrays.asList(values),
    new OdxClientKeywordRequest(),
    null,
    Integer.class // Odoo returns the ID of the new record
).thenAccept(response -> {
    System.out.println("Created ID: " + response.getResult());
});
```
## 3. Write (Update)
Updates existing records.
```java
Map<String, Object> updates = new HashMap<>();
updates.put("name", "Updated Name");

OdxProxy.write(
    "res.partner",
    Arrays.asList(10, 11), // IDs to update
    updates,
    new OdxClientKeywordRequest(),
    null
).thenAccept(response -> {
    System.out.println("Success: " + response.getResult()); // Returns Boolean
});
```

## 4. Remove (Unlink)
Deletes records.
```java
OdxProxy.remove(
    "res.partner",
    Arrays.asList(99), // ID to delete
    new OdxClientKeywordRequest(),
    null
);
```

## 5. Call Method
Calls a custom method on the Odoo model.
```java
OdxProxy.callMethod(
    "res.partner",
    "action_archive", // The function name on the model
    Arrays.asList(Arrays.asList(5)), // Params (usually IDs)
    new OdxClientKeywordRequest(),
    null,
    Boolean.class // Return type depends on the Odoo method
);
```
## 🧵 Threading & Concurrency
Important:
Background Execution: All library logic (JSON Serialization + Network Calls) happens on a background thread pool. It will not freeze your UI.
Callbacks: The .thenAccept() or .exceptionally() callbacks run on a background thread.
UI Updates: If using Android or JavaFX, you must switch back to the UI thread to update views.
JavaFX Example:
```java
OdxProxy.searchRead(...).thenAccept(response -> {
    // We are in background thread here
    Platform.runLater(() -> {
        // Now safe to update UI
        myLabel.setText("Loaded!");
    });
});
```
## 🤖 AI Developer Guide
This repository includes a file named **AI_SDK_GUIDE.md**.
What is it?
Generic AI models (Copilot, ChatGPT, Cursor) do not know how this specific library works. They often try to write generic HTTP calls using Retrofit or standard OkHttp, which defeats the purpose of this library.
How to use it:
For Cursor IDE: Rename the file to .cursorrules in your project root. The AI will automatically read it and generate perfect code for this library.
For Copilot/Chat: Drag and drop the AI_SDK_GUIDE.md file into your chat window context.
Benefit: The AI will instantly know to use OdxMany2One instead of String, preventing polymorphic JSON crashes.


# 📄 License
MIT License. See LICENSE for details.