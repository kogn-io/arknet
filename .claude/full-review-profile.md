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
  ground truth, not something to re-derive from scratch. **Its reach stops at its own `pom.xml`
  dependencies, though** (issues #185/#191, 2026-08-01): `arknet-mcp` is never declared as a test
  dependency, so `DependencyRulesTest`'s `@AnalyzeClasses` cannot see a single class in
  `de.hauschel.arknet.mcp..` — not "reviewer attention only" but structurally invisible — even
  though `ArknetMcpConfiguration`/`StoreReader`/`TraceabilityGraph` each carry their own explicit
  "stays free of RDF4J" Javadoc claim. Before trusting this module's silence on a given package as
  "verified clean", check its `pom.xml` actually depends on that package's module.
- `arknet-mcp` is the composition root and additionally carries three cross-cutting,
  BC-spanning read paths (`mcp/store`, `mcp/report`, `mcp/trace`) — a contract hole there
  distorts all six hexagons at once. Weight it above the individual BC modules.
- Within `arknet-mcp`, `StoreReader` (`mcp/store/StoreReader.java`) is the single highest
  priority file: all five generic tools (`store_overview`, `resource_get`, `trace_matrix`,
  `orphan_check`, `impact_analysis`) and the HTML report read through the same
  `readSnapshot`/`outgoing`/`incoming` snapshot. A filtering bug there (see #136, blank-node
  subjects) distorts five tools simultaneously, not just one. `resource_history` (issue #251)
  shares only the existence check (`outgoing`/`incoming`, for its "not found" case) with that
  snapshot, not the snapshot itself: its revision data comes from `StoreReader#history`, a
  deliberately separate query over `ArkprovVocabulary#PROVENANCE_GRAPH` - the one graph the
  shared snapshot excludes on purpose.
- `ArknetMcpConfiguration.java` (~660 lines, the central wiring) doesn't deserve a craftsmanship
  deduction in this skill — that's `/clean-code-review`'s job. For Full Review the only thing
  that matters: is every one of the six BC bean families actually wired per-call rather than as
  a singleton with `ProjectId` (ADR-009), and is there no fallback path without an anchor
  (ADR-016 point 3)? Both held up cleanly on the 2026-08-01 audit.

## Review cadence

Feeds the skill's `next` mode (pick the module due for review without anyone having to decide).
Priority: 1 = port/contract-carrying and cross-cutting (weighted above the rest per the module
weighting above), 2 = the six hexagonal BCs, 3 = self-check/meta module. `arknet-ontology` carries
no Java ports (`.ttl` resources only) and is out of scope for this skill entirely — not listed.

| Module | Path | Priority | Last reviewed commit | Date |
|---|---|---|---|---|
| `arknet-mcp` | `arknet-mcp` | 1 | `f7ad8db8` | 2026-08-06 |
| `arknet-shared-kernel` | `arknet-shared-kernel` | 1 | `e157bc5` | 2026-08-01 |
| `arknet-persistence-support` | `arknet-persistence-support` | 1 | `8a579e5` | 2026-08-01 |
| `arknet-requirements` | `arknet-requirements` | 2 | `266e7ea` | 2026-08-01 |
| `arknet-ubiquitous-language` | `arknet-ubiquitous-language` | 2 | `18bc394` | 2026-08-01 |
| `arknet-use-cases` | `arknet-use-cases` | 2 | `941b4aa` | 2026-08-01 |
| `arknet-bounded-context` | `arknet-bounded-context` | 2 | `c6113c7` | 2026-08-01 |
| `arknet-project` | `arknet-project` | 2 | `ae218d2` | 2026-08-01 |
| `arknet-adr` | `arknet-adr` | 2 | `3187cdc` | 2026-08-01 |
| `arknet-architecture-tests` | `arknet-architecture-tests` | 3 | `e228741` | 2026-08-01 |

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
  absolute in ADR-016/CLAUDE.md but does NOT hold for `project_list`, nor for `project_export` in
  its default scope, which enumerate across all registered projects. Correct as built, but
  undocumented as an exception — check any future absolute-sounding ADR-016 claim against these
  two tools specifically. `project_export`'s `projectOnly=true` scope (2026-09-02) does resolve an
  anchor, through the same `AnchorContext` path as every other tool, and is bound by decision 3
  again: the narrowed export has no fall-back to the full one. It also carries the one exception to
  "every tool takes an optional `projectAnchor` parameter": `project_export` takes one, but rejects
  it outside that scope instead of ignoring it, because the default scope addresses no single
  project and an anchor silently dropped there would hand the caller the opposite of what it asked
  for.
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
  **Third occurrence (2026-08-01, issue #186):** `arknet-adr`'s `AdrMcpTools#parseDate` catches a
  `DateTimeParseException` and wraps it in an `IllegalArgumentException` with a helpful
  ISO-8601-format message — but passes the original as `cause`, so the caller-visible text is the
  raw JDK parse message, not the helpful one. Same module's own `setStatus` (a few methods away)
  gets this right by never wrapping at all. Grep every `*McpTools` class for `catch` blocks that
  construct a new exception with a message *and* a `cause` argument — that combination is the
  smell, regardless of how carefully the message itself is worded.
- **A dedupe-by-comparator `Set` silently drops distinct elements whose comparator collides, even
  when they are not `equal`.** Found in `arknet-adr` (2026-08-01, issue #187): both
  `AdrService.list()` and `KognioRdfAdrRepository.findSupersedingCodes` collect superseding
  business codes into a `TreeSet<String>` ordered by a parsed-running-number comparator
  (`CODE_BY_RUNNING_NUMBER`) — deliberately not lexicographic, to fix the sorting bug from issue
  #143. But `TreeSet` uses the comparator for *uniqueness* too, not just ordering: two distinct
  strings that the comparator ranks equal (e.g. two non-`ADR-N`-shaped codes that both fail to
  parse and default to running number 0 — only reachable via a store-first-inserted code that
  doesn't follow the business-code convention) collapse into one, and the second is silently
  dropped with no warning. Whenever a review finds a `TreeSet`/`TreeMap` keyed by a comparator
  that is *not* a total, injective ordering of the real domain (parsed numbers, truncated
  strings, rounded values, ...), check whether two genuinely different inputs can tie — that's a
  silent-data-loss vector, not just a sorting concern.
- **`DisplayLocale` is easy to bypass in a new generic/cross-BC read path.** Found in
  `arknet-mcp` (2026-08-01, issues #141, #145): `StoreResource#label()` and
  `TraceabilityGraph#termLabels()` pick the first literal in triple order, ignoring
  `DisplayLocale`, while the BC-specific report path (`ListTerms`) applies the full fallback
  chain. Causes divergence between digest/traceability tools and the HTML report on
  multi-language `prefLabel`s. Whenever a review touches a new generic read path, check whether
  it bypasses `DisplayLocale` even though a sibling BC out-adapter next to it applies it.
  **Extension found (2026-08-01, `arknet-use-cases`, issue #156):** the same bypass also shows up
  in a *cross-BC lookup* out-adapter, not just a generic `arknet-mcp` read path —
  `KognioRdfActorLookup.resolveByName` matches `skos:prefLabel` with an ungetaggten SPARQL literal
  (`"escaped(actorName)"`), which fails against a language-tagged `"Customer"@en` even though the
  sibling `KognioRdfTermRepository` (ul out-adapter) already treats the identical multi-language
  `prefLabel` case as a first-class concern with `DisplayLocale.select`'s fallback chain. Whenever
  a BC resolves a *neighbour* BC's business label by exact-match SPARQL (name→id lookups across
  BCs, ADR-008), check it against `DisplayLocale` the same way a same-BC read path would be
  checked — the cross-BC boundary doesn't exempt it.
- **Port `@throws` documentation gaps are a recurring, not a one-off, finding.** Found in
  `arknet-shared-kernel` (#133/#134) and again in `arknet-use-cases` (2026-08-01, issue #159):
  in-ports (`AddUseCase`, `UpdateUseCase`) and out-ports (`ActorLookup`, `RequirementLookup`)
  document the thrown-exception behavior only in class-level prose, or only on a *private* helper
  method, not as an `@throws` tag on the port method itself — while a sibling out-port in the same
  module (`UseCaseRepository`) documents its `@throws` fully. Check this asymmetry (some ports in
  a module fully documented, others not) explicitly on every future full-review rather than
  assuming one clean module means the pattern is gone.
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
  **Second dimension found in `arknet-bounded-context` (2026-08-01, issue #158):** the vector
  isn't only "which predicates" but also "which read methods". `KognioRdfBoundedContextRepository`'s
  `findAll` correctly groups rows per subject and warns on collapsed `ownedBy`/`subdomain`
  multi-values (both predicates lack `sh:maxCount` in `arknet-shapes.ttl`, `sh:Warning` only) —
  but the sibling `findByCode`/`findCurrentByCode` join the exact same predicates without that
  grouping, and `.findFirst()` over the resulting SPARQL cross-product silently picks an
  arbitrary pairing. Because that read feeds `compareAndUpdate`'s replace-by-identity write, the
  read-side inconsistency becomes permanent data loss on the next unrelated write (e.g. a
  `bc_link_term` call touching a completely different field). When a class handles
  row-multiplication for a predicate in one read method, grep the *whole class* for every other
  method that joins the same predicate — a comment or mechanism on one method says nothing about
  its siblings.
- **A field's SHACL `sh:severity` matters as much as its `sh:in`/type shape.** `sh:Warning`
  fields are store-first writeable past whatever the Java read path assumes — only
  `sh:Violation` actually blocks a write. Found in `arknet-requirements` (2026-08-01, issues
  #160, #163): `RequirementStatus` implements only 2 of 6 SHACL-legal status individuals, and
  `findAll`/`findByCode` resolve the raw IRI/typed-term inline inside the per-row processing
  lambda with no guard for the unmapped case — one store-first-legal value (a `sh:Violation`
  field the Java enum doesn't cover, or a `sh:Warning` field with the wrong RDF term kind)
  throws and aborts the *entire* batch read, not just the offending row. The module already
  handles the analogous case correctly elsewhere in the same file (the `type` filter, the
  acceptance-criteria placeholder) but didn't generalize the pattern to every enum-from-IRI or
  typed-cast-from-RDF-term resolution. Check every such resolution against the field's actual
  SHACL severity and full value space, not just the Java enum's MVP subset, and check whether a
  single bad row aborts the whole read.
- **A read-time "surface the gap instead of crashing" placeholder can silently become permanent
  write-time data.** Found in `arknet-requirements` (2026-08-01, issue #157):
  `findCurrentByCode` substitutes a fixed placeholder string for missing legacy
  `acceptanceCriteria` so `req_get`/`req_list` don't crash on old data — but that same
  `Requirement` object is also the read-modify-write source every optional-field mutation
  (`status`, `title`, ...) reads from when the caller passes `null` ("leave this field
  unchanged"). The next `compareAndUpdate` persists the placeholder as real, no-longer
  suspicious data — required an empirical throwaway test against the real store to confirm,
  static reading alone made it look safe. Whenever a read path substitutes a display-only stand-in
  into a domain object, check whether that same object doubles as another path's "unchanged"
  source for a subsequent write.
- **A CAS-protected write's dependent side-effect write isn't automatically ordered with it.**
  Found in `arknet-project` (2026-08-01, issue #173): `KognioRdfProjectRegistry#compareAndUpdate`
  is CAS-protected and revisioned; `KognioRdfProjectSelfDescription#describe` is a deliberate,
  documented idempotent replace *without* a CAS check (adding one would reopen the TOCTOU window
  the design avoids). `ProjectService` calls `describe()` right after every successful registry
  write, and the module's own docs claim resulting drift "self-heals on the next successful
  registry write" — but nothing orders two concurrent `describe()` calls to commit in the same
  sequence as the two `compareAndUpdate` calls that triggered them, so the *later*-committing
  `describe()` (not necessarily the one for the *later* registry write) wins and can leave
  actively stale, not just missing, content. Whenever a CAS-protected write has a companion
  "just replace it, idempotently" write derived from it, check whether the two are ever allowed
  to interleave out of order — "idempotent" only means "safe to repeat the same value", not
  "safe under concurrent, differently-ordered writers".
- **A retry-safety fix applied to one entry point doesn't automatically extend to a structurally
  symmetric sibling.** Found in `arknet-project` (2026-08-01, issue #174): `register()` retries
  transparently on a lost commit race (`registerRetryingOnUnattributedConflict`, added by issue
  #67 specifically to close this gap for registration). `adopt()` calls the same
  `registry.register(project)` under the same TOCTOU exposure (existence-check then write, no
  transaction spanning both) but has no retry wrapper — a losing concurrent `project_adopt` call
  gets a raw, port-undocumented `ResourceAlreadyExistsException` instead. When a past issue's
  title says "add retry/CAS-safety to X", grep for other call sites that hit the same guarded
  resource through a structurally similar path and check whether the fix was generalized or only
  patched the one reported symptom.

- **A new resource type or edge shipped by a BC module does not automatically reach the
  cross-cutting read paths — check every inventory list in `arknet-mcp` whenever one lands.**
  Found twice on 2026-08-06: `bc_link_context`'s `arkddd:upstream`/`downstream` edges (landed
  2026-08-01, the same day the last review snapshot was taken) never entered
  `TraceabilityGraph.DEPENDENT_EDGE_PREDICATES`, making a recorded ContextRelationship invisible
  to `impact_analysis`; the `arkreq:AcceptanceCriterion` resources from #266 never entered
  `HtmlReportRenderer`'s leftover-suppression (which only knows `arkreq:Step`), so every AC
  renders twice. Constraint (#223) got both integrations right — proving the integration step is
  known but not enforced. The hard lists to check: `DEPENDENT_EDGE_PREDICATES` + type inventories
  in `TraceabilityGraph`, the leftover/suppression sets in `HtmlReportRenderer`, and the
  `@McpTool` descriptions enumerating edges/types (`impact_analysis` already under-promises
  `constrainedBy`).

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
| 2026-08-01 | `arknet-use-cases` (all 3 submodules, 38 files, ~5.9k LOC incl. tests; 3 parallel subagents split by submodule: core, adapter-kogniordf, adapter-mcp) | 5 findings, 0 critical, 1 medium (cross-BC `DisplayLocale`/language-tag bypass in `ActorLookup`, dormant until store-first multi-language actor labels appear), 4 low (two `@throws`-doc gaps, one missing-null-check + broken `{@link}` on `RevisionToken`, one unguarded aggregate parameter, one undocumented list ordering) | Confirms this BC is genuinely as solid as its own dense `CLAUDE.md` claims — every one of the module's own documented design decisions (`compareAndUpdate`-only, no unconditional `update`, `CodeAssignment` retry ordering vs. reference resolution, `StepTextPatch`'s past #96 regression not reintroduced, `WriteFunnel`/SHACL-gate usage, real-`NativeStore` concurrency test, blank-node guards, exception-message-swallowing pattern, MCP tool-bean statelessness) was independently re-verified against the code and held up. The one real (if narrow/dormant) functional finding was a genuinely new instance of the already-known `DisplayLocale`-bypass trap, found in a place (a cross-BC lookup out-adapter) the trap's prior wording didn't cover yet — see the extended trap entry above. `mvn test` green, `mvn javadoc:javadoc` reproduces the `RevisionToken` `{@link}` defect live. Issues filed: [#156](https://github.com/kogn-io/arknet/issues/156), [#159](https://github.com/kogn-io/arknet/issues/159), [#162](https://github.com/kogn-io/arknet/issues/162), [#165](https://github.com/kogn-io/arknet/issues/165), [#169](https://github.com/kogn-io/arknet/issues/169). |
| 2026-08-01 | `arknet-adr` (full module, 27 files, ~5.2k LOC incl. tests; core read directly in the main session, out-adapter and MCP in-adapter each a parallel subagent) | 5 findings, 0 critical, 1 medium (rootCause-message trap, third occurrence), 4 low (a narrow comparator-collision data-loss vector duplicated in two modules; a tool-description completeness gap; a test-fake sort-order divergence; a sibling-BC observation filed against `arknet-requirements` instead) | Sixth BC, built "CAS from the start" per its own `CLAUDE.md` — and the highest-risk documented claim (`AdrRepository#findCurrentByCode`'s "one snapshot for head+scalars, edges filled by later-only, therefore-never-staler follow-up reads") held up under direct tracing into `kognio-rdf`'s per-call-connection `SparqlQueryRdf4j.select`, backed by a regression test that names the exact prior bug this design fixed. The `replaceTriples` preservation logic, the `sh:maxCount`/`decisionDate`-WARN claims (checked directly against `architecture-shapes.ttl`, not trusted from `CLAUDE.md`), and the ADR-008 borrowed-port usage (`ResolveBoundedContexts`/`ResolveRequirements`, both verified never-throws/batched) were all correct as documented. The one real functional finding (issue #186) is the rootCause-message trap recurring a third time in a brand-new module built after the first fix (#137) — confirms it's a per-catch-site smell, not something a module fixes once and inherits everywhere; see the sharpened trap above. Issues filed: [#186](https://github.com/kogn-io/arknet/issues/186)–[#190](https://github.com/kogn-io/arknet/issues/190). |
| 2026-08-01 | `arknet-architecture-tests` (full module, 5 files, 631 LOC; single-session read — small enough) | 2 findings, both coverage gaps in the module's own job rather than a bug in what it does check: `DependencyRulesTest` never analyzes `arknet-mcp` at all (not declared as a test dependency — the composition root and its generic read paths (`StoreReader`, `TraceabilityGraph`) each carry an explicit "stays free of RDF4J" Javadoc claim with zero mechanical enforcement, in the module the profile itself weights highest), and `arknet-shared-kernel` is unguarded by the same "empty POM today is not proof for tomorrow" logic Rule 3's own Javadoc uses to justify existing for the six `*-core` modules. This module reviews itself differently from a normal component: there is no interface/implementation pair to compare, so Phase 1 became "read every ArchRule's `because()` claim and the four bidirectional vocabulary tests, then check what package tree each one's `@AnalyzeClasses`/dependency scope can *actually* reach" — Rule 1 was live-broken (temp field in `ShaclWriteGate` + temp `rdf4j-model` test dependency) and confirmed red while Rules 2–4 stayed green, matching the module's own documented verification claim; the three `*VocabularyMatchesOntologyTest` classes were checked constant-by-constant against both the Java class and the shipped `.ttl` rather than re-broken live (straightforward `Set.of(...)` equality, lower payoff for the build-cycle cost). Issues filed: [#185](https://github.com/kogn-io/arknet/issues/185), [#191](https://github.com/kogn-io/arknet/issues/191). |
| 2026-08-01 | `arknet-bounded-context` (full module, 31 files incl. tests; 4 parallel subagents: Phase 1 contract audit, Phase 2 concurrency, Phase 3+5+6 lifecycle/test-gaps/docs combined, Phase 4 abstraction neutrality) | 5 findings, 1 high (silent data loss via row-multiplication on `ownedBy`/`subdomain` — see the trap above), 4 low (RDF vocabulary leaking into `BoundedContextRepository`'s port javadoc despite its own neutrality promise, an unchanged pattern shared with `arknet-requirements`; unenforced code-uniqueness on `compareAndUpdate` if a future caller changes the code; missing `@throws` on `AddBoundedContext.add`'s retry exhaustion, same category as #133/#134; a stale Javadoc `@link` in a concurrency test) | Phase 1 and Phase 3 independently converged on the same root cause from opposite angles (read-divergence vs. write-side data loss) — a strong signal it's real, not a review artifact. Phase 2 (concurrency) was fully clean: explicit `SERIALIZABLE` on every write path, guard reads genuinely participate in conflict detection (re-confirmed the persistence-support calibration's `contains()`-vs-`SELECT` finding rather than re-deriving it), CAS/retry semantics for `linkTerm` match the module `CLAUDE.md` exactly, verified against both a decorator-based deterministic test and a real-`NativeStore` two-threads-pinned-via-barrier test. Noted but not filed: existing concurrency tests only pin one two-way interleaving each, no `@RepeatedTest`/stress with N>2 writers — a coverage limitation, not a defect. Issues filed: [#158](https://github.com/kogn-io/arknet/issues/158), [#161](https://github.com/kogn-io/arknet/issues/161), [#164](https://github.com/kogn-io/arknet/issues/164), [#166](https://github.com/kogn-io/arknet/issues/166), [#170](https://github.com/kogn-io/arknet/issues/170). |
| 2026-08-01 | `arknet-requirements` (full module, 42 files, ~2.6k LOC main + ~4.4k LOC tests; 4 parallel subagents split by phase: contract, concurrency, lifecycle, abstraction-neutrality) | 6 findings — 1 critical (a read-time placeholder becomes permanent write-time data), 2 high (unguarded status/type-cast resolution crashes the whole `findAll` batch on a single store-first-legal value), 2 medium (untested `compareAndUpdate` CAS under real concurrency; a repo-wide `@throws` doc gap filed separately, not duplicated per BC), 1 low (an informational torn-read window in a multi-query read path) | Both high-severity findings are the same failure shape the module already handles correctly elsewhere in the same file (the `type` filter, the acceptance-criteria placeholder) but didn't generalize to `status`/`priority`/`motivatedBy`/`qualityCategory` — see the two new traps above. Phase 4 (abstraction neutrality) was clean: zero RDF4J/kognio-rdf imports found in the port interfaces themselves, `RevisionToken` is a demonstrably neutral value type. The critical finding required an empirical throwaway test against the real store to confirm (write materialization) — static reading made it look safe. One subagent run (Phase 3) failed on a mid-response API error and needed a clean retry with the identical prompt. Issues filed: [#157](https://github.com/kogn-io/arknet/issues/157), [#160](https://github.com/kogn-io/arknet/issues/160), [#163](https://github.com/kogn-io/arknet/issues/163), [#168](https://github.com/kogn-io/arknet/issues/168), [#171](https://github.com/kogn-io/arknet/issues/171), [#172](https://github.com/kogn-io/arknet/issues/172). |
| 2026-08-06 | `arknet-mcp` (second full review, 77 files, ~14k LOC incl. tests; same 4-slice subagent split as 2026-08-01: dataset+store, report, trace+mention, composition root) | 20 findings after cross-slice dedup: 1 high (`bc_link_context`'s `arkddd:upstream`/`downstream` edges never entered `TraceabilityGraph.DEPENDENT_EDGE_PREDICATES`, so a recorded ContextRelationship is invisible to `impact_analysis` — the feature landed the same day the last review's snapshot was taken), 6 medium (shutdown "still open" warning fires for every persisted dataset on every shutdown because `DatasetLifecycle.list()` means "known", not "open" — found independently by two slices, empirically confirmed; Host allowlist hardcodes `:47331` while `arknet.mcp.port` makes the port configurable → any port override 421s every request; report-header project description is the one label path that bypasses the project default language (selected in `KognioRdfProjectRegistry` with the process-wide locale); `arkreq:AcceptanceCriterion` resources (#266) render twice — as requirement bullets AND as raw "Other resources" cards, since leftover suppression only knows `arkreq:Step`; `term_cooccurrence` sorts ambiguous-mention tie-breaks by IRI while its sibling pass documents and uses business-code order; `StoreReader`'s class javadoc still claims four WriteFunnel bypasses that were all closed), rest low/doc | The follow-the-new-features heuristic paid off: every functional finding sits in code added or touched after the 2026-08-01 review (bc_link_context, #266 AC resources, language switch 1033bd89, Host allowlist eaeb96ab, shutdown guards d69151c8) — the previously audited core held. New trap confirmed twice: **a feature that lands in a BC module needs its cross-cutting read paths (trace/report/store inventory lists) extended in the same PR** — ContextRelationship missed all of them, AcceptanceCriterion missed the suppression list, while Constraint (#223) got both right. Biggest test gap: the transport security validator is completely unpinned — removing `.securityValidator(...)` would silently fall back to NOOP with all tests green, while the sibling `contextExtractor` has exactly the reflection-pinning regression test the validator never got. |
| 2026-08-01 | `arknet-project` (all 3 submodules `-core`/`-adapter-kogniordf`/`-adapter-mcp`, 40 files, ~4.8k LOC incl. tests; 4 parallel subagents split by module + a dedicated concurrency/test-gap pass across all three) | 8 findings, 2 real functional holes (both concurrency/retry-safety, both concrete not theoretical), 1 doc `@throws` gap, 4 test-coverage gaps, 1 cross-cutting doc-precision issue spanning 3 BCs | First full review of the identity-resolution BC — structurally the least like the other five (not project-scoped, no business code, double-write-with-no-2PC by design). Both real holes sit exactly where the module's own `CLAUDE.md` predicted the risk ("no two-phase commit … drift self-heals" and "the adopt/register asymmetry") but had not been mechanically verified — see the two new traps above. The 2026-08-01 `register()`-path concurrency test (`ProjectRegistryRealStoreConcurrencyTest`, real `NativeStore`, barrier/latch-forced interleaving, no `@RepeatedTest` needed because the race is deterministically constructed) is a strong template other BCs' `compareAndUpdate`-path tests should be checked against — `arknet-project` itself is missing the `compareAndUpdate`-side counterpart (issue #178). Two agents needed a mid-run restart after a transient API connection error; both resumed cleanly with no loss of audit quality. Issues filed: [#173](https://github.com/kogn-io/arknet/issues/173)–[#176](https://github.com/kogn-io/arknet/issues/176), [#178](https://github.com/kogn-io/arknet/issues/178)–[#180](https://github.com/kogn-io/arknet/issues/180), [#182](https://github.com/kogn-io/arknet/issues/182). |
