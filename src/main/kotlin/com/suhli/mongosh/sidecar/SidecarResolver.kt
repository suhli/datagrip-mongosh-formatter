package com.suhli.mongosh.sidecar

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission

class SidecarExtractor(
    private val cacheRoot: Path,
    private val sidecarVersion: String,
    private val resourceOpener: (String) -> InputStream?,
) {
    fun ensureExtracted(platform: HostPlatform): Path {
        val targetDir = cacheRoot.resolve(sidecarVersion).resolve(platform.resourceDir)
        val stamp = targetDir.resolve(".extracted")
        val executable = targetDir.resolve(platform.executableName)
        if (Files.isRegularFile(stamp) && Files.isRegularFile(executable)) {
            return targetDir
        }

        Files.createDirectories(targetDir)
        val lockFile = targetDir.resolve(".lock")
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                if (Files.isRegularFile(stamp) && Files.isRegularFile(executable)) {
                    return targetDir
                }
                extractFile(platform, platform.executableName, targetDir)
                extractOptionalFile(platform, "formatter.js", targetDir)
                extractOptionalFile(platform, "launch.json", targetDir)
                if (platform.os != OsFamily.WINDOWS) {
                    setExecutable(targetDir.resolve(platform.executableName))
                }
                Files.writeString(stamp, sidecarVersion)
            }
        }
        LOG.debug("Extracted sidecar ${platform.resourceDir} version=$sidecarVersion")
        return targetDir
    }

    private fun extractFile(platform: HostPlatform, name: String, targetDir: Path) {
        val resource = resourcePath(platform, name)
        val input = resourceOpener(resource)
            ?: throw UnsupportedPlatformException("Sidecar resource missing: $resource")
        input.use { stream -> extractTo(targetDir.resolve(name), stream) }
    }

    private fun extractOptionalFile(platform: HostPlatform, name: String, targetDir: Path) {
        val resource = resourcePath(platform, name)
        val input = resourceOpener(resource) ?: return
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

    companion object {
        private val LOG = Logger.getInstance(SidecarExtractor::class.java)
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
