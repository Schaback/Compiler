/*
 * This file was generated by the Gradle 'init' task.
 */

plugins {
    application
}

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("info.picocli:picocli:4.6.1")
    implementation("net.java.dev.jna:jna:4.5.2")
    implementation("com.github.Firmwehr:jFirm:b970d57751")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.6.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.7.0")
    testImplementation("org.mockito:mockito-core:3.6.0")
}

application {
    mainClass.set("compiler.MainCommand")
    applicationDefaultJvmArgs = listOf("--enable-preview")
}

buildDir = File("target")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

sourceSets.test {
    java {
        srcDirs.add(File("src/test/resources"))
    }
}

tasks.withType<JavaCompile>() {
    options.encoding = "UTF-8"
    options.compilerArgs.add("--enable-preview")
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
    jvmArgs("--enable-preview")
}

tasks.withType<JavaExec>() {
    jvmArgs("--enable-preview")
    isIgnoreExitValue = true
}