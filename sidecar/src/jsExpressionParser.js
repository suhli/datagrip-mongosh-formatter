/**
 * Isolates Prettier's internal `__js_expression` parser.
 *
 * This is not a documented public Prettier API. Exact-selection formatting uses
 * it as the first attempt for bare expressions/fragments. Keep all references
 * here so upgrades can be validated in one place (see formatter-unit tests).
 */
export const JS_EXPRESSION_PARSER = "__js_expression";

export function jsExpressionAttempt(core) {
  return {
    parser: JS_EXPRESSION_PARSER,
    text: core,
    cursorShift: 0,
    unwrap: false,
  };
}
