plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("org.jetbrains.dokka") version "1.9.10"
    id("com.vanniktech.maven.publish") version "0.33.0"
}

group = "io.odxproxy"
version = "0.1.0"

repositories {
    mavenCentral()
}

val okHttpVersion = "4.12.0"
val ulidVersion = "5.2.3"
val serializationVersion = "1.7.3"
val junitVersion = "5.12.2"

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.squareup.okhttp3:okhttp:$okHttpVersion")
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
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates("io.odxproxy", "odxproxyclient-java", "0.1.0")

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
    kotlinOptions {
        jvmTarget = "1.8"
        javaParameters = true
        freeCompilerArgs += listOf("-Xjvm-default=all")
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
