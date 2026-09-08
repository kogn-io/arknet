# arknet -- Building-block view (transitional)

This document describes arknet's own structure in the vocabulary of its module
schema: Bounded Context > Component > Maven module. It is transitional: arknet's
metamodel does not yet hold this view itself (kogn-io/arknet#77, milestone 0.11.0).
Once it does, this file goes away. Decisions do **not** live here; they are records
in the arknet store (`adr_get ADR-n`, rendered in `docs/adr-export/`). This file only
points at them, and it repeats nothing the store already holds (glossary, records).

## 1. Terms (glossary in the store)

- **Bounded Context** (TERM-19): a boundary within which a domain model and its terms
  hold uniformly. In the build a Maven parent and nothing else: no hexagon, no core.
- **Ubiquitous Language** (TERM-25): the one language of a Bounded Context.
- **Component** (TERM-26): one or more form a Bounded Context and speak its
  Ubiquitous Language; each has a responsibility of its own behind an interface.
  Construction rule (ADR-52 and ADR-48, both proposed): a Component is a
  hexagon, built as the modules `core | adapter-<tech>`. An `api` module is an extension
  stage, built only once a module outside the Component calls an in-port at compile
  time; arknet has no such caller.
- **Application**: the only deployable unit, outside every Bounded Context, holding the
  composition root.
- **Vocabulary** `<bc>-shared`: identities and ownerless value objects of one Bounded
  Context, no behaviour.

## 2. Context view

The Bounded Contexts, their context map and their glossary links live in the store
(#438): `bc_list` shows BC-1 to BC-6 with name, domain vision, subdomain and linked
terms; `resource_get BC-n` shows a context's relationships (there is no relationship
listing yet). This section keeps only what the store does not hold: the resource
types each context owns, the record it rests on, and how the recorded relationships
compare to the code.

Target Bounded Contexts per ADR-52 (proposed successor of ADR-10; lists all six),
ADR-36 and ADR-46 (both ACCEPTED):

| Code | Bounded Context (target)     | holds                                                         | Record |
|------|------------------------------|---------------------------------------------------------------|--------|
| BC-1 | Product & Requirements       | Requirement, Constraint, AcceptanceCriterion, UseCase, Steps  | ADR-10; successor ADR-52 (proposed) |
| BC-2 | Domain Modelling             | Term (SKOS), BoundedContext, ContextRelationship              | ADR-10; successor ADR-52 (proposed) |
| BC-3 | Architecture & Decisions     | ArchitectureDecisionRecord, Consequence, ConsideredOption     | ADR-10; successor ADR-52 (proposed) |
| BC-4 | Actor                        | Actor, Role                                                   | ADR-36, ADR-37 |
| BC-5 | Project registry             | Project, Anchor, language commitment -- supporting context outside the eight lifecycle contexts, like Actor | ADR-13; successor ADR-53 (proposed) |
| BC-6 | Model Analysis               | read-only: impact analysis, trace matrix, orphans, role/use-case matrix, term co-occurrence, `store_check`, the HTML report -- reads the Published Language of every model context | ADR-54 (proposed) |

Decided on #444 (2026-09-08): the project registry is a Bounded Context of its own --
a supporting context outside the eight lifecycle contexts, the same position Actor
holds, upstream of every model context and visible on the context map. Grounds: the
schema knows no Component without a context; `ProjectId` sits in the Shared Kernel as
a model term and needs an owning context; Project, Anchor and the language commitment
are a language no model context holds. Tenant and user identity are not part of it
(a different language, a decision of its own once multi-tenancy becomes concrete;
ADR-6). Recorded as ADR-53 (proposed); the undefined "model context" goes with it.

Context map as recorded in the store (#438, 2026-09-08), against the code:

- `PUBLISHED_LANGUAGE`, nine relationships: Domain Modelling and Actor upstream of
  Product & Requirements (term codes, role codes); Product & Requirements and Domain
  Modelling upstream of Architecture & Decisions; every other context upstream of
  Model Analysis. The four edges among the model contexts are as built: the
  out-adapter reads the neighbour's named graph (the ontology as schema). The five
  into Model Analysis are the target of #554, not the build: today those evaluations
  live in the composition root, and `store_check` takes the maintained languages from
  `ResolvedProject`, not from the registry's graph.
- `CONFORMIST`, four relationships: the project registry upstream of the four model
  contexts. As built, no context reads the registry's graph. The contexts take
  `ProjectId` and the language commitments as they are, through the Shared Kernel
  (`ResolvedProject`), and the composition root fills that value by calling the
  registry's `ResolveProject` in-port. Whether this becomes a Published Language
  edge once Part B has moved the resolver is for the consolidation pass after Part A.
- Not recorded: Domain Modelling (bounded-context) reads Domain Modelling
  (ubiquitous-language), context-internal per ADR-10; and `arknet-shared-kernel` as
  the Shared Kernel of every context (decided on #444: project identity, resource
  identity, business-code assignment, language). The store records a shared kernel
  only pairwise, so that statement waits for #77.
- Relationship kind as built, read side: the in-adapter borrows the neighbour's read
  in-port (Borrowed In-Port, TERM-22). Abolished by ADR-49 (proposed), removed in
  Part B, see section 4.

## 3. Building blocks as built

Seven Components, three modules each (`core`, `adapter-kogniordf`, `adapter-mcp`),
no `api` module, no `<bc>-shared`. Target context per section 2.

Target (decided on #444, 2026-09-08; recorded as ADR-48 and ADR-56, both proposed): the
Components stay as they are and the Bounded Contexts become Maven parents above them
(`arknet-product-requirements`, `arknet-domain-modelling`); no core is merged. The two
merged contexts get a `<bc>-shared` (typed codes, the context's one `TermRef`); Actor,
Architecture & Decisions and Model Analysis hold one Component each and need none.
No `api` module. Model Analysis becomes an eighth Component (#560). Implementation:
#439, #441, #560, #561.

| Target Bounded Context       | Component (today = Maven parent) | Modules                                   |
|------------------------------|----------------------------------|-------------------------------------------|
| Product & Requirements       | arknet-requirements              | -core, -adapter-kogniordf, -adapter-mcp   |
| Product & Requirements       | arknet-use-cases                 | -core, -adapter-kogniordf, -adapter-mcp   |
| Domain Modelling             | arknet-ubiquitous-language       | -core, -adapter-kogniordf, -adapter-mcp   |
| Domain Modelling             | arknet-bounded-context           | -core, -adapter-kogniordf, -adapter-mcp   |
| Architecture & Decisions     | arknet-adr                       | -core, -adapter-kogniordf, -adapter-mcp   |
| Actor                        | arknet-actor                     | -core, -adapter-kogniordf, -adapter-mcp   |
| Project registry             | arknet-project                   | -core, -adapter-kogniordf, -adapter-mcp   |

Outside every Bounded Context:

| Role in the schema                 | Module                          | Note |
|------------------------------------|---------------------------------|------|
| Application (composition root)     | arknet-mcp                      | Spring Boot daemon; additionally carries the generic store read path (ADR-51, tolerated exception; ADR-55, proposed successor, narrows it to `store_overview`/`resource_get`); the five cross-context evaluations, `store_check` and the HTML report still live here and move to Model Analysis (ADR-54, #560) |
| Shared Kernel                      | arknet-shared-kernel            | ADR-56 (proposed): keeps ProjectId, ResourceId + factory, CodeCounter/CodeAssignment, LanguageTag, DisplayLocale (model terms every core carries); LocalizedLiteral moves to persistence-support, the anchor/translation mechanics (ProjectResolver, StaleTranslationHint, FieldLanguageLookup) into a support module of the tool adapters (#561) |
| Technical library                  | arknet-persistence-support      | SHACL gate, WriteFunnel, vocabulary constants -- no model term, hence neither Shared Kernel nor vocabulary |
| Technical library (test scope)     | arknet-persistence-test-support | Guarded* decorators |
| Published Language (schema)        | arknet-ontology                 | .ttl ontologies and shapes |
| Checker                            | arknet-architecture-tests       | ArchUnit rules and ontology cross-checks |

## 4. Dependencies as built (from the POMs)

- Every `-core` depends only on `arknet-shared-kernel`. No core depends on a
  neighbouring core.
- Every `-adapter-kogniordf` depends on its own core, `arknet-ontology` and
  `arknet-persistence-support`; on no neighbouring module. It resolves neighbour codes
  by SPARQL in the neighbour's graph (Published Language).
- `-adapter-mcp` depends on neighbouring cores (Borrowed In-Port):
  - requirements-mcp -> ubiquitous-language-core
  - use-cases-mcp -> requirements-core, ubiquitous-language-core, actor-core
  - bounded-context-mcp -> ubiquitous-language-core
  - adr-mcp -> requirements-core, ubiquitous-language-core, bounded-context-core
  - actor-mcp, project-mcp, ubiquitous-language-mcp: none

The schema's dependency rules forbid an adapter depending on a foreign core. ADR-49
(proposed): the Borrowed In-Port is abolished. The `*Lookup` out-ports get the
reverse direction (identity to code), served by `adapter-kogniordf` through the Published
Language, and in-port results carry foreign codes themselves; every `-adapter-mcp` then
depends on its own core only. TERM-22 goes with it (#439, #441).

## 5. Check against the schema's mandatory parts

| Mandatory per schema                          | arknet as built                                     |
|-----------------------------------------------|-----------------------------------------------------|
| Bounded Context as Maven parent               | missing; today the parent is the Component (#439, #441, #560) |
| `api` per Component                           | not required: extension stage, no foreign in-port caller in arknet (ADR-48) |
| `core` per Component, framework-free          | present                                              |
| one adapter per technology per Component      | present (kogniordf, mcp)                             |
| `<bc>-shared` once ownerless identities exist | missing; ADR-48: one each for Product & Requirements and Domain Modelling (#439, #441) |
| Application outside every Bounded Context     | present (arknet-mcp)                                 |
| dependency rules as a checker                 | partial (the ArchUnit rules do not yet carry the schema's rule that no module of a Component depends on a module of another; ADR-49, Part B) |
| extension stages (starter, bom, adapter-events) | no trigger met -- correctly absent                 |

## 6. Cross-cutting concepts (pointers only)

- Write funnel, revision, concurrency token: ADR-3, ADR-28, ADR-32
- Persistence only through domain-near out-ports, records without graph access: ADR-8, ADR-45
- Project identity: anchor, one dataset per project, system dataset: ADR-20, ADR-25, ADR-27
- Operation: one shared daemon, Spring AI: ADR-16, ADR-14
- Composition root without application logic: ADR-51; successor ADR-55 (proposed)
- Namespaces follow context boundaries: ADR-12
- Foreign concepts through an anti-corruption layer: ADR-19
- Single user, no tenancy in the cores: ADR-6

## 7. Open decisions (in order)

1. Decided on #554, recorded as ADR-54 (proposed): the cross-context evaluations,
   `store_check` and the report become the Component `arknet-model-analysis` of a
   read-only Bounded Context Model Analysis, reading the Published Language (no
   Borrowed In-Ports). The move itself is Part B (#560).
2. Decided on #444 (2026-09-08), recorded in the consolidation pass: ADR-48 rewritten
   (Components stay, contexts become parents, two `<bc>-shared`, no `api`), ADR-49
   rewritten (Borrowed In-Port abolished, Published Language in both directions),
   ADR-56 new (shared kernel confirmed and slimmed). All proposed; a review precedes
   acceptance. #352 closes with their acceptance.
3. ADR-52 (successor of ADR-10: the full context list placed against ADR-46, context =
   Maven parent and Component = hexagon, gateway wording and TERM-22 dropped) and
   ADR-53 (successor of ADR-13: the project registry is a Bounded Context of its own)
   are written, proposed. The supersession edges are recorded once both sides are
   accepted; until then ADR-10 and ADR-13 stay ACCEPTED and ADR-52/ADR-53 name them
   via `relatedTo`.
4. Done (#438, 2026-09-08): six Bounded Contexts, thirteen relationships and the
   glossary links are in the store; section 2 keeps only what the store does not hold.
5. Part B (#439, #441, #560, #561): Maven, ArchUnit, module map in `CLAUDE.md`.
6. #77 -- building-block view into the store; this file goes away. Before that, a
   successor of ADR-46 (components move from "outside" to "not yet built"), see the
   comment on #77.
