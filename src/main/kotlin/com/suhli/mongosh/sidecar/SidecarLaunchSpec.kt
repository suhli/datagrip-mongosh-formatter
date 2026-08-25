package com.suhli.mongosh.sidecar

import java.nio.file.Path

data class SidecarLaunchSpec(
    val executable: Path,
    val args: List<String>,
    val workingDirectory: Path,
)
