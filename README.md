# OdxProxy Java Client

## STILL IN WIP NEED TESTING

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A modern, lightweight, and high-performance Java client for the [ODX Proxy Gateway](https://www.odxproxy.io). This library allows you to interact with any Odoo instance through a simple and robust API. It is designed for performance, portability, and excellent developer experience in both Java and Kotlin.

## Features

*   **Modern & Asynchronous:** Built with a fully asynchronous API using `CompletableFuture` to ensure your applications remain responsive.
*   **High Performance:** Uses industry-standard libraries like [OkHttp](https://square.github.io/okhttp/) for efficient networking and [Jackson](https://github.com/FasterXML/jackson) for fast JSON processing.
*   **Lightweight & Portable:** Designed with minimal dependencies, making it suitable for any Java environment, including **Java SE (Desktop/Server)** and **Android**.
*   **Kotlin-Friendly:** The API is designed to be fully interoperable and idiomatic when used from Kotlin.

## Installation

Add the library as a dependency to your project.

#### Maven

```xml
<dependency>
    <groupId>io.odxproxy</groupId>
    <artifactId>odx-proxy-java</artifactId>
    <version>0.1.0</version>
</dependency>