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
- `arknet-mcp` is the composition root and additionally carries three cross-cutting,
  BC-spanning read paths (`mcp/store`, `mcp/report`, `mcp/trace`) — a contract hole there
  distorts all six hexagons at once. Weight it above the individual BC modules.
- Within `arknet-mcp`, `StoreReader` (`mcp/store/StoreReader.java`) is the single highest
  priority file: all five generic tools (`store_overview`, `resource_get`, `trace_matrix`,
  `orphan_check`, `impact_analysis`) and the HTML report read through the same
  `readSnapshot`/`outgoing`/`incoming` snapshot. A filtering bug there (see #136, blank-node
  subjects) distorts five tools simultaneously, not just one.
- `ArknetMcpConfiguration.java` (~660 lines, the central wiring) doesn't deserve a craftsmanship
  deduction in this skill — that's `/clean-code-review`'s job. For Full Review the only thing
  that matters: is every one of the six BC bean families actually wired per-call rather than as
  a singleton with `ProjectId` (ADR-009), and is there no fallback path without an anchor
  (ADR-016 point 3)? Both held up cleanly on the 2026-08-01 audit.

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
  **Counter-example found in `arknet-mcp` (2026-08-01, issue #148):** the same-looking pattern is
  NOT always intentional — `arkddd:ubiquitousLanguageTerm`/`arkddd:BoundedContext` are duplicated
  between the bc-adapter and `TraceabilityGraph` with a comment claiming `ArkdddVocabulary`'s
  scope is "deliberately limited" to predicates *not* duplicated elsewhere — the comment is
  simply wrong, and no architecture test guards it (the existing `arknet-architecture-tests`
  vocabulary abgleich covers `arkprov`/`arkprj`/`arkarch`, not `arkddd`/`arkproc`). Always verify
  the "intentional" claim against `arknet-architecture-tests` before accepting it — don't take a
  comment's word for it.
- **Anchor/Project routing (ADR-016) is the one recurring hot spot.** `ProjectId`,
  `ProjectResolver`, `UnresolvedProjectAnchorException` (kernel) plus `RegisteredAnchorProjectResolver`
  (`arknet-mcp`) together implement "no default, no fallback, registry lookup only". Any future
  change touching project routing should be re-checked against ADR-016 decisions 3 (no default)
  and 5 (no migration of legacy opaque ids) specifically.
  **Extension found (2026-08-01, issue #149):** the "no call without an anchor" wording is
  absolute in ADR-016/CLAUDE.md but does NOT hold for `project_export`/`project_list`, which are
  deliberately anchor-less (they enumerate across all registered projects). Correct as built, but
  undocumented as an exception — check any future absolute-sounding ADR-016 claim against these
  two tools specifically.
- **`CodeAssignment`'s retry loop is duplicated logic made generic, not a shared implementation
  detail.** It exists in the kernel (not `arknet-persistence-support`) because the calling
  `*-core` services must stay free of `io.kogn.rdf` (ArchUnit rule 3), while
  `arknet-persistence-support` carries that dependency. When reviewing any of the five call
  sites (`req`/`ul`/`uc`/`bc`/`adr` application services), confirm the caller's
  `Duplicate<Type>CodeException` is the actual collision signal passed to
  `createRetryingOnCodeCollision`, not a coincidentally-matching supertype.
  **Related recurring bug (2026-08-01, issue #143):** lexicographic (`String` natural order)
  sorting of unpadded business codes (`ADR-1, ADR-10, ADR-11, ADR-2, ...`) is a bug this repo has
  already fixed once (`KognioRdfAdrRepository`'s numeric comparator) and re-introduced once
  (arknet-mcp's report card builders). Grep for `Comparator.comparing(... code().value())` and
  verify the backing field isn't unpadded numeric text.
- **A read path and a write/export path over the same store can silently diverge on edge cases.**
  Found in `arknet-mcp` (2026-08-01, issue #136): `StoreExporter` handles blank-node subjects
  correctly (has its own test for it), `StoreReader` drops them silently. Whenever two paths
  read/write the same store, check them explicitly against each other, not just against the docs.
- **The MCP tool-callback layer discards the outer exception message in favor of the deepest
  `cause`.** Found in `arknet-mcp` (2026-08-01, issue #137): two independent places compose a
  helpful remedy message and attach the original exception as `cause` — Spring AI's
  `AbstractSyncMcpToolMethodCallback` renders `rootCause.getMessage()`, so the composed message
  never reaches the caller. A unit test asserting on `exception.getMessage()` will not catch
  this; the test has to go through the actual callback down to `CallToolResult`. Ask explicitly:
  "does this message really reach the caller, or only the exception object?"
- **`DisplayLocale` is easy to bypass in a new generic/cross-BC read path.** Found in
  `arknet-mcp` (2026-08-01, issues #141, #145): `StoreResource#label()` and
  `TraceabilityGraph#termLabels()` pick the first literal in triple order, ignoring
  `DisplayLocale`, while the BC-specific report path (`ListTerms`) applies the full fallback
  chain. Causes divergence between digest/traceability tools and the HTML report on
  multi-language `prefLabel`s. Whenever a review touches a new generic read path, check whether
  it bypasses `DisplayLocale` even though a sibling BC out-adapter next to it applies it.
- **kognio-rdf's full source is checked out locally — read it, don't `javap`/guess.**
  `~/DEV/projects/java/kognio/kognio-rdf` (not just the `-sources.jar` under `~/.m2`) carries
  the implementation and its javadoc, including measured backend quirks that aren't visible
  from the port interfaces alone. Example: `DatasetTransactorRdf4j`'s own class javadoc
  documents an RDF4J `MemoryStore` value-interning race that makes an `ASK`/`SELECT` guard
  unreliable for a brand-new, not-yet-persisted IRI, while `DatasetTx#contains` is immune
  (it looks values up instead of interning them). `WriteFunnel`'s two `create` existence
  checks already use `contains()` for exactly this reason; its `readHead`'s `SELECT` doesn't
  need to, because it only ever reads already-persisted state (an existing resource's head).
  Confirm a concurrency/isolation-adjacent suspicion against this checkout before filing it as
  a finding — two suspicions in the persistence-support review below turned out to be
  unfounded once checked here.
- **A missing `sh:maxCount` is a multi-valued vector for every predicate it's missing on, not
  just the ones a class's own comment calls out.** Found in `arknet-ubiquitous-language`
  (2026-08-01, issue #154): `KognioRdfTermRepository` explicitly documents and handles
  store-first row multiplication for `skos:prefLabel`/`skos:definition` (no `sh:maxCount` in
  `ul-shapes.ttl`) — but a code comment on the same read-assembly method claims
  `arkproc:actorRole` has "no reported store-first multiplicity vector", which is false: neither
  `ul-shapes.ttl` nor `arknet-actor.ttl` constrain its cardinality either. The class already
  built the right mechanism (collect candidates, warn on distinct collision) for `definition`,
  just didn't extend it to `actorRole`. When a class handles row-multiplication for some
  predicates on a shape, grep the *whole* shapes file (and any sibling ontology module the
  predicate's `rdfs:domain` lives in) for `sh:maxCount` on every predicate the same query joins,
  rather than trusting a comment that says "this one's fine."

## Relevant ADRs to keep loaded

- ADR-016 (registered anchors, not derived) — governs `ProjectId`/`ProjectResolver`.
- ADR-009 (shared daemon, per-call resolution) — the reason `ProjectResolver` resolves per call
  rather than injecting a singleton.
- ADR-007 (shared SHACL write gate as its own module) — the reason
  `arknet-persistence-support` exists separately from the kernel despite both being "shared
  technique".
- ADR-013 / ADR-014 (write funnel, revision as concurrency token) — relevant whenever a review
  touches a write path's transaction/concurrency behavior (Phase 2).
- ADR-006 (generic store read path) / ADR-008 (in-adapter as BC gateway, the "borrow" pattern) —
  relevant whenever a review touches `arknet-mcp`'s `mcp/store`/`mcp/report`/`mcp/trace`, since
  those packages exist entirely because of these two ADRs.

## Calibration log

| Date | Scope | Findings | What it confirmed |
|---|---|---|---|
| 2026-08-01 | `arknet-persistence-support` (full module, ~2719 LOC incl. tests) | 1 minor (`WriteFunnel`'s constructors and `create`/`update`/`compareAndUpdate` have untested `Objects.requireNonNull` guards — `ShaclWriteGateTest` has the analogous test, `WriteFunnelTest` doesn't) — test-hygiene, no functional holes | Two initial suspicions (commit-conflict misclassification via `isWriteConflict`; `readHead`'s `SELECT` being an unreliable CAS guard the way an `ASK` guard would be) were both refuted by reading the actual kognio-rdf source rather than trusting the port's javadoc alone — see the trap above. Issue filed: [#132](https://github.com/kogn-io/arknet/issues/132). |
| 2026-08-01 | `arknet-shared-kernel` (full module, ~1092 LOC incl. tests) | 2 minor (a Javadoc `@throws` omission; an untested exception accessor) — both documentation/test-hygiene, no functional holes | Module lives up to its own "bewusst winzig, sorgfaeltig" framing. A near-zero yield here is a correct result, not a sign the review was too shallow — don't manufacture findings to justify the effort. Issues filed: [#133](https://github.com/kogn-io/arknet/issues/133), [#134](https://github.com/kogn-io/arknet/issues/134). |
| 2026-08-01 | `arknet-mcp` (full module, 64 files, ~10.3k LOC incl. tests; 4 parallel subagents split by disjoint package: dataset+store, report, trace+mention, composition root) | 16 findings, 2 critical (silent under-reporting of dependents/statements from two independent tools), 8 high, 6 medium/low | Highest-yield full review to date, roughly proportional to module size and to how much prose-documented behavior (`@McpTool` descriptions, a very dense module `CLAUDE.md`) the module carries to audit against. Phase 1 Sweep 4 (adjectives about runtime behavior — "without cache", "case-insensitive", "the first label", "in encounter order", "the longer one wins") was by far the most productive sweep here. Phase 2 (concurrency) was clean — all tool beans are stateless singletons, `ProjectId` is consistently a method parameter, no `ThreadLocal` anywhere in the MCP stack; a 50-request/8-thread throwaway probe against two anchors confirmed zero cross-project mixing. Issues filed: [#135](https://github.com/kogn-io/arknet/issues/135)–[#150](https://github.com/kogn-io/arknet/issues/150). |
| 2026-08-01 | `arknet-ubiquitous-language` (full module, 19 main + 10 test files, ~4.8k LOC incl. tests; single-session read, no subagent split — small enough) | 2 minor (silent loss of a store-first multi-valued `arkproc:actorRole`, no functional risk; a stale tool-count in `ArknetMcpConfiguration`'s Javadoc) — no functional holes reachable via the MCP tools | Second near-zero-yield module after `arknet-shared-kernel`: every non-obvious contract claim (no-read-before-merge on `update`, role-preserved-on-kind-change, CAS retry with atomic state+head read, blank-node guard, row-multiplication on multi-valued `prefLabel`/`definition`) was not only correctly implemented but backed by a dedicated regression test, several explicitly commented "Bug 1/2/3, found reviewing the PR". The one real gap (issue #154) was found by extending the module's *own* documented row-multiplication pattern to a predicate its own comment wrongly claimed was exempt — see the new trap above. Concurrency (Phase 2) fully covered by two dedicated tests (deterministic interleaving injection + real RDF4J store with real threads for the second-interleaving case). Issues filed: [#154](https://github.com/kogn-io/arknet/issues/154), [#155](https://github.com/kogn-io/arknet/issues/155). |
