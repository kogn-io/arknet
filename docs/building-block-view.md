# arknet -- Building-block view (transitional)

This document describes arknet's own structure in the vocabulary of its module
schema: Bounded Context > Component > Maven module. It is transitional: arknet's
metamodel does not yet hold this view itself (kogn-io/arknet#77, milestone 0.11.0).
Once it does, this file goes away.

Decisions do **not** live here; they are records in the arknet store (`adr_get ADR-n`,
rendered in `docs/adr-export/`), and so is their status. This file states the shape
as built and the target shape, and points at the record each part rests on. It repeats
nothing the store already holds (glossary, records, context map).

## 1. Terms (glossary in the store)

- **Bounded Context** (TERM-19): a boundary within which a domain model and its terms
  hold uniformly. In the build a Maven parent and nothing else: no hexagon, no core
  (ADR-48).
- **Ubiquitous Language** (TERM-25): the one language of a Bounded Context.
- **Component** (TERM-26): one or more form a Bounded Context and speak its
  Ubiquitous Language; each has a responsibility of its own behind an interface. A
  Component is a hexagon, built as the modules `core | adapter-<tech>` (ADR-48). An
  `api` module is an extension stage, built only once a module outside the Component
  calls an in-port at compile time; arknet has no such caller (ADR-58).
- **Application**: the only deployable unit, outside every Bounded Context, holding the
  composition root (ADR-55).
- **Vocabulary** `<bc>-shared`: typed identities and ownerless value objects of one
  Bounded Context, no behaviour (ADR-57).
- **Shared Kernel**: the model terms every context carries -- project identity,
  resource identity, business-code assignment, language (ADR-56).

## 2. Context view

The Bounded Contexts, their context map and their glossary links live in the store:
`bc_list` shows BC-1 to BC-6 with name, domain vision, subdomain and linked terms;
`resource_get BC-n` shows a context's relationships. This section keeps only what the
store does not hold: the resource types each context owns, the record it rests on, and
where the recorded relationships differ from the code.

| Code | Bounded Context              | holds                                                         | Record |
|------|------------------------------|---------------------------------------------------------------|--------|
| BC-1 | Product & Requirements       | Requirement, Constraint, AcceptanceCriterion, UseCase, Steps  | ADR-52 (succeeds ADR-10) |
| BC-2 | Domain Modelling             | Term (SKOS), BoundedContext, ContextRelationship              | ADR-52 (succeeds ADR-10) |
| BC-3 | Architecture & Decisions     | ArchitectureDecisionRecord, Consequence, ConsideredOption     | ADR-52 (succeeds ADR-10) |
| BC-4 | Actor                        | Actor, Role                                                   | ADR-36, ADR-37 |
| BC-5 | Project Registry             | Project, Anchor, language commitment -- supporting context outside the eight lifecycle contexts, upstream of every other | ADR-53 (succeeds ADR-13) |
| BC-6 | Model Analysis               | no resource of its own; reads every other context: impact analysis, trace matrix, orphans, role/use-case matrix, term co-occurrence, `store_check`, the HTML report | ADR-54 |

Three core contexts (BC-1..3) are the realised part of the eight-context lifecycle
target cut (ADR-46); three supporting contexts (BC-4..6) sit outside it (ADR-52).

Context map as recorded in the store, against the code:

- `PUBLISHED_LANGUAGE`, nine relationships: Domain Modelling and Actor upstream of
  Product & Requirements (term codes, role codes); Product & Requirements and Domain
  Modelling upstream of Architecture & Decisions; every other context upstream of
  Model Analysis. The four edges among the core contexts and Actor are as built: the
  out-adapter reads the neighbour's named graph (the ontology as schema). The five
  into Model Analysis describe the target (ADR-54), not the build: those evaluations
  still live in the composition root, and `store_check` takes the maintained
  languages from `ResolvedProject`, not from the registry's graph.
- `CONFORMIST`, four relationships: the project registry upstream of the four model
  contexts. As built, no context reads the registry's graph. The contexts take
  `ProjectId` and the language commitments as they are, through the Shared Kernel
  (`ResolvedProject`), and the composition root fills that value by calling the
  registry's `ResolveProject` in-port. Whether this becomes a Published Language
  edge once the resolver has moved (#561) is open.
- Not recorded: Domain Modelling (bounded-context) reads Domain Modelling
  (ubiquitous-language), context-internal; and the Shared Kernel of every context
  (ADR-56). The store records a shared kernel only pairwise, so that statement waits
  for #77.
- Read side as built: the in-adapter borrows the neighbour's read in-port (a
  "Borrowed In-Port"). The target has no such edge (ADR-49), see section 4.

## 3. Building blocks

As built: seven Components, three modules each (`core`, `adapter-kogniordf`,
`adapter-mcp`), no `api` module, no `<bc>-shared`, no parent per Bounded Context --
the Component is the Maven parent.

Target: the Components stay as they are and the Bounded Contexts become Maven parents
above them; no core is merged (ADR-48). The two contexts with two Components get a
`<bc>-shared` (typed codes, the context's one `TermRef`); Actor, Architecture &
Decisions and Model Analysis hold one Component each and need none (ADR-57). No `api`
module (ADR-58). Model Analysis becomes an eighth Component (ADR-54). Implementation:
#439, #441, #560, #561.

| Target Bounded Context       | Component (today = Maven parent) | Modules                                   |
|------------------------------|----------------------------------|-------------------------------------------|
| Product & Requirements       | arknet-requirements              | -core, -adapter-kogniordf, -adapter-mcp   |
| Product & Requirements       | arknet-use-cases                 | -core, -adapter-kogniordf, -adapter-mcp   |
| Domain Modelling             | arknet-ubiquitous-language       | -core, -adapter-kogniordf, -adapter-mcp   |
| Domain Modelling             | arknet-bounded-context           | -core, -adapter-kogniordf, -adapter-mcp   |
| Architecture & Decisions     | arknet-adr                       | -core, -adapter-kogniordf, -adapter-mcp   |
| Actor                        | arknet-actor                     | -core, -adapter-kogniordf, -adapter-mcp   |
| Project Registry             | arknet-project                   | -core, -adapter-kogniordf, -adapter-mcp   |
| Model Analysis               | arknet-model-analysis (target)   | -core, -adapter-kogniordf, -adapter-mcp, -adapter-html |

Outside every Bounded Context:

| Role in the schema                 | Module                          | Note |
|------------------------------------|---------------------------------|------|
| Application (composition root)     | arknet-mcp                      | Spring Boot daemon; additionally carries the generic store read path, the type-independent exception (ADR-55: `store_overview`, `resource_get`). The five cross-context evaluations, `store_check` and the HTML report still live here; their target is Model Analysis (ADR-54, #560) |
| Shared Kernel                      | arknet-shared-kernel            | ADR-56: keeps ProjectId, ResourceId + factory, CodeCounter/CodeAssignment, LanguageTag, DisplayLocale. As built it also carries LocalizedLiteral (target: persistence-support) and the anchor/translation mechanics ProjectResolver, StaleTranslationHint, FieldLanguageLookup (target: a support module of the tool adapters, #561) |
| Technical library                  | arknet-persistence-support      | SHACL gate, WriteFunnel, vocabulary constants -- no model term, hence neither Shared Kernel nor vocabulary |
| Technical library (test scope)     | arknet-persistence-test-support | Guarded* decorators |
| Published Language (schema)        | arknet-ontology                 | .ttl ontologies and shapes |
| Checker                            | arknet-architecture-tests       | ArchUnit rules and ontology cross-checks |

## 4. Dependencies

As built (from the POMs):

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

Target (ADR-49): one mechanism for every edge, inside and across context boundaries.
The core defines its own out-port for whatever it needs from a neighbour; the
`adapter-kogniordf` serves it through the neighbour's Published Language in both
directions (code to identity when writing, identity to code when reading), and
in-port results carry foreign codes themselves. Every `-adapter-mcp` then depends on
its own core only; no module of a Component depends on a module of another.

## 5. Check against the schema's mandatory parts

| Mandatory per schema                          | arknet as built                                     |
|-----------------------------------------------|-----------------------------------------------------|
| Bounded Context as Maven parent               | missing; today the parent is the Component (#439, #441, #560) |
| `api` per Component                           | not required: extension stage, no foreign in-port caller in arknet (ADR-58) |
| `core` per Component, framework-free          | present                                              |
| one adapter per technology per Component      | present (kogniordf, mcp)                             |
| `<bc>-shared` once ownerless identities exist | missing; one each for Product & Requirements and Domain Modelling (ADR-57; #439, #441) |
| Application outside every Bounded Context     | present (arknet-mcp)                                 |
| dependency rules as a checker                 | partial: the ArchUnit rules do not yet carry the schema's rule that no module of a Component depends on a module of another (ADR-49; Part B) |
| extension stages (starter, bom, adapter-events) | no trigger met -- correctly absent                 |

## 6. Cross-cutting concepts (pointers only)

- Write funnel, revision, concurrency token: ADR-3, ADR-28, ADR-32
- Persistence only through domain-near out-ports, records without graph access: ADR-8, ADR-45
- Project identity: anchor, one dataset per project, system dataset: ADR-20, ADR-25, ADR-27
- Operation: one shared daemon, Spring AI: ADR-16, ADR-14
- Composition root without application logic: ADR-55 (succeeds ADR-51)
- Namespaces follow context boundaries: ADR-12
- Foreign concepts through an anti-corruption layer: ADR-19
- Single user, no tenancy in the cores: ADR-6

## 7. What moves this file

- Part B of the context cut (#439, #441, #560, #561): Maven parents, `<bc>-shared`,
  Model Analysis as a Component, the shared kernel slimmed, ArchUnit, module map in
  `CLAUDE.md`. Sections 3 to 5 then describe one shape instead of two.
- #77: the building-block view moves into the store; this file goes away. Before
  that, a successor of ADR-46 (components move from "outside" to "not yet built"),
  see the comment on #77.
