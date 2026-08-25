import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.7.2"
}

fun properties(key: String) = providers.gradleProperty(key)

group = properties("pluginGroup").get()
version = properties("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val type = properties("platformType")
        val version = properties("platformVersion")
        create(type, version)
        bundledPlugin("com.intellij.database")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

val sidecarDir = file("sidecar")
val generatedSidecar = layout.buildDirectory.dir("generated/sidecar-resources")

fun npmCommand(vararg args: String): List<String> {
    val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    return if (windows) listOf("cmd", "/c", "npm", *args) else listOf("npm", *args)
}

fun hostSidecarResourceDir(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        os.startsWith("windows") -> "windows-x64"
        os.contains("linux") && (arch == "aarch64" || arch == "arm64") -> "linux-aarch64"
        os.contains("linux") -> "linux-x64"
        (os.contains("mac") || os.contains("darwin")) && (arch == "aarch64" || arch == "arm64") -> "macos-aarch64"
        os.contains("mac") || os.contains("darwin") -> "macos-x64"
        else -> error("Unsupported build host $os/$arch")
    }
}

tasks.register<Exec>("npmSidecarInstall") {
    workingDir = sidecarDir
    commandLine = npmCommand("ci")
    inputs.files(sidecarDir.resolve("package.json"), sidecarDir.resolve("package-lock.json"))
    outputs.dir(sidecarDir.resolve("node_modules"))
}

tasks.register<Exec>("buildSidecarBundle") {
    dependsOn("npmSidecarInstall")
    workingDir = sidecarDir
    commandLine = npmCommand("run", "build")
    inputs.dir(sidecarDir.resolve("src"))
    inputs.file(sidecarDir.resolve("versions.json"))
    outputs.file(sidecarDir.resolve("dist/formatter.bundle.js"))
}

tasks.register<Exec>("packageSidecar") {
    dependsOn("buildSidecarBundle")
    workingDir = sidecarDir
    environment("SIDECAR_OUTPUT_DIR", generatedSidecar.get().dir("sidecar").asFile.absolutePath)
    val targets = System.getenv("SIDECAR_TARGETS")
    if (!targets.isNullOrBlank()) {
        environment("SIDECAR_TARGETS", targets)
    }
    commandLine = npmCommand("run", "package-native")
    inputs.file(sidecarDir.resolve("dist/formatter.bundle.js"))
    inputs.file(sidecarDir.resolve("versions.json"))
    outputs.dir(generatedSidecar)
}

tasks.register<Exec>("sidecarTest") {
    dependsOn("packageSidecar")
    workingDir = sidecarDir
    environment(
        "SIDECAR_DIR",
        generatedSidecar.get().dir("sidecar/${hostSidecarResourceDir()}").asFile.absolutePath,
    )
    commandLine = npmCommand("test")
}

sourceSets {
    main {
        resources {
            srcDir(generatedSidecar)
        }
    }
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        version = properties("pluginVersion")
        ideaVersion {
            sinceBuild = properties("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // 2.0.1 -> default; 2.0.1-beta.1 -> beta
        channels = properties("pluginVersion").map { version ->
            listOf(version.substringAfter('-', "default").substringBefore('.').ifEmpty { "default" })
        }
    }
    pluginVerification {
        ides {
            create(IntelliJPlatformType.DataGrip, "2025.1")
            create(IntelliJPlatformType.IntellijIdeaUltimate, properties("platformVersion"))
        }
    }
}

tasks {
    wrapper {
        gradleVersion = properties("gradleVersion").get()
    }
    named("processResources") {
        dependsOn("packageSidecar")
    }
    test {
        dependsOn("packageSidecar")
        systemProperty(
            "sidecar.dir",
            generatedSidecar.get().dir("sidecar/${hostSidecarResourceDir()}").asFile.absolutePath,
        )
    }
    named<JavaExec>("runIde") {
        // Cursor / VS Code: ./gradlew runIde -PdebugIde=true
        if (project.hasProperty("debugIde")) {
            jvmArgs(
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005",
            )
        }
    }
    buildSearchableOptions {
        enabled = false
    }
}
