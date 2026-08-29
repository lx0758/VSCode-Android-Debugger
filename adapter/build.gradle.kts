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

tasks.register("copyJarToExtension") {
    val jarFile = tasks.named("shadowJar").get().outputs.files.singleFile
    val targetDir = rootProject.projectDir.parentFile.resolve("extension/resources")
    outputs.dir(targetDir)
    doLast {
        targetDir.mkdirs()
        jarFile.copyTo(targetDir.resolve(jarFile.name), overwrite = true)
        println("Copied ${jarFile.name} → ${targetDir}")
    }
}

tasks.named("build") {
    finalizedBy("copyJarToExtension")
}