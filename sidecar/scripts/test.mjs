import { spawn } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { CASES } from "../test/cases.mjs";

const root = path.dirname(fileURLToPath(new URL(".", import.meta.url)));
const distDir = path.join(root, "dist");
const bundlePath = path.join(distDir, "formatter.bundle.js");

function detectHostId() {
  if (process.platform === "win32") return "windows-x64";
  if (process.platform === "linux" && process.arch === "arm64") return "linux-aarch64";
  if (process.platform === "linux") return "linux-x64";
  if (process.platform === "darwin" && process.arch === "arm64") return "macos-aarch64";
  if (process.platform === "darwin") return "macos-x64";
  throw new Error(`Unsupported host: ${process.platform}/${process.arch}`);
}

function hostExecutable() {
  return process.platform === "win32" ? "mongosh-formatter.exe" : "mongosh-formatter";
}

const hostId = detectHostId();
const sidecarDir = process.env.SIDECAR_DIR
  ? process.env.SIDECAR_DIR
  : path.join(distDir, "native", hostId);
const compiledSidecar = path.join(sidecarDir, hostExecutable());
const interpreterPath = process.env.QJS_PATH || path.join(root, ".cache", "qjs", hostExecutable());
const qjsPath = existsSync(compiledSidecar) ? compiledSidecar : interpreterPath;

function launchArgs(executable) {
  const launchFile = path.join(path.dirname(executable), "launch.json");
  if (existsSync(launchFile)) {
    const launch = JSON.parse(readFileSync(launchFile, "utf8"));
    return (launch.args || []).map((arg) =>
      path.isAbsolute(arg) ? arg : path.join(path.dirname(executable), arg),
    );
  }
  if (executable === interpreterPath) {
    return [bundlePath];
  }
  return [];
}

const scriptArgs = launchArgs(qjsPath);
console.log(`Using sidecar ${qjsPath} ${scriptArgs.join(" ")}`.trim());

function invokeSidecar(request, extra = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(qjsPath, scriptArgs, {
      stdio: ["pipe", "pipe", "pipe"],
      windowsHide: true,
      ...extra.spawnOptions,
    });
    const stdoutChunks = [];
    const stderrChunks = [];
    child.stdout.on("data", (chunk) => stdoutChunks.push(chunk));
    child.stderr.on("data", (chunk) => stderrChunks.push(chunk));
    child.on("error", reject);
    const timeout = setTimeout(() => {
      child.kill();
      reject(new Error("sidecar timed out"));
    }, extra.timeoutMs ?? 30_000);
    child.on("close", (exitCode) => {
      clearTimeout(timeout);
      resolve({
        exitCode,
        stdout: Buffer.concat(stdoutChunks).toString("utf8"),
        stderr: Buffer.concat(stderrChunks).toString("utf8"),
      });
    });
    const payload = extra.raw ? request : JSON.stringify(request);
    child.stdin.write(payload, "utf8");
    child.stdin.end();
  });
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function parseStdout(stdout) {
  const trimmed = stdout.trim();
  assert(trimmed.length > 0, `stdout was empty`);
  try {
    return JSON.parse(trimmed);
  } catch (error) {
    throw new Error(`stdout is not JSON: ${trimmed}\n${error.message}`);
  }
}

const failures = [];

for (const testCase of CASES) {
  try {
    const result = await invokeSidecar(testCase.request, {
      timeoutMs: testCase.timeoutMs,
      raw: testCase.raw,
    });
    const body = parseStdout(result.stdout);
    if (testCase.expectStderrEmpty !== false) {
      assert(result.stderr.trim() === "", `${testCase.name}: stderr must stay empty, got ${result.stderr}`);
    }
    if (typeof testCase.check === "function") {
      testCase.check({ body, result });
    }
    console.log(`ok  ${testCase.name}`);
  } catch (error) {
    failures.push(`${testCase.name}: ${error.message}`);
    console.error(`FAIL ${testCase.name}: ${error.message}`);
  }
}

if (failures.length > 0) {
  console.error(`\n${failures.length} sidecar test(s) failed`);
  process.exit(1);
}

const benchSource = `db.users.aggregate([${"{$match:{a:1}},".repeat(200)}{$limit:1}]);\n`;
const sizes = [
  { name: "small query", source: "db.users.find({a:1,b:2})" },
  { name: "10 KB", source: `${benchSource}\n`.repeat(Math.ceil(10_000 / benchSource.length)) },
  { name: "100 KB", source: `${benchSource}\n`.repeat(Math.ceil(100_000 / benchSource.length)) },
  { name: "1 MB", source: `${benchSource}\n`.repeat(Math.ceil(1_000_000 / benchSource.length)) },
];

await mkdir(path.join(distDir, "bench"), { recursive: true });
const benchResults = [];
for (const size of sizes) {
  const started = performance.now();
  const result = await invokeSidecar(
    { protocolVersion: 1, source: size.source },
    { timeoutMs: 180_000 },
  );
  const elapsedMs = performance.now() - started;
  const body = parseStdout(result.stdout);
  assert(body.ok === true, `${size.name} formatting failed: ${JSON.stringify(body)}`);
  benchResults.push({
    name: size.name,
    inputChars: size.source.length,
    elapsedMs: Math.round(elapsedMs),
    exitCode: result.exitCode,
  });
  console.log(`bench ${size.name}: ${Math.round(elapsedMs)} ms (${size.source.length} chars)`);
}

await writeFile(
  path.join(distDir, "bench", "results.json"),
  `${JSON.stringify(benchResults, null, 2)}\n`,
);

console.log("\nAll sidecar tests passed");
