plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("org.jetbrains.dokka") version "1.9.10"
    id("com.vanniktech.maven.publish") version "0.33.0"
}

group = "io.odxproxy"
version = "0.1.2"

repositories {
    mavenCentral()
}

val okHttpVersion = "5.4.0"
val ulidVersion = "5.2.3"
val serializationVersion = "1.11.0"
val junitVersion = "5.12.2"

dependencies {
    implementation(kotlin("stdlib"))
    // OkHttp 5 is a KMP build: the plain `okhttp` artifact is a metadata stub with no classes,
    // and its POM does not point Maven consumers at the real jar. Depend on `okhttp-jvm` so the
    // published POM carries a coordinate that resolves for Maven users, not just Gradle ones.
    implementation("com.squareup.okhttp3:okhttp-jvm:$okHttpVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
    implementation("com.github.f4b6a3:ulid-creator:$ulidVersion")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("com.squareup.okhttp3:mockwebserver:$okHttpVersion")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    // FIX IS HERE: Explicitly force Java 8 bytecode generation
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    explicitApi()
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates("io.odxproxy", "odxproxyclient-java", "0.1.2")

    pom {
        name.set("ODXProxy Java Client")
        description.set("High-performance Java/Kotlin client for ODXProxy Gateway.")
        url.set("https://github.com/terrakernel/odxproxy-java")
        licenses {
            license {
                name.set("MIT License")
                url.set("http://www.opensource.org/licenses/mit-license.php")
            }
        }
        developers {
            developer {
                id.set("jwajong")
                name.set("Julian Wajong")
                email.set("julian.wajong@gmail.com")
                organization.set("TERRAKERNEL PTE. LTD.")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/terrakernel/ODXProxyClient-Java.git")
            url.set("https://github.com/terrakernel/ODXProxyClient-Java.git")
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    // kotlinOptions was removed in Kotlin 2.2; compilerOptions is the replacement DSL.
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        javaParameters.set(true)
        // Kotlin 2.3 renamed -Xjvm-default=all to -jvm-default=no-compatibility.
        freeCompilerArgs.addAll("-jvm-default=no-compatibility")
    }
}

sourceSets {
    test {
        kotlin.setSrcDirs(listOf("src/main/test/kotlin", "."))
        kotlin.include("io/odxproxy/**/*.kt", "TestModels.kt")
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
