# Full Review Profile — arknet

Project-specific calibration for `/full-review`. Supplements, and where it conflicts overrides,
the skill's generic methodology.

## Module weighting

- Port/contract modules first: `arknet-shared-kernel`, `arknet-persistence-support`, and each
  `*-core`'s `application/port/*` packages carry the promises other modules build on. Weight
  these above domain value objects and above adapters.
- `arknet-shared-kernel` is deliberately tiny (see its own `CLAUDE.md`: "Bewusst winzig"). A full
  read at this size is realistic in one sitting — don't sample, and don't expect a high finding
  yield; see the calibration example below.
- `arknet-architecture-tests` (`DependencyRulesTest`) encodes structural invariants Maven cannot
  express (RDF-freedom of `*-core`, technology concentration in `*RepositoryFactory`). Read this
  file before auditing dependency direction by hand — it documents which invariants are already
  mechanically enforced (with a note that each rule was confirmed to fail when the invariant was
  broken on purpose) and which are "reviewer attention only". Treat the enforced ones as verified
  ground truth, not something to re-derive from scratch.

## Known project-specific traps

- **A port's implementation often lives in a different module than the port itself.** Several
  shared-kernel ports (`ProjectResolver`) have their sole implementation in `arknet-mcp`'s
  composition root, not in a sibling `*-core`/`*-adapter-*` module. Grep the whole repo for
  `implements <PortName>` before concluding a port is unimplemented or before skipping Phase 1's
  interface-vs-implementation comparison — stopping at the port's own module directory misses the
  real implementation entirely.
- **Intentional duplication is flagged in the owning module's `CLAUDE.md`, not as a TODO.**
  Example: `ResourceId`'s forbidden-character check duplicates
  `SparqlTerms.isValidIriReference` (`arknet-persistence-support`) byte-for-byte, because the
  kernel must not depend on that module (RDF-free core rule). Verify the two character sets still
  match rather than filing the duplication itself as a finding — the module's `CLAUDE.md` already
  documents why it exists and won't be resolved.
- **Anchor/Project routing (ADR-016) is the one recurring hot spot.** `ProjectId`,
  `ProjectResolver`, `UnresolvedProjectAnchorException` (kernel) plus `RegisteredAnchorProjectResolver`
  (`arknet-mcp`) together implement "no default, no fallback, registry lookup only". Any future
  change touching project routing should be re-checked against ADR-016 decisions 3 (no default)
  and 5 (no migration of legacy opaque ids) specifically.
- **`CodeAssignment`'s retry loop is duplicated logic made generic, not a shared implementation
  detail.** It exists in the kernel (not `arknet-persistence-support`) because the calling
  `*-core` services must stay free of `io.kogn.rdf` (ArchUnit rule 3), while
  `arknet-persistence-support` carries that dependency. When reviewing any of the five call
  sites (`req`/`ul`/`uc`/`bc`/`adr` application services), confirm the caller's
  `Duplicate<Type>CodeException` is the actual collision signal passed to
  `createRetryingOnCodeCollision`, not a coincidentally-matching supertype.

## Relevant ADRs to keep loaded

- ADR-016 (registered anchors, not derived) — governs `ProjectId`/`ProjectResolver`.
- ADR-009 (shared daemon, per-call resolution) — the reason `ProjectResolver` resolves per call
  rather than injecting a singleton.
- ADR-007 (shared SHACL write gate as its own module) — the reason
  `arknet-persistence-support` exists separately from the kernel despite both being "shared
  technique".
- ADR-013 / ADR-014 (write funnel, revision as concurrency token) — relevant whenever a review
  touches a write path's transaction/concurrency behavior (Phase 2).

## Calibration log

| Date | Scope | Findings | What it confirmed |
|---|---|---|---|
| 2026-08-01 | `arknet-shared-kernel` (full module, ~1092 LOC incl. tests) | 2 minor (a Javadoc `@throws` omission; an untested exception accessor) — both documentation/test-hygiene, no functional holes | Module lives up to its own "bewusst winzig, sorgfaeltig" framing. A near-zero yield here is a correct result, not a sign the review was too shallow — don't manufacture findings to justify the effort. Issues filed: [#133](https://github.com/kogn-io/arknet/issues/133), [#134](https://github.com/kogn-io/arknet/issues/134). |
