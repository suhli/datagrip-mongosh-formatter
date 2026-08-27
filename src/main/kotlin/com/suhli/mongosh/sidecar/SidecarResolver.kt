package com.suhli.mongosh.sidecar

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

data class SidecarManifest(
    val protocolVersion: Int,
    val runtime: String,
    val runtimeVersion: String,
    val prettierVersion: String,
    val mode: String,
    val contentHash: String,
    val requiredFiles: List<RequiredFile>,
) {
    data class RequiredFile(val name: String, val sha256: String)
}

class SidecarExtractor(
    private val cacheRoot: Path,
    private val resourceOpener: (String) -> InputStream?,
) {
    fun ensureExtracted(platform: HostPlatform): Path {
        val manifest = loadManifest(platform)
        val targetDir = cacheRoot.resolve(manifest.contentHash).resolve(platform.resourceDir)
        if (isValidCache(targetDir, manifest, platform)) {
            return targetDir
        }

        Files.createDirectories(targetDir)
        val lockFile = targetDir.resolve(".lock")
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                if (isValidCache(targetDir, manifest, platform)) {
                    return targetDir
                }
                for (required in manifest.requiredFiles) {
                    extractFile(platform, required.name, targetDir)
                }
                if (platform.os != OsFamily.WINDOWS) {
                    setExecutable(targetDir.resolve(platform.executableName))
                }
                if (!isValidCache(targetDir, manifest, platform)) {
                    throw UnsupportedPlatformException(
                        "Sidecar cache validation failed after extract for ${platform.resourceDir}",
                    )
                }
                Files.writeString(targetDir.resolve(".extracted"), manifest.contentHash)
            }
        }
        LOG.debug(
            "Extracted sidecar ${platform.resourceDir} mode=${manifest.mode} contentHash=${manifest.contentHash.take(12)}",
        )
        return targetDir
    }

    fun loadManifest(platform: HostPlatform): SidecarManifest {
        val resource = "/sidecar/${platform.resourceDir}/manifest.json"
        val input = resourceOpener(resource)
            ?: throw UnsupportedPlatformException("Sidecar manifest missing: $resource")
        val text = input.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        return parseManifest(text)
    }

    private fun isValidCache(targetDir: Path, manifest: SidecarManifest, platform: HostPlatform): Boolean {
        if (!Files.isDirectory(targetDir)) {
            return false
        }
        for (required in manifest.requiredFiles) {
            val file = targetDir.resolve(required.name)
            if (!Files.isRegularFile(file)) {
                return false
            }
            val actual = sha256(file)
            if (!actual.equals(required.sha256, ignoreCase = true)) {
                return false
            }
        }
        val executable = targetDir.resolve(platform.executableName)
        if (!Files.isRegularFile(executable)) {
            return false
        }
        if (platform.os != OsFamily.WINDOWS && !Files.isExecutable(executable)) {
            return false
        }
        return true
    }

    private fun extractFile(platform: HostPlatform, name: String, targetDir: Path) {
        val resource = resourcePath(platform, name)
        val input = resourceOpener(resource)
            ?: throw UnsupportedPlatformException("Sidecar resource missing: $resource")
        input.use { stream -> extractTo(targetDir.resolve(name), stream) }
    }

    private fun extractTo(target: Path, stream: InputStream) {
        val temp = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
        Files.copy(stream, temp, StandardCopyOption.REPLACE_EXISTING)
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun resourcePath(platform: HostPlatform, name: String): String = "/sidecar/${platform.resourceDir}/$name"

    private fun setExecutable(path: Path) {
        if (!Files.exists(path)) {
            return
        }
        try {
            val permissions = Files.getPosixFilePermissions(path).toMutableSet()
            permissions.add(PosixFilePermission.OWNER_EXECUTE)
            permissions.add(PosixFilePermission.GROUP_EXECUTE)
            permissions.add(PosixFilePermission.OTHERS_EXECUTE)
            Files.setPosixFilePermissions(path, permissions)
        } catch (_: UnsupportedOperationException) {
            path.toFile().setExecutable(true, false)
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private val LOG = Logger.getInstance(SidecarExtractor::class.java)

        fun parseManifest(text: String): SidecarManifest {
            val root = JsonParser.parseString(text).asJsonObject
            val required = root.getAsJsonArray("requiredFiles").map { element ->
                val obj = element.asJsonObject
                SidecarManifest.RequiredFile(
                    name = obj.get("name").asString,
                    sha256 = obj.get("sha256").asString,
                )
            }
            return SidecarManifest(
                protocolVersion = root.get("protocolVersion").asInt,
                runtime = root.get("runtime").asString,
                runtimeVersion = root.get("runtimeVersion").asString,
                prettierVersion = root.get("prettierVersion").asString,
                mode = root.get("mode").asString,
                contentHash = root.get("contentHash").asString,
                requiredFiles = required,
            )
        }
    }
}

fun interface SidecarSpecProvider {
    fun resolve(): SidecarLaunchSpec
}

class SidecarResolver(
    private val extractor: SidecarExtractor,
    private val platform: HostPlatform = HostPlatform.current(),
) : SidecarSpecProvider {
    override fun resolve(): SidecarLaunchSpec {
        val dir = extractor.ensureExtracted(platform)
        val executable = dir.resolve(platform.executableName)
        if (!Files.isRegularFile(executable)) {
            throw UnsupportedPlatformException("No sidecar binary for ${platform.resourceDir}")
        }
        return SidecarLaunchSpec(
            executable = executable,
            args = readArgs(dir),
            workingDirectory = dir,
        )
    }

    private fun readArgs(dir: Path): List<String> {
        val launchFile = dir.resolve("launch.json")
        if (Files.isRegularFile(launchFile)) {
            val root = JsonParser.parseString(Files.readString(launchFile)).asJsonObject
            val args = root.getAsJsonArray("args") ?: return emptyList()
            return args.map { element ->
                val value = element.asString
                val candidate = dir.resolve(value)
                if (Files.exists(candidate)) candidate.toString() else value
            }
        }
        val script = dir.resolve("formatter.js")
        return if (Files.isRegularFile(script)) listOf(script.toString()) else emptyList()
    }
}
