# Contributing

Thanks for your interest in arknet.

## Maintenance status

This project is maintained on a best-effort basis by a single maintainer, in
spare time. Issues and pull requests are read and reviewed as capacity allows --
there is no service-level promise and no guaranteed turnaround. A slow or absent
response is not a judgement on your contribution; it just reflects available
time.

## Where things live

- **Code and pull requests** live on GitHub:
  [`github.com/kogn-io/arknet`](https://github.com/kogn-io/arknet). Pull requests
  go here.
- **Bugs, feature requests, and questions** go to
  [GitHub Discussions](https://github.com/kogn-io/arknet/discussions). The
  maintainer triages from there. The issue tracker is deliberately closed: the
  project is pre-1.0 and planned in a private backlog, so an open tracker would
  only look like a queue nobody works.

## Before you open a pull request

For anything beyond a trivial fix (typo, obvious one-line bug), **start a
discussion first** and wait for a short go-ahead. This protects your time as
much as the maintainer's: a large or unsolicited PR that does not fit the
project's scope or design may not be merged, and it is frustrating for everyone
to discover that after the work is done.

Good candidates that rarely need discussion:

- Fixing a clearly wrong behaviour, with a failing test that the fix makes pass.
- Correcting documentation.

Things to raise in a discussion first:

- New MCP tools, ports, or store backends.
- Anything touching a bounded context's domain model or its ontology
  (`arknet-ontology`) / SHACL shapes.
- New dependencies.

## Working on a change

- Branch off `main`; pull requests target `main`.
- Keep pull requests **small and focused** -- one concern per PR. Split unrelated
  changes.
- Follow existing patterns in the code; look before you guess. arknet is a
  hexagonal, DDD-oriented multi-module Maven project -- respect the
  core/adapter/wiring split (ArchUnit rules in `arknet-architecture-tests`
  enforce the dependency invariants).
- Add or adjust tests. Bug fixes start with a failing test.
- Build and test locally before pushing:

  ```bash
  mvn verify
  ```

See the [README](README.md) for the required toolchain (Java 25+, Maven 3.9+).

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/):
`type(scope): subject` (e.g. `fix(requirements): ...`). Common types: `feat`,
`fix`, `docs`, `refactor`, `test`, `build`, `ci`, `chore`, `perf`. Breaking
changes get a `!` after the type or a `BREAKING CHANGE:` footer. The project
follows [Semantic Versioning](https://semver.org/).

## AI-assisted contributions

AI-assisted contributions are allowed. If you use such tools, you remain
responsible for what you submit: understand the change, make sure it is correct,
and test it as you would any other code. Unreviewed machine-generated output is
not a shortcut around the bar above -- and large or sweeping AI-generated changes
may be rejected on scope alone, regardless of correctness.

## Licensing

By submitting a contribution you agree that it is licensed under the project's
[Apache 2.0 license](LICENSE). Do not submit code you do not have the right to
contribute under that license.

## Reporting bugs

Open a thread in [GitHub Discussions](https://github.com/kogn-io/arknet/discussions)
with a minimal reproduction and the expected vs. actual behaviour. For anything
security-sensitive, do **not** post publicly -- see [SECURITY.md](SECURITY.md).
