import { installPolyfills } from "./polyfill.js";
import { formatMongoJs } from "./formatter.js";
import {
  EXIT_FORMAT_ERROR,
  EXIT_INTERNAL_ERROR,
  EXIT_INVALID_REQUEST,
  EXIT_SUCCESS,
  failureResponse,
  parseRequest,
  successResponse,
} from "./protocol.js";

installPolyfills(globalThis);

export async function handleRequest(raw) {
  const parsed = parseRequest(raw);
  if (!parsed.ok) {
    return {
      exitCode: EXIT_INVALID_REQUEST,
      response: parsed.error,
    };
  }

  try {
    const result = await formatMongoJs(parsed.request);
    return {
      exitCode: EXIT_SUCCESS,
      response: successResponse(result.formatted, result.cursorOffset),
    };
  } catch (error) {
    return {
      exitCode: EXIT_FORMAT_ERROR,
      response: failureResponse(error),
    };
  }
}

export async function runWithIo(io) {
  try {
    const raw = io.readStdin();
    const result = await handleRequest(raw);
    io.writeStdout(`${JSON.stringify(result.response)}\n`);
    io.exit(result.exitCode);
  } catch (error) {
    try {
      io.writeStderr(`${error && error.stack ? error.stack : String(error)}\n`);
    } catch {
      // ignore secondary IO failures
    }
    io.exit(EXIT_INTERNAL_ERROR);
  }
}
