# Changelog

## Unreleased

### Fixed

* Exact selection formatting preserves leading/trailing whitespace (spaces, tabs, LF, CRLF) instead of trimming the selection and rewriting the whole range.
* Caret/selection restore only targets the editor that triggered formatting; cross-document textLength guessing and multi-split restore are removed.
* Reverse selections are restored using selection anchor + caret lead.
* Sidecar cache is content-addressed with per-file SHA256 validation (including `formatter.js` in interpreter mode).
* Document size limits and timeouts are aligned with measured sidecar performance; oversized documents are rejected immediately.
* Sidecar process timeout stays strictly below the IntelliJ AsyncDocumentFormattingService timeout.
* Default logs no longer include Mongo query / selection text.

### Changed

* Plugin Verifier CI covers DataGrip 2025.1, 2026.1, and 2026.2.
* Native sidecar CI builds on a per-OS matrix; the assemble job packages the plugin once.
* All QuickJS download artifacts require pinned SHA256 checksums.
* Embedded SQL formatting from the legacy 1.x implementation is currently not supported in 2.x.
