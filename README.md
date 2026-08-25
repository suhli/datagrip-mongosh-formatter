# MongoJS Formatter for DataGrip

DataGrip plugin that formats MongoDB / MongoJS Console code through a bundled QuickJS sidecar running Prettier Standalone.

[Plugin page](https://plugins.jetbrains.com/plugin/22495-mongojs-prettier-format)

Compatible with **DataGrip 2025.1+** (and other IDEs that bundle Database Tools on the 251+ platform).

## What it does

In a MongoDB / MongoJS Console:

* `Code → Reformat Code` or `Ctrl+Alt+L` formats the whole document
* a selection is formatted with Prettier range options against the **full document**
* syntax errors, sidecar crashes, timeouts, and unsupported platforms leave the original document unchanged

Runtime does **not** require:

* Node.js / npm / pnpm / yarn / bun
* Prettier CLI
* JetBrains JavaScript plugin
* JetBrains Prettier plugin
* a system-wide QuickJS install

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

Pinned versions live in `sidecar/versions.json`.

## Settings

`Settings → Tools → MongoJS Formatter`

* print width, tab width, tabs vs spaces
* semicolons, quotes, trailing commas, bracket spacing

There is no `.prettierrc` lookup in v2. Options are plugin-controlled so formatting does not depend on a local Node project.

## Building

Requirements for **build time** only: JDK 21, Node.js (for esbuild + pinning Prettier).

```bash
./gradlew test
./gradlew buildPlugin
```

Gradle packages a host-platform sidecar under `build/generated/sidecar-resources/sidecar/<os-arch>/`. Set `SIDECAR_TARGETS` to build several native layouts in CI, for example:

```bash
SIDECAR_TARGETS=windows-x64,linux-x64,linux-aarch64,macos-x64,macos-aarch64 ./gradlew buildPlugin
```

## Releasing

Use **Actions → Release → Run workflow**, enter the SemVer (for example `2.0.1`), and optionally mark it as a prerelease.

That workflow will:

1. run sidecar/Kotlin tests and Plugin Verifier
2. build + sign the plugin zip
3. publish to JetBrains Marketplace (`PUBLISH_TOKEN` + signing secrets)
4. create a GitHub Release `v<version>` with the zip attached

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
| 1 MB | ~74 s |

Startup of the native sidecar is a small part of that. A long-lived daemon is intentionally not in v2.

## Sidecar layout

```text
sidecar/
  src/           QuickJS entry, protocol, Prettier wrapper, polyfills
  scripts/       esbuild bundle + native packaging
  test/          formatter cases run against the native sidecar
  versions.json  pinned Prettier + QuickJS-ng versions
```

On Windows, `qjsc` currently emits C instead of a PE executable, so the packaged sidecar is the pinned `qjs` interpreter plus `formatter.js`. Linux/macOS CI can still try `qjsc`; the resolver reads `launch.json` and does not assume a single binary layout.
