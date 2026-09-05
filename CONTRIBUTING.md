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
- **Bugs and feature requests** go to the
  [issue tracker](https://github.com/kogn-io/arknet/issues).
- **Open-ended questions** go to
  [GitHub Discussions](https://github.com/kogn-io/arknet/discussions).

## arknet's own architecture decisions

arknet's own decisions about its own build -- Bounded Context cuts, storage
choices, persistence mechanics and so on -- are recorded exclusively in
arknet's own store, via the `adr_*` MCP tools, not as Markdown files in
this repository.

- **`docs/adr-export/`** is a generated snapshot of that store, not a second
  source of truth -- see its own [README](docs/adr-export/README.md) for what
  it contains and how it is regenerated. Never hand-edit it, and never use it
  as a merge base.
- **Regenerate it after every change to arknet's own store decisions** and
  commit the result together with the change that caused it, so the
  repository's commit history and its releases carry the model state, not
  just the code. This is a transition measure until commit-provenance linking
  makes the export current by other means; once that lands, this rule can be
  relaxed.

## Before you open a pull request

For anything beyond a trivial fix (typo, obvious one-line bug), **open an
issue first** and wait for a short go-ahead. This protects your time as
much as the maintainer's: a large or unsolicited PR that does not fit the
project's scope or design may not be merged, and it is frustrating for everyone
to discover that after the work is done.

Good candidates that rarely need an issue first:

- Fixing a clearly wrong behaviour, with a failing test that the fix makes pass.
- Correcting documentation.

Things to raise in an issue first:

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
  mvn -T 1C verify
  ```

  `-T 1C` builds the reactor with one thread per core, which is measurably
  faster and is the same verification CI runs on your pull request (plain
  `mvn verify` is equivalent, just serial).

Toolchain: Java 25+, Maven 3.9+.

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

## AI processing of contributions

Issues, discussions, and pull request content in this repository may be
processed by AI systems (Anthropic). If you do not want that, please do not
post here. Please refrain from posting personally identifiable information
(PII) or similarly sensitive data in issues, discussions, or pull requests.

## Licensing

By submitting a contribution you agree that it is licensed under the project's
[Apache 2.0 license](LICENSE). Do not submit code you do not have the right to
contribute under that license.

## Reporting bugs

Open an [issue](https://github.com/kogn-io/arknet/issues) with a minimal
reproduction and the expected vs. actual behaviour. For anything
security-sensitive, do **not** post publicly -- see [SECURITY.md](SECURITY.md).
