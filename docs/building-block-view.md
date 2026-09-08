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
  Construction rule (decided on #444, 2026-09-08; record pending): a Component is a
  hexagon, built as the modules `core | adapter-<tech>`. An `api` module is an extension
  stage, built only once a module outside the Component calls an in-port at compile
  time; arknet has no such caller.
- **Application**: the only deployable unit, outside every Bounded Context, holding the
  composition root.
- **Vocabulary** `<bc>-shared`: identities and ownerless value objects of one Bounded
  Context, no behaviour.

## 2. Context view

Target Bounded Contexts per ADR-10, ADR-36 and ADR-46 (all ACCEPTED) plus the two
decided on #554 and #444 (records pending):

| Bounded Context (target)     | holds                                                         | Record |
|------------------------------|---------------------------------------------------------------|--------|
| Product & Requirements       | Requirement, Constraint, AcceptanceCriterion, UseCase, Steps  | ADR-10 |
| Domain Modelling             | Term (SKOS), BoundedContext, ContextRelationship              | ADR-10 |
| Architecture & Decisions     | ArchitectureDecisionRecord, Consequence, ConsideredOption     | ADR-10 |
| Actor                        | Actor, Role                                                   | ADR-36, ADR-37 |
| Project registry             | Project, Anchor, language commitment -- supporting context outside the eight lifecycle contexts, like Actor | ADR-13 (successor pending; decided on #444, 2026-09-08) |
| Model Analysis               | read-only: impact analysis, trace matrix, orphans, role/use-case matrix, term co-occurrence, `store_check`, the HTML report -- reads the Published Language of every model context | #554 (decided 2026-09-08, record pending) |

Decided on #444 (2026-09-08): the project registry is a Bounded Context of its own --
a supporting context outside the eight lifecycle contexts, the same position Actor
holds, upstream of every model context and visible on the context map. Grounds: the
schema knows no Component without a context; `ProjectId` sits in the Shared Kernel as
a model term and needs an owning context; Project, Anchor and the language commitment
are a language no model context holds. Tenant and user identity are not part of it
(a different language, a decision of its own once multi-tenancy becomes concrete;
ADR-6). The undefined "model context" goes with the successor of ADR-13.

Context map (as built today, read off the code, not yet in the store -- #438):

- Product & Requirements reads Domain Modelling (term codes) and Actor (role codes).
- Architecture & Decisions reads Product & Requirements and Domain Modelling.
- Domain Modelling (bounded-context) reads Domain Modelling (ubiquitous-language) --
  context-internal per ADR-10.
- Actor reads nobody.
- The project registry reads nobody; the tool adapter of every context resolves its
  project there (upstream of every context).
- Model Analysis (target, #554) reads every model context through its Published
  Language; nobody reads Model Analysis.
- `arknet-shared-kernel` is the Shared Kernel of every context (decided on #444):
  project identity, resource identity, business-code assignment, language.
- Relationship kind: on the write side the out-adapter reads the neighbour's named
  graph (= Published Language, the ontology as schema); on the read side the in-adapter
  borrows the neighbour's read in-port (Borrowed In-Port, TERM-22 -- whether that
  pattern survives is for the successor of ADR-49).

## 3. Building blocks as built

Seven Components, three modules each (`core`, `adapter-kogniordf`, `adapter-mcp`),
no `api` module, no `<bc>-shared`. Target context per section 2.

Target (decided on #444, 2026-09-08, five sub-decisions; records pending): the
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
| Application (composition root)     | arknet-mcp                      | Spring Boot daemon; additionally carries the generic store read path (ADR-51, tolerated exception); the five cross-context evaluations, `store_check` and the HTML report still live here and move to Model Analysis (#554) |
| Shared Kernel                      | arknet-shared-kernel            | decided on #444: keeps ProjectId, ResourceId + factory, CodeCounter/CodeAssignment, LanguageTag, DisplayLocale (model terms every core carries); LocalizedLiteral moves to persistence-support, the anchor/translation mechanics (ProjectResolver, StaleTranslationHint, FieldLanguageLookup) into a support module of the tool adapters (#561) |
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

The schema's dependency rules forbid an adapter depending on a foreign core. Decided
on #444 (2026-09-08): the Borrowed In-Port is abolished. The `*Lookup` out-ports get the
reverse direction (identity to code), served by `adapter-kogniordf` through the Published
Language, and in-port results carry foreign codes themselves; every `-adapter-mcp` then
depends on its own core only. TERM-22 goes with it (#439, #441).

## 5. Check against the schema's mandatory parts

| Mandatory per schema                          | arknet as built                                     |
|-----------------------------------------------|-----------------------------------------------------|
| Bounded Context as Maven parent               | missing; today the parent is the Component (#439, #441, #560) |
| `api` per Component                           | not required: extension stage, no foreign in-port caller in arknet (decided on #444) |
| `core` per Component, framework-free          | present                                              |
| one adapter per technology per Component      | present (kogniordf, mcp)                             |
| `<bc>-shared` once ownerless identities exist | missing; decided: one each for Product & Requirements and Domain Modelling (#439, #441) |
| Application outside every Bounded Context     | present (arknet-mcp)                                 |
| dependency rules as a checker                 | partial (the ArchUnit rules do not carry "no core depends on a neighbouring core", per ADR-48's context) |
| extension stages (starter, bom, adapter-events) | no trigger met -- correctly absent                 |

## 6. Cross-cutting concepts (pointers only)

- Write funnel, revision, concurrency token: ADR-3, ADR-28, ADR-32
- Persistence only through domain-near out-ports, records without graph access: ADR-8, ADR-45
- Project identity: anchor, one dataset per project, system dataset: ADR-20, ADR-25, ADR-27
- Operation: one shared daemon, Spring AI: ADR-16, ADR-14
- Composition root without application logic: ADR-51
- Namespaces follow context boundaries: ADR-12
- Foreign concepts through an anti-corruption layer: ADR-19
- Single user, no tenancy in the cores: ADR-6

## 7. Open decisions (in order)

1. #554 -- decided: the cross-context evaluations, `store_check` and the report become
   the Component `arknet-model-analysis` of a read-only Bounded Context Model Analysis,
   reading the Published Language (no Borrowed In-Ports). The move itself is Part B.
2. Decided (#444, 2026-09-08): Components stay, contexts become parents; Borrowed
   In-Port abolished; no `api`; two `<bc>-shared`; shared-kernel confirmed and slimmed.
   ADR-48 and ADR-49 are replaced by records of this shape in the consolidation pass
   after Part A. Closes #352.
3. ADR-10 and ADR-13 through successor records in the consolidation pass. Decided
   (#444, 2026-09-08): the project registry is a Bounded Context of its own; the
   undefined "model context" goes. Left for the ADR-10 successor: the full context
   list placed against ADR-46, the gateway wording and TERM-22 dropped, context =
   Maven parent and Component = hexagon.
4. #438 -- Bounded Contexts and context map into the store; section 2 moves there.
5. Part B (#439, #441, #560, #561): Maven, ArchUnit, module map in `CLAUDE.md`.
6. #77 -- building-block view into the store; this file goes away. Before that, a
   successor of ADR-46 (components move from "outside" to "not yet built"), see the
   comment on #77.
