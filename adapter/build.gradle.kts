plugins {
    kotlin("jvm") version "1.9.24"
    application
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.liux.android"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    // 3.23.0 is the last JDT Core release compatible with Java 8 (3.24+
    // requires Java 11, 3.31+ requires 17). The adapter JVM target is 1.8.
    implementation("org.eclipse.jdt:org.eclipse.jdt.core:3.23.0")
    testImplementation("junit:junit:4.13.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

application {
    mainClass.set("com.liux.android.debugger_adapter.MainKt")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("adapter")
    archiveVersion.set("")
    archiveClassifier.set("")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "com.liux.android.debugger_adapter.MainKt"
    }
}

// The extension loads the adapter from `extension/resources/adapter.jar`, so
// the freshly-built jar has to land there. The path is derived from the
// adapter project's parent (the repo root) — `rootProject.projectDir` is the
// repo root as well, but going through `projectDir` keeps this correct no
// matter how the build is invoked (root or sub-project).
val extensionResourcesDir = projectDir.parentFile.resolve("extension/resources")

tasks.register("copyJarToExtension") {
    val jarFile = tasks.named("shadowJar").get().outputs.files.singleFile
    val target = extensionResourcesDir.resolve(jarFile.name)
    // Track the jar itself as input and the copied file as output — a
    // directory output would make Gradle consider the task up-to-date even
    // when the jar inside it changed, leaving a stale adapter in the
    // extension.
    inputs.file(jarFile)
    outputs.file(target)
    doLast {
        target.parentFile.mkdirs()
        jarFile.copyTo(target, overwrite = true)
        println("Copied ${jarFile.name} → $target")
    }
}

tasks.named("build") {
    finalizedBy("copyJarToExtension")
}