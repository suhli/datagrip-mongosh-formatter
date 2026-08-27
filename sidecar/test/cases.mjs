export const CASES = [
  {
    name: "plain js",
    request: {
      protocolVersion: 1,
      source: "const x=1;const y=2;",
      cursorOffset: 6,
    },
    check({ body, result }) {
      assertEq(result.exitCode, 0, "exit code");
      assertEq(body.ok, true, "ok");
      assertEq(body.protocolVersion, 1, "protocol");
      assertIncludes(body.formatted, "const x = 1", "formatted output");
      assertType(body.cursorOffset, "number", "cursorOffset");
    },
  },
  {
    name: "find query",
    request: {
      protocolVersion: 1,
      source: "db.users.find({a:1,b:{$gt:2}})",
    },
    check({ body }) {
      assertEq(body.ok, true, "ok");
      assertIncludes(body.formatted, "db.users.find", "find call");
      assertIncludes(body.formatted, "$gt", "mongo operator");
    },
  },
  {
    name: "aggregate",
    request: {
      protocolVersion: 1,
      source:
        'db.users.aggregate([{$match:{status:"active"}},{$group:{_id:"$type",count:{$sum:1}}}])',
    },
    check({ body }) {
      assertEq(body.ok, true, "ok");
      assertIncludes(body.formatted, "$match", "match stage");
      assertIncludes(body.formatted, "$group", "group stage");
    },
  },
  {
    name: "ObjectId",
    request: {
      protocolVersion: 1,
      source: 'db.users.find({_id:ObjectId("64f000000000000000000000")})',
    },
    check({ body }) {
      assertEq(body.ok, true, "ok");
      assertIncludes(body.formatted, "ObjectId(", "ObjectId call");
    },
  },
  {
    name: "ISODate",
    request: {
      protocolVersion: 1,
      source: 'db.logs.find({createdAt:{$gte:ISODate("2026-01-01T00:00:00Z")}})',
    },
    check({ body }) {
      assertEq(body.ok, true, "ok");
      assertIncludes(body.formatted, "ISODate(", "ISODate call");
    },
  },
  {
    name: "updateMany",
    request: {
      protocolVersion: 1,
      source:
        "db.test.updateMany(\n{$or:[{a:1},{b:2}]},\n{$set:{enabled:true,updatedAt:new Date()}}\n)",
    },
    check({ body }) {
      assertEq(body.ok, true, "ok");
      assertIncludes(body.formatted, "updateMany", "updateMany");
      assertIncludes(body.formatted, "new Date()", "Date constructor");
    },
  },
  {
    name: "mongo globals",
    request: {
      protocolVersion: 1,
      source:
        'db.x.find({a:NumberLong("1"),b:NumberInt("2"),c:Decimal128("1.0"),d:BinData(0,"QQ=="),e:Timestamp(1,2)})',
    },
    check({ body }) {
      assertEq(body.ok, true, "ok");
      for (const name of ["NumberLong", "NumberInt", "Decimal128", "BinData", "Timestamp"]) {
        assertIncludes(body.formatted, name, name);
      }
    },
  },
  {
    name: "multi-statement console",
    request: {
      protocolVersion: 1,
      source: 'use("test")\n\ndb.users.find({a:1,b:2})\n\ndb.logs.aggregate([{$match:{level:"error"}},{$limit:10}])\n',
    },
    check({ body }) {
      assertEq(body.ok, true, "ok");
      assertIncludes(body.formatted, 'use("test")', "use()");
      assertIncludes(body.formatted, "db.users.find", "find");
      assertIncludes(body.formatted, "db.logs.aggregate", "aggregate");
    },
  },
  {
    name: "syntax error",
    request: {
      protocolVersion: 1,
      source: "db.users.find({",
    },
    check({ body, result }) {
      assertEq(body.ok, false, "ok");
      assertTrue(result.exitCode !== 0, "non-zero exit");
      assertType(body.error.message, "string", "error.message");
    },
  },
  {
    name: "unicode chinese",
    request: {
      protocolVersion: 1,
      source: 'db.users.find({name:"中文用户"})',
    },
    check({ body }) {
      assertEq(body.ok, true, "ok");
      assertIncludes(body.formatted, "中文用户", "chinese text");
    },
  },
  {
    name: "emoji surrogate pairs",
    request: {
      protocolVersion: 1,
      source: 'db.users.find({mood:"😀🎉"})',
      rangeStart: 16,
      rangeEnd: 26,
      cursorOffset: 21,
    },
    check({ body }) {
      assertEq(body.ok, true, "ok");
      assertIncludes(body.formatted, "😀🎉", "emoji preserved");
      assertType(body.cursorOffset, "number", "cursorOffset");
      const emojiOffset = body.formatted.indexOf("😀");
      assertTrue(emojiOffset >= 0, "emoji still present");
      assertTrue(body.cursorOffset >= emojiOffset, "cursor stays around emoji");
    },
  },
  {
    name: "crlf",
    request: {
      protocolVersion: 1,
      source: "db.users.find({a:1,b:2})\r\ndb.logs.find({c:3})\r\n",
      options: { endOfLine: "crlf" },
    },
    check({ body }) {
      assertEq(body.ok, true, "ok");
      assertTrue(body.formatted.includes("\r\n"), "keeps crlf");
    },
  },
  {
    name: "range formatting",
    request: {
      protocolVersion: 1,
      source: "db.keep.find({z:9})\ndb.users.find({a:1,b:2})\n",
      rangeStart: 20,
      rangeEnd: 44,
      cursorOffset: 32,
    },
    check({ body }) {
      assertEq(body.ok, true, "ok");
      assertTrue(
        body.formatted.startsWith("db.keep.find({z:9})\n"),
        "unselected statement stays compact",
      );
      assertIncludes(body.formatted, "db.users.find", "selected statement formatted");
      assertTrue(body.formatted !== "db.keep.find({z:9})\ndb.users.find({a:1,b:2})\n", "range changed");
    },
  },
  {
    name: "exact selection keeps trailing newline",
    request: (() => {
      const line = "db.a.find({a:1})";
      const source = `${line}\ndb.b.find({b:2})`;
      return {
        protocolVersion: 1,
        source,
        rangeStart: 0,
        rangeEnd: line.length + 1,
      };
    })(),
    check({ body }) {
      assertEq(body.ok, true, "ok");
      assertTrue(body.formatted.includes("\ndb.b.find({b:2})"), "sibling stays on next line");
      assertTrue(!body.formatted.includes("});db.b"), "must not glue statements");
      assertTrue(body.formatted.startsWith("db.a.find("), "selected statement formatted");
      assertIncludes(body.formatted, "db.b.find({b:2})", "sibling verbatim");
    },
  },
  {
    name: "exact selection keeps leading indentation",
    request: (() => {
      const selected = "  db.a.find({a:1})";
      const source = `function x() {\n${selected}\n}`;
      const rangeStart = source.indexOf(selected);
      return {
        protocolVersion: 1,
        source,
        rangeStart,
        rangeEnd: rangeStart + selected.length,
      };
    })(),
    check({ body }) {
      assertEq(body.ok, true, "ok");
      assertTrue(body.formatted.includes("\n  db.a.find("), "leading indent preserved");
      assertTrue(body.formatted.startsWith("function x() {\n"), "prefix unchanged");
      assertTrue(body.formatted.endsWith("\n}"), "suffix unchanged");
    },
  },
  {
    name: "exact selection keeps leading and trailing whitespace",
    request: (() => {
      const selected = "  db.a.find({a:1})\n";
      const prefix = "db.before.find({x:1})\n";
      const suffix = "db.after.find({y:2})";
      const source = prefix + selected + suffix;
      return {
        protocolVersion: 1,
        source,
        rangeStart: prefix.length,
        rangeEnd: prefix.length + selected.length,
      };
    })(),
    check({ body }) {
      assertEq(body.ok, true, "ok");
      assertTrue(body.formatted.startsWith("db.before.find({x:1})\n"), "prefix unchanged");
      assertTrue(body.formatted.endsWith("db.after.find({y:2})"), "suffix unchanged");
      const mid = body.formatted.slice(
        "db.before.find({x:1})\n".length,
        body.formatted.length - "db.after.find({y:2})".length,
      );
      assertTrue(mid.startsWith("  "), "leading spaces kept");
      assertTrue(mid.endsWith("\n"), "trailing newline kept");
    },
  },
  {
    name: "exact selection keeps trailing crlf",
    request: (() => {
      const selected = "db.a.find({a:1})\r\n";
      const suffix = "db.b.find({b:2})\r\n";
      const source = selected + suffix;
      return {
        protocolVersion: 1,
        source,
        rangeStart: 0,
        rangeEnd: selected.length,
        options: { endOfLine: "crlf" },
      };
    })(),
    check({ body }) {
      assertEq(body.ok, true, "ok");
      assertTrue(body.formatted.endsWith("db.b.find({b:2})\r\n"), "suffix crlf preserved");
      assertTrue(body.formatted.includes("\r\n"), "crlf present");
      assertTrue(!body.formatted.includes("});db.b"), "must not glue over crlf");
      const idx = body.formatted.indexOf("db.b.find");
      assertTrue(body.formatted.slice(idx - 2, idx) === "\r\n", "crlf before sibling");
    },
  },
  {
    name: "nested object selection does not format siblings",
    request: (() => {
      const selected =
        '{\n                        $cond: [{ $lte: ["$lastLogoutMs", cut24] }, 1, 0],\n                        }';
      const prefix = "db.x.aggregate([{ $group: { h24: { $sum: ";
      const suffix = ", }, h72: { $sum: 1 } } }]);";
      return {
        protocolVersion: 1,
        source: prefix + selected + suffix,
        rangeStart: prefix.length,
        rangeEnd: prefix.length + selected.length,
        cursorOffset: prefix.length + 2,
      };
    })(),
    check({ body }) {
      assertEq(body.ok, true, "ok");
      assertIncludes(body.formatted, "h72: { $sum: 1 }", "sibling stays compact");
      assertTrue(
        body.formatted.startsWith("db.x.aggregate([{ $group: { h24: { $sum: "),
        "prefix unchanged",
      );
      assertIncludes(body.formatted, "$cond", "selected object kept");
      assertIncludes(
        body.formatted,
        ", }, h72: { $sum: 1 } } }]);",
        "suffix including sibling stays verbatim",
      );
    },
  },
  {
    name: "prefix suffix invariant with comments blank lines unicode",
    request: (() => {
      const prefix = "// keep me\n\ndb.before.find({z:9})\n\n";
      const selected = "db.users.find({name:\"中文😀\",a:1})";
      const suffix = "\n\n/* trailing */\ndb.after.find({y:2})\n";
      return {
        protocolVersion: 1,
        source: prefix + selected + suffix,
        rangeStart: prefix.length,
        rangeEnd: prefix.length + selected.length,
      };
    })(),
    check({ body }) {
      assertEq(body.ok, true, "ok");
      const prefix = "// keep me\n\ndb.before.find({z:9})\n\n";
      const suffix = "\n\n/* trailing */\ndb.after.find({y:2})\n";
      assertTrue(body.formatted.startsWith(prefix), "prefix bytes unchanged");
      assertTrue(body.formatted.endsWith(suffix), "suffix bytes unchanged");
      assertIncludes(body.formatted, "中文😀", "unicode/emoji preserved");
    },
  },
  {
    name: "incomplete object selection",
    request: {
      protocolVersion: 1,
      source: "db.users.find({a:1,b:2,c:3})",
      rangeStart: 14,
      rangeEnd: 18,
      cursorOffset: 16,
    },
    check({ body }) {
      assertEq(body.ok, true, "ok");
      assertIncludes(body.formatted, "db.users.find", "keeps surrounding call");
    },
  },
  {
    name: "invalid json",
    raw: true,
    request: "{",
    check({ body, result }) {
      assertEq(body.ok, false, "ok");
      assertEq(result.exitCode, 2, "invalid request exit");
    },
  },
  {
    name: "missing protocol",
    request: { source: "const x=1" },
    check({ body, result }) {
      assertEq(body.ok, false, "ok");
      assertEq(result.exitCode, 2, "invalid request exit");
    },
  },
  {
    name: "oversized input",
    request: {
      protocolVersion: 1,
      source: `const x="${"a".repeat(512 * 1024 + 1)}";`,
    },
    check({ body, result }) {
      assertEq(body.ok, false, "ok");
      assertEq(result.exitCode, 2, "invalid request exit");
      assertIncludes(body.error.message, "too large", "size message");
    },
  },
];

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

function assertIncludes(haystack, needle, label) {
  if (!String(haystack).includes(needle)) {
    throw new Error(`${label}: expected to include ${JSON.stringify(needle)}\n${haystack}`);
  }
}

function assertType(value, type, label) {
  if (typeof value !== type) {
    throw new Error(`${label}: expected ${type}, got ${typeof value}`);
  }
}
