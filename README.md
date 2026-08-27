# MongoJS Formatter for DataGrip

DataGrip plugin that formats MongoDB / MongoJS Console code through a bundled QuickJS sidecar running Prettier Standalone.

[Plugin page](https://plugins.jetbrains.com/plugin/22495-mongojs-prettier-format)

Compatible with **DataGrip 2025.1+** (and other IDEs that bundle Database Tools on the 251+ platform).

## What it does

In a MongoDB / MongoJS Console:

* `Code → Reformat Code` or `Ctrl+Alt+L` formats the whole document
* a selection is formatted with **exact fragment formatting** when possible (siblings and selection-boundary whitespace are preserved); incomplete fragments fall back to Prettier range formatting
* syntax errors, sidecar crashes, timeouts, oversized documents, and unsupported platforms leave the original document unchanged

Runtime does **not** require:

* Node.js / npm / pnpm / yarn / bun
* Prettier CLI
* JetBrains JavaScript plugin
* JetBrains Prettier plugin
* a system-wide QuickJS install

## Not supported in 2.x

Embedded SQL formatting from the legacy 1.x implementation is currently not supported in 2.x.

Restoring it would need a separate, stable design — not a return to Mongo PSI traversal or the JavaScript plugin.

## Architecture

```text
DataGrip
   │  Reformat Code
   ▼
MongoJsFormattingService  (IntelliJ AsyncDocumentFormattingService)
   │  JSON over stdin
   ▼
QuickJS sidecar  (pinned QuickJS-ng + Prettier Standalone)
   │  JSON over stdout
   ▼
IntelliJ Document
```

The JetBrains plugin talks only to a `FormatterBackend`. The first backend is `QuickJsSidecarBackend` (one process per format request). That boundary is what allows replacing QuickJS later without rewriting the IntelliJ integration.

Pinned versions live in `sidecar/versions.json`. Every downloaded QuickJS artifact has a fixed SHA256; packaging writes a content-addressed `manifest.json` used for IDE-side cache validation.

## Settings

`Settings → Tools → MongoJS Formatter`

* print width, tab width, tabs vs spaces
* semicolons, quotes, trailing commas, bracket spacing

There is no `.prettierrc` lookup in v2. Options are plugin-controlled so formatting does not depend on a local Node project.

## Size limits

Formatting is intended for interactive Mongo Console buffers, not multi-megabyte scripts.

| Size | Behavior |
| --- | --- |
| ≤ ~256 KB | Normal formatting (scaled sidecar timeout) |
| ≤ ~512 KB | Allowed with a higher sidecar timeout (still &lt; IDE formatter timeout) |
| &gt; ~512 KB | Immediate reject — document is not modified |

## Building

Requirements for **build time** only: JDK 21, Node.js (for esbuild + pinning Prettier).

```bash
./gradlew test
./gradlew buildPlugin
```

Gradle packages a host-platform sidecar under `build/generated/sidecar-resources/sidecar/<os-arch>/`. Set `SIDECAR_TARGETS` to build several native layouts, for example:

```bash
SIDECAR_TARGETS=windows-x64,linux-x64,linux-aarch64,macos-x64,macos-aarch64 ./gradlew buildPlugin
```

CI builds each mainstream platform on a native runner, then assembles the plugin once.

## Releasing

Use **Actions → Release → Run workflow**, enter the SemVer (for example `2.0.1`), and optionally mark it as a prerelease.

That workflow will:

1. build native sidecars on a platform matrix
2. run sidecar/Kotlin tests and Plugin Verifier (DataGrip 2025.1 / 2026.1 / 2026.2)
3. build + sign the plugin zip
4. publish to JetBrains Marketplace (`PUBLISH_TOKEN` + signing secrets)
5. create a GitHub Release `v<version>` with the zip attached

Marketplace channel comes from the version label: `2.0.1` → default, `2.1.0-beta.1` → `beta`.

Sidecar tests (QuickJS + Prettier) can also be run directly:

```bash
cd sidecar
npm ci
npm run build
npm run package-native
npm test
```

Measured on Windows x64, one process per format (QuickJS-ng 0.16.2 + Prettier 3.9.6):

| Input | Time |
| --- | --- |
| small query | ~150 ms |
| 10 KB | ~0.8 s |
| 100 KB | ~6 s |

Larger buffers are rejected by the size policy above rather than waiting for a timeout.

## Sidecar layout

```text
sidecar/
  src/           QuickJS entry, protocol, Prettier wrapper, polyfills
  scripts/       esbuild bundle + native packaging
  test/          formatter cases run against the native sidecar
  versions.json  pinned Prettier + QuickJS-ng versions + SHA256
```

Each packaged platform directory contains `manifest.json` with `mode`, `contentHash`, and per-file SHA256 digests.

### Runtime modes

* **compiled** — single native executable produced by `qjsc` on the host platform when that works
* **interpreter** — pinned `qjs` executable + `formatter.js` (and `launch.json`)

On Windows, `qjsc` currently emits C instead of a PE executable, so the packaged sidecar uses interpreter mode. Linux/macOS CI still tries `qjsc` on the native runner. Both modes are first-class: the manifest declares the mode, the content hash covers every required file, and CI executes the packaged sidecar where the architecture allows.

Users still do not need to install QuickJS themselves.
