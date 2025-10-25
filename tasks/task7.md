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

## Validation
- Optional web mode starts without affecting desktop startup when disabled.
- Running in web mode produces matching search results across desktop and browser clients.
- Security review confirms sensible defaults (localhost binding, no unauthenticated remote access by default).
