/**
 * Minimal QuickJS compatibility layer for Prettier Standalone.
 *
 * Only APIs that Prettier 3.9 actually touches are implemented. This is not a
 * general browser polyfill. Probe missing globals by running the sidecar tests
 * on the pinned QuickJS-ng binary rather than guessing.
 */

export function installPolyfills(globalObject) {
  const g = globalObject;

  if (typeof g.globalThis === "undefined") {
    g.globalThis = g;
  }

  if (typeof g.queueMicrotask !== "function") {
    g.queueMicrotask = (callback) => {
      Promise.resolve().then(callback);
    };
  }

  if (typeof g.performance === "undefined" || typeof g.performance.now !== "function") {
    const epoch = Date.now();
    g.performance = {
      now() {
        return Date.now() - epoch;
      },
    };
  }

  if (typeof g.TextEncoder !== "function") {
    g.TextEncoder = SimpleTextEncoder;
  }

  if (typeof g.TextDecoder !== "function") {
    g.TextDecoder = SimpleTextDecoder;
  }

  if (typeof g.structuredClone !== "function") {
    g.structuredClone = (value) => JSON.parse(JSON.stringify(value));
  }

  if (typeof g.setTimeout !== "function") {
    g.setTimeout = (fn) => {
      g.queueMicrotask(fn);
      return 0;
    };
  }

  if (typeof g.clearTimeout !== "function") {
    g.clearTimeout = () => {};
  }
}

class SimpleTextEncoder {
  encode(input) {
    const str = input === undefined || input === null ? "" : String(input);
    const bytes = [];
    for (let i = 0; i < str.length; i += 1) {
      let code = str.charCodeAt(i);
      if (code >= 0xd800 && code <= 0xdbff && i + 1 < str.length) {
        const extra = str.charCodeAt(i + 1);
        if (extra >= 0xdc00 && extra <= 0xdfff) {
          code = ((code - 0xd800) << 10) + (extra - 0xdc00) + 0x10000;
          i += 1;
        }
      }
      if (code < 0x80) {
        bytes.push(code);
      } else if (code < 0x800) {
        bytes.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
      } else if (code < 0x10000) {
        bytes.push(
          0xe0 | (code >> 12),
          0x80 | ((code >> 6) & 0x3f),
          0x80 | (code & 0x3f),
        );
      } else {
        bytes.push(
          0xf0 | (code >> 18),
          0x80 | ((code >> 12) & 0x3f),
          0x80 | ((code >> 6) & 0x3f),
          0x80 | (code & 0x3f),
        );
      }
    }
    return Uint8Array.from(bytes);
  }
}

class SimpleTextDecoder {
  constructor(label, options) {
    this.fatal = Boolean(options && options.fatal);
  }

  decode(input) {
    if (input === undefined || input === null) {
      return "";
    }
    const bytes =
      input instanceof Uint8Array
        ? input
        : input instanceof ArrayBuffer
          ? new Uint8Array(input)
          : Uint8Array.from(input);
    let out = "";
    for (let i = 0; i < bytes.length; ) {
      const b1 = bytes[i];
      let code;
      let size;
      if (b1 < 0x80) {
        code = b1;
        size = 1;
      } else if ((b1 & 0xe0) === 0xc0) {
        code = ((b1 & 0x1f) << 6) | (bytes[i + 1] & 0x3f);
        size = 2;
      } else if ((b1 & 0xf0) === 0xe0) {
        code =
          ((b1 & 0x0f) << 12) |
          ((bytes[i + 1] & 0x3f) << 6) |
          (bytes[i + 2] & 0x3f);
        size = 3;
      } else {
        code =
          ((b1 & 0x07) << 18) |
          ((bytes[i + 1] & 0x3f) << 12) |
          ((bytes[i + 2] & 0x3f) << 6) |
          (bytes[i + 3] & 0x3f);
        size = 4;
      }
      i += size;
      if (code > 0xffff) {
        const shifted = code - 0x10000;
        out += String.fromCharCode(0xd800 + (shifted >> 10), 0xdc00 + (shifted & 0x3ff));
      } else {
        out += String.fromCharCode(code);
      }
    }
    return out;
  }
}
