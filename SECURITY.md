# Security Policy

## Reporting a vulnerability

Please do **not** open a public issue for security problems.

Email **info@hauschel.de** with the details. This reaches the maintainer
privately and lets us coordinate a fix and disclosure.

## What to include

- A description of the issue and its impact.
- Steps to reproduce, or a minimal proof of concept.
- The affected version or commit, plus any relevant environment details.

## Process and expectations

This project is maintained on a best-effort basis by a single maintainer in
spare time (see [CONTRIBUTING](CONTRIBUTING.md)). There is no guaranteed
response time, but security reports are prioritised over routine issues. Please
allow a reasonable window for a fix before any public disclosure.

## Scope

arknet runs as a local, single-user MCP daemon bound to loopback
(`127.0.0.1`); the Workspace-Dir header is routing, not authentication (see
[ADR-009](docs/adr/adr-009-mcp-http-daemon-transport.md)). Reports
about that trust boundary are in scope; reports that assume the daemon is
exposed to an untrusted network are not -- do not expose it.

## Supported versions

The project is pre-1.0 and still evolving. Only the latest released version
receives fixes; there is no back-porting to older versions.
