/**
 * Node-side unit tests for exact-selection internals and strategy selection.
 * These import source modules directly (not the QuickJS bundle) so we can
 * assert strategy without exposing it on the IPC protocol.
 */
import { formatMongoJs, splitSelectionBounds } from "../src/formatter.js";
import { JS_EXPRESSION_PARSER, jsExpressionAttempt } from "../src/jsExpressionParser.js";
import { MAX_SOURCE_CHARS, SAFE_SOURCE_CHARS } from "../src/protocol.js";

function assertEq(actual, expected, label) {
  if (actual !== expected) {
    throw new Error(`${label}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
  }
}

function assertTrue(value, label) {
  if (!value) {
    throw new Error(`${label}: expected truthy`);
  }
}

const failures = [];

async function test(name, fn) {
  try {
    await fn();
    console.log(`ok  ${name}`);
  } catch (error) {
    failures.push(`${name}: ${error.message}`);
    console.error(`FAIL ${name}: ${error.message}`);
  }
}

await test("splitSelectionBounds preserves leading and trailing whitespace", () => {
  const source = "db.x\n  code()\n";
  const selected = "  code()\n";
  const start = source.indexOf(selected);
  const bounds = splitSelectionBounds(source, start, start + selected.length);
  assertEq(bounds.leading, "  ", "leading");
  assertEq(bounds.core, "code()", "core");
  assertEq(bounds.trailing, "\n", "trailing");
});

await test("splitSelectionBounds keeps crlf trailing", () => {
  const source = "db.a.find({a:1})\r\ndb.b.find({b:2})";
  const end = source.indexOf("db.b");
  const bounds = splitSelectionBounds(source, 0, end);
  assertEq(bounds.trailing, "\r\n", "trailing crlf");
  assertEq(bounds.core, "db.a.find({a:1})", "core");
});

await test("exact strategy for complete expression", async () => {
  const source = "db.a.find({a:1})\ndb.b.find({b:2})";
  const result = await formatMongoJs({
    source,
    rangeStart: 0,
    rangeEnd: "db.a.find({a:1})\n".length,
  });
  assertEq(result.strategy, "exact", "strategy");
  assertTrue(result.formatted.includes("\ndb.b.find({b:2})"), "sibling protected");
  assertTrue(!result.formatted.includes("});db.b"), "no glue");
});

await test("exact strategy for complete statement", async () => {
  const source = "const x={a:1}; const y=2;";
  const result = await formatMongoJs({
    source,
    rangeStart: 0,
    rangeEnd: "const x={a:1};".length,
  });
  assertEq(result.strategy, "exact", "strategy");
  assertTrue(result.formatted.endsWith(" const y=2;"), "suffix unchanged");
});

await test("exact strategy for object literal fragment", async () => {
  const prefix = "db.x.find(";
  const selected = "{a:1,b:2}";
  const suffix = ")";
  const source = prefix + selected + suffix;
  const result = await formatMongoJs({
    source,
    rangeStart: prefix.length,
    rangeEnd: prefix.length + selected.length,
  });
  assertEq(result.strategy, "exact", "strategy");
  assertEq(result.formatted.slice(0, prefix.length), prefix, "prefix");
  assertEq(result.formatted.slice(-suffix.length), suffix, "suffix");
});

await test("exact strategy for array fragment", async () => {
  const prefix = "db.x.aggregate(";
  const selected = "[{$match:{a:1}}]";
  const suffix = ")";
  const source = prefix + selected + suffix;
  const result = await formatMongoJs({
    source,
    rangeStart: prefix.length,
    rangeEnd: prefix.length + selected.length,
  });
  assertEq(result.strategy, "exact", "strategy");
  assertEq(result.formatted.slice(0, prefix.length), prefix, "prefix");
  assertEq(result.formatted.slice(-suffix.length), suffix, "suffix");
});

await test("incomplete fragment falls back to range strategy", async () => {
  const source = "db.users.find({a:1,b:2,c:3})";
  const result = await formatMongoJs({
    source,
    rangeStart: 14,
    rangeEnd: 18, // "{a:1" — incomplete object
  });
  assertEq(result.strategy, "range", "strategy");
  assertTrue(result.formatted.includes("db.users.find"), "keeps call");
});

await test("prefix suffix invariant under exact path", async () => {
  const prefix = "/* before */\n\n";
  const selected = "db.a.find({a:1,b:2})";
  const suffix = "\n\n// after 中文😀\n";
  const source = prefix + selected + suffix;
  const result = await formatMongoJs({
    source,
    rangeStart: prefix.length,
    rangeEnd: prefix.length + selected.length,
  });
  assertEq(result.strategy, "exact", "strategy");
  assertEq(result.formatted.slice(0, prefix.length), prefix, "prefix bytes");
  assertEq(result.formatted.slice(-suffix.length), suffix, "suffix bytes");
});

await test("__js_expression parser is isolated", () => {
  assertEq(JS_EXPRESSION_PARSER, "__js_expression", "parser id");
  const attempt = jsExpressionAttempt("db.x.find({a:1})");
  assertEq(attempt.parser, JS_EXPRESSION_PARSER, "attempt parser");
});

await test("size thresholds are realistic", () => {
  assertTrue(SAFE_SOURCE_CHARS < MAX_SOURCE_CHARS, "safe < max");
  assertTrue(MAX_SOURCE_CHARS <= 512 * 1024, "max is not multi-MB");
  assertTrue(SAFE_SOURCE_CHARS >= 128 * 1024, "safe allows medium docs");
});

if (failures.length > 0) {
  console.error(`\n${failures.length} formatter unit test(s) failed`);
  process.exit(1);
}

console.log("\nAll formatter unit tests passed");
