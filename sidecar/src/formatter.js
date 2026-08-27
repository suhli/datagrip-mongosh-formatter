import * as prettier from "prettier/standalone";
import * as babelPlugin from "prettier/plugins/babel";
import * as estreePlugin from "prettier/plugins/estree";
import { jsExpressionAttempt } from "./jsExpressionParser.js";

const DEFAULTS = {
  parser: "babel",
  filepath: "console.js",
  printWidth: 100,
  tabWidth: 2,
  useTabs: false,
  semi: true,
  singleQuote: false,
  trailingComma: "all",
  bracketSpacing: true,
  endOfLine: "auto",
  embeddedLanguageFormatting: "off",
};

/**
 * @returns {Promise<{formatted: string, cursorOffset?: number, strategy: "exact"|"range"|"document"}>}
 */
export async function formatMongoJs(request) {
  const options = {
    ...DEFAULTS,
    ...request.options,
    parser: "babel",
    plugins: [babelPlugin, estreePlugin],
  };

  if (
    request.rangeStart !== undefined &&
    request.rangeEnd !== undefined &&
    request.rangeStart < request.rangeEnd
  ) {
    try {
      const exact = await formatExactSelection(request, options);
      return { ...exact, strategy: "exact" };
    } catch {
      // Prettier's range API expands to the enclosing AST node (expected for incomplete
      // fragments). It must not silently rewrite the whole Mongo console buffer.
      const rangeOptions = {
        ...options,
        rangeStart: request.rangeStart,
        rangeEnd: request.rangeEnd,
      };
      const ranged = await formatDocument(request, rangeOptions);
      await assertNotWholeDocumentRewrite(request, options, ranged.formatted);
      return { ...ranged, strategy: "range" };
    }
  }

  const whole = await formatDocument(request, options);
  return { ...whole, strategy: "document" };
}

/**
 * If a ranged format yields the same text as formatting the entire document, while the
 * user only selected a fragment, treat it as an unsafe whole-document rewrite.
 */
export async function assertNotWholeDocumentRewrite(request, options, rangedFormatted) {
  const { source, rangeStart, rangeEnd } = request;
  const selectionLen = rangeEnd - rangeStart;
  if (selectionLen >= source.length * 0.9) {
    return;
  }
  const { rangeStart: _rs, rangeEnd: _re, ...wholeOptions } = options;
  const whole = await formatDocument({ ...request, rangeStart: undefined, rangeEnd: undefined }, wholeOptions);
  if (whole.formatted === rangedFormatted) {
    throw new Error(
      "Range formatting expanded to the whole document; refusing to rewrite outside the selection",
    );
  }
}

async function formatDocument(request, options) {
  if (request.cursorOffset !== undefined) {
    const result = await prettier.formatWithCursor(request.source, {
      ...options,
      cursorOffset: request.cursorOffset,
    });
    return {
      formatted: result.formatted,
      cursorOffset: result.cursorOffset,
    };
  }

  const formatted = await prettier.format(request.source, options);
  return { formatted };
}

/**
 * Split a selection into leading whitespace / core / trailing whitespace.
 * Whitespace includes spaces, tabs, LF, CRLF, and blank lines — never regenerated.
 */
export function splitSelectionBounds(source, rangeStart, rangeEnd) {
  const selected = source.slice(rangeStart, rangeEnd);
  const leadingMatch = selected.match(/^\s*/) || [""];
  const leadingLength = leadingMatch[0].length;
  const afterLeading = selected.slice(leadingLength);
  const trailingMatch = afterLeading.match(/\s*$/) || [""];
  const trailingLength = trailingMatch[0].length;
  const coreStart = rangeStart + leadingLength;
  const coreEnd = rangeEnd - trailingLength;
  return {
    coreStart,
    coreEnd,
    core: source.slice(coreStart, coreEnd),
    leading: source.slice(rangeStart, coreStart),
    trailing: source.slice(coreEnd, rangeEnd),
  };
}

/**
 * Format only the selected core and splice it back, preserving original
 * leading/trailing whitespace of the selection verbatim.
 */
