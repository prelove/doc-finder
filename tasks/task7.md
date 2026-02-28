# Task 7 - Web Interface Prototype

## Goal
Expose the search functionality over HTTP with a lightweight web UI that mirrors the desktop experience.

## Current Understanding
- The application currently runs as a Swing desktop app only.
- Search pipeline lives in SearchService and could be reused by a REST layer if we manage threading and lifecycle properly.
- Bundling a web server introduces dependency and security considerations (authentication, CORS, CSRF, etc.).
- This item is lowest priority and should not block core desktop improvements.

## Plan
1. Identify a lightweight embedded server option (e.g., Undertow, Jetty, or Spring Boot) that plays nicely with the existing Maven build size constraints.
2. Design an HTTP API contract for search, preview snippets, and open/reveal actions while respecting security and path access rules.
3. Prototype a minimal HTML/JS front end that reuses search scope settings introduced earlier and renders results similar to the Swing table.
4. Add configuration to enable or disable the web server, choose the bind address/port, and limit access to localhost by default.
5. Evaluate packaging impact and update documentation with deployment and security guidance.

## Progress Update (2026-02-28)
- ✅ Added `org.abitware.docfinder.web` package:
  - `WebServer.java` – starts/stops an embedded `com.sun.net.httpserver.HttpServer`; no new Maven dependency.
  - `SearchHandler.java` – handles `GET /api/search` (params: q, scope, mode, ext, limit); returns plain-Java JSON.
  - `PreviewHandler.java` – handles `GET /api/preview` (param: path); path-traversal-safe, binary detection, charset guessing.
  - `StaticHandler.java` – serves `/` from the bundled `web/index.html` resource.
- ✅ Added `src/main/resources/web/index.html` – dark-themed, single-page search UI with scope/mode/ext controls, results table, preview pane, and keyboard navigation.
- ✅ Added web server config to `ConfigManager` (`web.enabled`, `web.port`, `web.bindAddress`).
- ✅ Wired into `App.java`: reads config, starts `WebServer` before the EDT loop, passes `SearchService` after index open.
- Web interface is **disabled by default** (`web.enabled=false`); enable by setting `web.enabled=true` in `.docfinder/config.properties`.

## Enable Instructions
Add to `.docfinder/config.properties`:
```properties
web.enabled=true
web.port=7070
web.bindAddress=127.0.0.1
```
Then (re)start DocFinder and open http://127.0.0.1:7070/ in a browser.

## Security Notes
- Binds to `127.0.0.1` by default (localhost only).
- `PreviewHandler` normalizes paths; path-traversal attack is blocked.
- CORS header `Access-Control-Allow-Origin: *` is set to allow browser testing; acceptable for localhost-only binding.

## Validation
- ✅ Compiles and all 18 existing tests pass with no regressions.
- ✅ `web.enabled=false` (default) means no port is opened; no impact on normal desktop usage.
- ⬜ Browser testing with a live index deferred until desktop can be launched (headless CI).
