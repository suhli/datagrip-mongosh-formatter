package com.suhli.mongosh.sidecar

data class HostPlatform(
    val os: OsFamily,
    val arch: Arch,
) {
    val resourceDir: String
        get() = "${os.dirName}-${arch.dirName}"

    val executableName: String
        get() = if (os == OsFamily.WINDOWS) "mongosh-formatter.exe" else "mongosh-formatter"

    companion object {
        fun current(): HostPlatform =
            from(System.getProperty("os.name").orEmpty(), System.getProperty("os.arch").orEmpty())

        fun from(osName: String, archName: String): HostPlatform {
            val os = OsFamily.from(osName)
            val arch = Arch.from(archName)
            return if (os == OsFamily.WINDOWS && arch == Arch.AARCH64) {
                HostPlatform(OsFamily.WINDOWS, Arch.X64)
            } else {
                HostPlatform(os, arch)
            }
        }
    }
}

enum class OsFamily(val dirName: String) {
    WINDOWS("windows"),
    LINUX("linux"),
    MACOS("macos");

    companion object {
        fun from(osName: String): OsFamily {
            val normalized = osName.lowercase()
            return when {
                normalized.startsWith("windows") -> WINDOWS
                normalized.startsWith("mac") || normalized.startsWith("darwin") -> MACOS
                normalized.contains("linux") -> LINUX
                else -> throw UnsupportedPlatformException("Unsupported OS: $osName")
            }
        }
    }
}

enum class Arch(val dirName: String) {
    X64("x64"),
    AARCH64("aarch64");

    companion object {
        fun from(archName: String): Arch {
            return when (archName.lowercase()) {
                "amd64", "x86_64", "x64" -> X64
                "aarch64", "arm64" -> AARCH64
                else -> throw UnsupportedPlatformException("Unsupported architecture: $archName")
            }
        }
    }
}

class UnsupportedPlatformException(message: String) : RuntimeException(message)
