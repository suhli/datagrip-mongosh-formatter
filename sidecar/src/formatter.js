import * as prettier from "prettier/standalone";
import * as babelPlugin from "prettier/plugins/babel";
import * as estreePlugin from "prettier/plugins/estree";

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
      return await formatExactSelection(request, options);
    } catch {
      options.rangeStart = request.rangeStart;
      options.rangeEnd = request.rangeEnd;
    }
  }

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
 * Format only the selected slice and splice it back.
 *
 * Prettier's own rangeStart/rangeEnd expand to the enclosing statement, which
 * in a Mongo console turns a nested object selection into a whole-pipeline
 * reformat. Exact-slice formatting keeps siblings untouched.
 */
async function formatExactSelection(request, options) {
  const { source, rangeStart, rangeEnd } = request;
  const selected = source.slice(rangeStart, rangeEnd);
  const core = selected.trim();
  if (!core) {
    return { formatted: source, cursorOffset: request.cursorOffset };
  }

  const leading = selected.match(/^\s*/)[0];
  const innerCursor =
    request.cursorOffset !== undefined &&
    request.cursorOffset >= rangeStart + leading.length &&
    request.cursorOffset <= rangeStart + leading.length + core.length
      ? request.cursorOffset - (rangeStart + leading.length)
      : undefined;

  const fragment = await formatFragment(core, options, innerCursor);
  const eol = documentEol(source, options.endOfLine);
  const formattedLf = stripTrailingNewlines(toLf(fragment.formatted));
  const indent = continuationIndent(source, rangeStart, options.tabWidth || 2);
  const nextSelected = reindent(formattedLf, indent, eol);
  const formatted = source.slice(0, rangeStart) + nextSelected + source.slice(rangeEnd);

  let cursorOffset = request.cursorOffset;
  if (cursorOffset !== undefined) {
    if (cursorOffset <= rangeStart) {
      // before the selection
    } else if (cursorOffset >= rangeEnd) {
      cursorOffset += nextSelected.length - selected.length;
    } else {
      const mapped =
        fragment.cursorOffset !== undefined && fragment.cursorOffset >= 0
          ? mapCursorThroughReindent(formattedLf, indent, eol, fragment.cursorOffset)
          : nextSelected.length;
      cursorOffset = rangeStart + mapped;
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
    { parser: "__js_expression", text: core, cursorShift: 0, unwrap: false },
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
