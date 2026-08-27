export const PROTOCOL_VERSION = 1;
/** Hard reject above this size — do not wait for a timeout. */
export const MAX_SOURCE_CHARS = 512 * 1024;
/** Inputs at or below this size use the base sidecar timeout. */
export const SAFE_SOURCE_CHARS = 256 * 1024;

export const EXIT_SUCCESS = 0;
export const EXIT_FORMAT_ERROR = 1;
export const EXIT_INVALID_REQUEST = 2;
export const EXIT_INTERNAL_ERROR = 3;

const ALLOWED_OPTIONS = {
  printWidth: "number",
  tabWidth: "number",
  useTabs: "boolean",
  semi: "boolean",
  singleQuote: "boolean",
  trailingComma: "string",
  bracketSpacing: "boolean",
  endOfLine: "string",
};

const TRAILING_COMMA = new Set(["all", "es5", "none"]);
const END_OF_LINE = new Set(["lf", "crlf", "cr", "auto"]);

export function parseRequest(raw) {
  if (raw === null || raw === undefined || String(raw).trim() === "") {
    return invalid("Request body is empty");
  }

  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch (error) {
    return invalid("Request is not valid JSON");
  }

  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
    return invalid("Request must be a JSON object");
  }

  if (parsed.protocolVersion !== PROTOCOL_VERSION) {
    return invalid(
      `Unsupported protocolVersion: ${String(parsed.protocolVersion)} (expected ${PROTOCOL_VERSION})`,
    );
  }

  if (typeof parsed.source !== "string") {
    return invalid("Request.source must be a string");
  }

  if (parsed.source.length > MAX_SOURCE_CHARS) {
    const kb = Math.round(parsed.source.length / 1024);
    return invalid(`MongoJS formatting skipped: document is too large (${kb} KB).`);
  }

  const rangeStart = optionalOffset(parsed.rangeStart, "rangeStart");
  if (rangeStart.error) {
    return rangeStart.error;
  }
  const rangeEnd = optionalOffset(parsed.rangeEnd, "rangeEnd");
  if (rangeEnd.error) {
    return rangeEnd.error;
  }
  const cursorOffset = optionalOffset(parsed.cursorOffset, "cursorOffset");
  if (cursorOffset.error) {
    return cursorOffset.error;
  }

  if (rangeStart.value !== undefined && rangeEnd.value === undefined) {
    return invalid("rangeEnd is required when rangeStart is set");
  }
  if (rangeEnd.value !== undefined && rangeStart.value === undefined) {
    return invalid("rangeStart is required when rangeEnd is set");
  }
  if (
    rangeStart.value !== undefined &&
    rangeEnd.value !== undefined &&
    rangeStart.value > rangeEnd.value
  ) {
    return invalid("rangeStart must be <= rangeEnd");
  }
  if (rangeStart.value !== undefined && rangeStart.value > parsed.source.length) {
    return invalid("rangeStart is outside the source");
  }
  if (rangeEnd.value !== undefined && rangeEnd.value > parsed.source.length) {
    return invalid("rangeEnd is outside the source");
  }
  if (cursorOffset.value !== undefined && cursorOffset.value > parsed.source.length) {
    return invalid("cursorOffset is outside the source");
  }

  const options = sanitizeOptions(parsed.options);
  if (options.error) {
    return options.error;
  }

  return {
    ok: true,
    request: {
      source: parsed.source,
      rangeStart: rangeStart.value,
      rangeEnd: rangeEnd.value,
      cursorOffset: cursorOffset.value,
      options: options.value,
    },
  };
}

export function successResponse(formatted, cursorOffset) {
  const body = {
    protocolVersion: PROTOCOL_VERSION,
    ok: true,
    formatted,
  };
  if (cursorOffset !== undefined && cursorOffset !== null) {
    body.cursorOffset = cursorOffset;
  }
  return body;
}

export function failureResponse(error) {
  return {
    protocolVersion: PROTOCOL_VERSION,
    ok: false,
    error: {
      message: error.message || String(error),
      line: Number.isInteger(error.loc?.start?.line)
        ? error.loc.start.line
        : Number.isInteger(error.line)
          ? error.line
          : undefined,
      column: Number.isInteger(error.loc?.start?.column)
        ? error.loc.start.column
        : Number.isInteger(error.column)
          ? error.column
          : undefined,
    },
  };
}

export function invalidResponse(message) {
  return {
    protocolVersion: PROTOCOL_VERSION,
    ok: false,
    error: {
      message,
    },
  };
}

function optionalOffset(value, name) {
  if (value === undefined || value === null) {
    return { value: undefined };
  }
  if (!Number.isInteger(value) || value < 0) {
    return { error: invalid(`${name} must be a non-negative integer`) };
  }
  return { value };
}

function sanitizeOptions(options) {
  if (options === undefined || options === null) {
    return { value: {} };
  }
  if (typeof options !== "object" || Array.isArray(options)) {
    return { error: invalid("options must be an object") };
  }

  const sanitized = {};
  for (const [key, value] of Object.entries(options)) {
    const expected = ALLOWED_OPTIONS[key];
    if (!expected) {
      continue;
    }
    if (typeof value !== expected) {
      return { error: invalid(`options.${key} must be a ${expected}`) };
    }
    if (key === "trailingComma" && !TRAILING_COMMA.has(value)) {
      return { error: invalid("options.trailingComma must be all, es5, or none") };
    }
    if (key === "endOfLine" && !END_OF_LINE.has(value)) {
      return { error: invalid("options.endOfLine must be lf, crlf, cr, or auto") };
    }
    if ((key === "printWidth" || key === "tabWidth") && !(value >= 0)) {
      return { error: invalid(`options.${key} must be >= 0`) };
    }
    sanitized[key] = value;
  }
  return { value: sanitized };
}

function invalid(message) {
  return { ok: false, error: invalidResponse(message) };
}
