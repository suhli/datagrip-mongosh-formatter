import * as std from "qjs:std";
import { runWithIo } from "./runner.js";

function readStdin() {
  if (std.in && typeof std.in.readAsString === "function") {
    return std.in.readAsString();
  }
  const chunks = [];
  const tmp = new Uint8Array(65536);
  while (true) {
    const n = std.in.read(tmp.buffer, 0, tmp.length);
    if (n === 0 || n === -1) {
      break;
    }
    chunks.push(new Uint8Array(tmp.subarray(0, n)));
  }
  let total = 0;
  for (const chunk of chunks) {
    total += chunk.length;
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.length;
  }
  return new TextDecoder().decode(bytes);
}

await runWithIo({
  readStdin,
  writeStdout(text) {
    std.out.puts(text);
    std.out.flush();
  },
  writeStderr(text) {
    std.err.puts(text);
    std.err.flush();
  },
  exit(code) {
    std.exit(code);
  },
});