async function formatExactSelection(request, options) {
  const { source, rangeStart, rangeEnd } = request;
  const bounds = splitSelectionBounds(source, rangeStart, rangeEnd);
  const { coreStart, coreEnd, core } = bounds;
  if (!core) {
    return { formatted: source, cursorOffset: request.cursorOffset };
  }

  const innerCursor =
    request.cursorOffset !== undefined &&
    request.cursorOffset >= coreStart &&
    request.cursorOffset <= coreEnd
      ? request.cursorOffset - coreStart
      : undefined;

  const fragment = await formatFragment(core, options, innerCursor);
  const eol = documentEol(source, options.endOfLine);
  const formattedLf = stripTrailingNewlines(toLf(fragment.formatted));
  const indent = continuationIndent(source, coreStart, options.tabWidth || 2);
  const formattedCore = reindent(formattedLf, indent, eol);
  const formatted = source.slice(0, coreStart) + formattedCore + source.slice(coreEnd);

  let cursorOffset = request.cursorOffset;
  if (cursorOffset !== undefined) {
    if (cursorOffset <= coreStart) {
      // before the core — offsets unchanged (leading whitespace kept)
    } else if (cursorOffset >= coreEnd) {
      cursorOffset += formattedCore.length - (coreEnd - coreStart);
    } else {
      const mapped =
        fragment.cursorOffset !== undefined && fragment.cursorOffset >= 0
          ? mapCursorThroughReindent(formattedLf, indent, eol, fragment.cursorOffset)
          : formattedCore.length;
      cursorOffset = coreStart + mapped;
    }
  }

  return { formatted, cursorOffset };
}

async function formatFragment(core, options, innerCursor) {
  const fragmentOptions = {
    ...options,
    filepath: "fragment.js",
    endOfLine: "lf",
  };
  const attempts = [
    jsExpressionAttempt(core),
    { parser: "babel", text: `(${core})`, cursorShift: 1, unwrap: true },
    { parser: "babel", text: core, cursorShift: 0, unwrap: false },
  ];

  let lastError;
  for (const attempt of attempts) {
    try {
      const opts = { ...fragmentOptions, parser: attempt.parser };
      let formatted;
      let cursorOffset;
      if (innerCursor !== undefined) {
        const result = await prettier.formatWithCursor(attempt.text, {
          ...opts,
          cursorOffset: innerCursor + attempt.cursorShift,
        });
        formatted = result.formatted;
        cursorOffset = result.cursorOffset;
      } else {
        formatted = await prettier.format(attempt.text, opts);
      }
      if (attempt.unwrap) {
        const unwrapped = unwrapGroupingParens(formatted);
        if (cursorOffset !== undefined && cursorOffset > 0) {
          cursorOffset = Math.max(0, cursorOffset - 1);
        }
        formatted = unwrapped;
      }
      return { formatted, cursorOffset };
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError;
}

function unwrapGroupingParens(formatted) {
  let text = stripTrailingNewlines(formatted).trim();
  if (text.endsWith(";")) {
    text = text.slice(0, -1).trim();
  }
  if (text.startsWith("(") && text.endsWith(")")) {
    return text.slice(1, -1).trim();
  }
  return text;
}

function toLf(text) {
  return text.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
}

function stripTrailingNewlines(text) {
  return text.replace(/[\n\r]+$/, "");
}

function documentEol(source, option) {
  if (option === "crlf") {
    return "\r\n";
  }
  if (option === "cr") {
    return "\r";
  }
  if (option === "lf") {
    return "\n";
  }
  if (source.includes("\r\n")) {
    return "\r\n";
  }
  if (source.includes("\r")) {
    return "\r";
  }
  return "\n";
}

function continuationIndent(source, offset, tabWidth) {
  const lineStart = source.lastIndexOf("\n", Math.max(offset - 1, 0)) + 1;
  const prefix = source.slice(lineStart, offset);
  if (/^[ \t]*$/.test(prefix)) {
    return prefix;
  }
  return " ".repeat(visualColumn(prefix, tabWidth));
}

function visualColumn(text, tabWidth) {
  let column = 0;
  for (const char of text) {
    if (char === "\t") {
      column += tabWidth - (column % tabWidth);
    } else {
      column += 1;
    }
  }
  return column;
}

function reindent(formattedLf, indent, eol) {
  const lines = formattedLf.split("\n");
  return lines
    .map((line, index) => {
      if (index === 0 || line.length === 0) {
        return line;
      }
      return indent + line;
    })
    .join(eol);
}

function mapCursorThroughReindent(formattedLf, indent, eol, cursor) {
  const clamped = Math.max(0, Math.min(cursor, formattedLf.length));
  const before = formattedLf.slice(0, clamped);
  const lineIndex = before.split("\n").length - 1;
  const lines = formattedLf.split("\n");
  let extra = 0;
  for (let index = 1; index <= lineIndex; index += 1) {
    if ((lines[index] || "").length > 0) {
      extra += indent.length;
    }
  }
  if (eol === "\r\n") {
    extra += lineIndex;
  } else if (eol === "\r") {
    extra += 0;
  }
  return clamped + extra;
}
