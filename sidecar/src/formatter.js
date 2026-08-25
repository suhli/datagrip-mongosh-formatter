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

  if (request.rangeStart !== undefined && request.rangeEnd !== undefined) {
    options.rangeStart = request.rangeStart;
    options.rangeEnd = request.rangeEnd;
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
