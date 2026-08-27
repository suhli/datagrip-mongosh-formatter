import { createHash } from "node:crypto";
import { chmod, copyFile, mkdir, readFile, unlink, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { spawn } from "node:child_process";

const root = path.dirname(fileURLToPath(new URL(".", import.meta.url)));
const versions = JSON.parse(await readFile(path.join(root, "versions.json"), "utf8"));
const cacheDir = path.join(root, ".cache", "qjs");
const distDir = path.join(root, "dist");
const outRoot = process.env.SIDECAR_OUTPUT_DIR || path.join(distDir, "native");
const bundlePath = path.join(distDir, "formatter.bundle.js");

const HOST = detectHost();
const targets = process.env.SIDECAR_TARGETS
  ? process.env.SIDECAR_TARGETS.split(",").map((value) => value.trim()).filter(Boolean)
  : [HOST.id];

await mkdir(cacheDir, { recursive: true });
await mkdir(outRoot, { recursive: true });

if (!existsSync(bundlePath)) {
  throw new Error("formatter.bundle.js is missing; run `npm run build` first");
}

for (const targetId of targets) {
  const spec = versions.quickjs.binaries[targetId];
  if (!spec) {
    throw new Error(`Unknown sidecar target: ${targetId}`);
  }
  if (!spec.sha256) {
    throw new Error(`Missing required sha256 for binary ${targetId}`);
  }
  const url = `${versions.quickjs.releaseBaseUrl}/${spec.file}`;
  const downloadPath = path.join(cacheDir, spec.file);
  await downloadPinned(url, downloadPath, spec.sha256);
  const targetDir = path.join(outRoot, targetId);
  await mkdir(targetDir, { recursive: true });
  const executablePath = path.join(targetDir, spec.executable);
  await copyFile(downloadPath, executablePath);
  if (targetId !== "windows-x64") {
    try {
      await chmod(executablePath, 0o755);
    } catch {
      // Windows hosts cannot always persist POSIX executable bits.
    }
  }

  let mode = "interpreter";
  let includeFormatterJs = true;

  if (targetId === HOST.id) {
    const hostPath = path.join(cacheDir, spec.executable);
    await copyFile(downloadPath, hostPath);
    if (process.platform !== "win32") {
      await chmod(hostPath, 0o755);
    }
    const compiled = await maybeCompileWithQjsc({
      targetId,
      executablePath,
    });
    if (compiled) {
      mode = "compiled";
      includeFormatterJs = false;
    }
  }

  if (includeFormatterJs) {
    await copyFile(bundlePath, path.join(targetDir, "formatter.js"));
  } else if (existsSync(path.join(targetDir, "formatter.js"))) {
    await unlink(path.join(targetDir, "formatter.js"));
  }

  const launch = {
    mode,
    protocolVersion: versions.protocolVersion,
    runtime: versions.quickjs.distribution,
    runtimeVersion: versions.quickjs.version,
    prettierVersion: versions.prettier,
    args: mode === "interpreter" ? ["formatter.js"] : [],
  };
  await writeFile(path.join(targetDir, "launch.json"), `${JSON.stringify(launch, null, 2)}\n`);

  const requiredFiles = [];
  for (const name of [spec.executable, ...(includeFormatterJs ? ["formatter.js"] : []), "launch.json"]) {
    const bytes = await readFile(path.join(targetDir, name));
    requiredFiles.push({
      name,
      sha256: createHash("sha256").update(bytes).digest("hex"),
    });
  }
  requiredFiles.sort((a, b) => a.name.localeCompare(b.name));
  const contentHash = createHash("sha256")
    .update(requiredFiles.map((file) => `${file.name}:${file.sha256}`).join("\n"))
    .digest("hex");

  const manifest = {
    protocolVersion: versions.protocolVersion,
    runtime: versions.quickjs.distribution,
    runtimeVersion: versions.quickjs.version,
    prettierVersion: versions.prettier,
    mode,
    contentHash,
    requiredFiles,
  };
  await writeFile(path.join(targetDir, "manifest.json"), `${JSON.stringify(manifest, null, 2)}\n`);

  console.log(`Packaged ${targetId} (${mode}) contentHash=${contentHash.slice(0, 12)} -> ${executablePath}`);
}

async function maybeCompileWithQjsc({ targetId, executablePath }) {
  const qjscSpec = versions.quickjs.qjsc[targetId];
  if (!qjscSpec) {
    return false;
  }
  const qjscFile = typeof qjscSpec === "string" ? qjscSpec : qjscSpec.file;
  const qjscSha = typeof qjscSpec === "object" ? qjscSpec.sha256 : undefined;
  if (!qjscSha) {
    throw new Error(`Missing required sha256 for qjsc ${targetId}`);
  }
  const qjscPath = path.join(cacheDir, qjscFile);
  try {
    await downloadPinned(`${versions.quickjs.releaseBaseUrl}/${qjscFile}`, qjscPath, qjscSha);
    if (process.platform !== "win32") {
      await chmod(qjscPath, 0o755);
    }
  } catch (error) {
    console.warn(`Skipping qjsc download: ${error.message}`);
    return false;
  }

  const compiledPath = path.join(cacheDir, `qjsc-output-${targetId}${process.platform === "win32" ? ".exe" : ""}`);
  const result = await run(
    qjscPath,
    ["-m", "-o", compiledPath, bundlePath],
    { timeoutMs: 180_000, env: { ...process.env, CC: process.env.CC || "gcc" } },
  );
  if (result.exitCode === 0 && existsSync(compiledPath) && (await isNativeExecutable(compiledPath))) {
    await copyFile(compiledPath, executablePath);
    console.log(`Compiled single-file sidecar with qjsc for ${targetId}`);
    return true;
  }
  console.warn(
    `qjsc did not produce a native executable for ${targetId} (exit ${result.exitCode}). Using interpreter + formatter.js.\n${result.stderr || result.stdout}`,
  );
  return false;
}

async function isNativeExecutable(filePath) {
  const bytes = await readFile(filePath);
  if (bytes.length < 4) {
    return false;
  }
  const mz = bytes[0] === 0x4d && bytes[1] === 0x5a;
  const elf = bytes[0] === 0x7f && bytes[1] === 0x45 && bytes[2] === 0x4c && bytes[3] === 0x46;
  const machoLe = bytes[0] === 0xcf && bytes[1] === 0xfa && bytes[2] === 0xed && bytes[3] === 0xfe;
  const machoBe = bytes[0] === 0xfe && bytes[1] === 0xed && bytes[2] === 0xfa && bytes[3] === 0xcf;
  const fat = bytes[0] === 0xca && bytes[1] === 0xfe && bytes[2] === 0xba && bytes[3] === 0xbe;
  return mz || elf || machoLe || machoBe || fat;
}

function detectHost() {
  const platform = process.platform;
  const arch = process.arch;
  if (platform === "win32" && (arch === "x64" || arch === "arm64")) {
    return { id: "windows-x64" };
  }
  if (platform === "linux" && arch === "x64") {
    return { id: "linux-x64" };
  }
  if (platform === "linux" && arch === "arm64") {
    return { id: "linux-aarch64" };
  }
  if (platform === "darwin" && arch === "x64") {
    return { id: "macos-x64" };
  }
  if (platform === "darwin" && arch === "arm64") {
    return { id: "macos-aarch64" };
  }
  throw new Error(`Unsupported host platform: ${platform}/${arch}`);
}

async function downloadPinned(url, destination, expectedSha256) {
  if (!expectedSha256) {
    throw new Error(`SHA256 is required for download: ${url}`);
  }
  if (existsSync(destination)) {
    const existing = await readFile(destination);
    const actual = createHash("sha256").update(existing).digest("hex");
    if (actual !== expectedSha256) {
      throw new Error(`Checksum mismatch for ${destination}: ${actual} != ${expectedSha256}`);
    }
    return;
  }
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`Failed to download ${url}: ${response.status} ${response.statusText}`);
  }
  const bytes = Buffer.from(await response.arrayBuffer());
  const sha256 = createHash("sha256").update(bytes).digest("hex");
  if (sha256 !== expectedSha256) {
    throw new Error(`Checksum mismatch for ${url}: ${sha256} != ${expectedSha256}`);
  }
  await writeFile(destination, bytes);
  console.log(`Downloaded ${url} sha256=${sha256}`);
}

function run(command, args, { timeoutMs, env }) {
  return new Promise((resolve) => {
    const child = spawn(command, args, { stdio: ["ignore", "pipe", "pipe"], windowsHide: true, env });
    const stdoutChunks = [];
    const stderrChunks = [];
    child.stdout.on("data", (chunk) => stdoutChunks.push(chunk));
    child.stderr.on("data", (chunk) => stderrChunks.push(chunk));
    const timer = setTimeout(() => child.kill(), timeoutMs);
    child.on("close", (exitCode) => {
      clearTimeout(timer);
      resolve({
        exitCode,
        stdout: Buffer.concat(stdoutChunks).toString("utf8"),
        stderr: Buffer.concat(stderrChunks).toString("utf8"),
      });
    });
    child.on("error", (error) => {
      clearTimeout(timer);
      resolve({ exitCode: 1, stdout: "", stderr: String(error) });
    });
  });
}
