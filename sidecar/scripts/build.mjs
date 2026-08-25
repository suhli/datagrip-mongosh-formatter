import * as esbuild from "esbuild";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.dirname(fileURLToPath(new URL(".", import.meta.url)));
const distDir = path.join(root, "dist");
const versions = JSON.parse(await readFile(path.join(root, "versions.json"), "utf8"));

await mkdir(distDir, { recursive: true });

const banner = `/* mongosh-formatter sidecar
 * prettier@${versions.prettier}
 * protocolVersion=${versions.protocolVersion}
 * quickjs=${versions.quickjs.distribution}@${versions.quickjs.version}
 */
`;

await esbuild.build({
  absWorkingDir: root,
  entryPoints: [path.join(root, "src", "main.js")],
  outfile: path.join(distDir, "formatter.bundle.js"),
  bundle: true,
  format: "esm",
  platform: "neutral",
  target: "es2022",
  legalComments: "none",
  minify: true,
  logLevel: "info",
  banner: { js: banner },
  external: ["qjs:std", "qjs:os"],
  define: {
    "process.env.NODE_ENV": '"production"',
  },
});

const bundlePath = path.join(distDir, "formatter.bundle.js");
const bundle = await readFile(bundlePath, "utf8");
await writeFile(
  path.join(distDir, "build-info.json"),
  `${JSON.stringify(
    {
      prettier: versions.prettier,
      protocolVersion: versions.protocolVersion,
      quickjs: versions.quickjs.version,
      bundleBytes: Buffer.byteLength(bundle, "utf8"),
    },
    null,
    2,
  )}\n`,
);

console.log(`Wrote ${bundlePath} (${Buffer.byteLength(bundle, "utf8")} bytes)`);
