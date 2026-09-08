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
  Construction rule (to be recorded in the successor of ADR-48): a Component is a
  hexagon, built as the modules `api | core | adapter-<tech>`.
- **Application**: the only deployable unit, outside every Bounded Context, holding the
  composition root.
- **Vocabulary** `<bc>-shared`: identities and ownerless value objects of one Bounded
  Context, no behaviour.

## 2. Context view

Target Bounded Contexts per ADR-10, ADR-36 and ADR-46 (all ACCEPTED):

| Bounded Context (target)     | holds                                                         | Record |
|------------------------------|---------------------------------------------------------------|--------|
| Product & Requirements       | Requirement, Constraint, AcceptanceCriterion, UseCase, Steps  | ADR-10 |
| Domain Modelling             | Term (SKOS), BoundedContext, ContextRelationship              | ADR-10 |
| Architecture & Decisions     | ArchitectureDecisionRecord, Consequence, ConsideredOption     | ADR-10 |
| Actor                        | Actor, Role                                                   | ADR-36, ADR-37 |
| (Project registry)           | Project, Anchor -- "outside the model contexts"               | ADR-13 |

Open: ADR-13 calls the registry "a hexagon outside the model contexts". In the current
vocabulary every Component belongs to a Bounded Context; whether the registry is a
tooling context of its own, and whether it appears in the context map held by the
store, is for the successor of ADR-13 (#438).

Context map (as built today, read off the code, not yet in the store -- #438):

- Product & Requirements reads Domain Modelling (term codes) and Actor (role codes).
- Architecture & Decisions reads Product & Requirements and Domain Modelling.
- Domain Modelling (bounded-context) reads Domain Modelling (ubiquitous-language) --
  context-internal per ADR-10.
- Actor and the project registry read nobody.
- Relationship kind: on the write side the out-adapter reads the neighbour's named
  graph (= Published Language, the ontology as schema); on the read side the in-adapter
  borrows the neighbour's read in-port (Borrowed In-Port, TERM-22 -- whether that
  pattern survives is for the successor of ADR-49).

## 3. Building blocks as built

Seven Components, three modules each (`core`, `adapter-kogniordf`, `adapter-mcp`),
no `api` module, no `<bc>-shared`. Target context per section 2.

| Target Bounded Context       | Component (today = Maven parent) | Modules                                   |
|------------------------------|----------------------------------|-------------------------------------------|
| Product & Requirements       | arknet-requirements              | -core, -adapter-kogniordf, -adapter-mcp   |
| Product & Requirements       | arknet-use-cases                 | -core, -adapter-kogniordf, -adapter-mcp   |
| Domain Modelling             | arknet-ubiquitous-language       | -core, -adapter-kogniordf, -adapter-mcp   |
| Domain Modelling             | arknet-bounded-context           | -core, -adapter-kogniordf, -adapter-mcp   |
| Architecture & Decisions     | arknet-adr                       | -core, -adapter-kogniordf, -adapter-mcp   |
| Actor                        | arknet-actor                     | -core, -adapter-kogniordf, -adapter-mcp   |
| (Project registry)           | arknet-project                   | -core, -adapter-kogniordf, -adapter-mcp   |

Outside every Bounded Context:

| Role in the schema                 | Module                          | Note |
|------------------------------------|---------------------------------|------|
| Application (composition root)     | arknet-mcp                      | Spring Boot daemon; additionally carries the generic store read path (ADR-51, tolerated exception) and five cross-context evaluations (violation, location open: #554) |
| Shared Kernel candidate            | arknet-shared-kernel            | ProjectId, ProjectResolver, ResourceId, DisplayLocale -- to be tested against the Shared Kernel definition (small, delimited, decided?) or a technical library without model terms |
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

The schema's dependency rules forbid an adapter depending on a foreign core. The
Borrowed In-Port therefore survives the target schema only through the neighbour's
`api` module, never its core.

## 5. Check against the schema's mandatory parts

| Mandatory per schema                          | arknet as built                                     |
|-----------------------------------------------|-----------------------------------------------------|
| Bounded Context as Maven parent               | missing; today the parent is the Component           |
| `api` per Component                           | missing everywhere                                   |
| `core` per Component, framework-free          | present                                              |
| one adapter per technology per Component      | present (kogniordf, mcp)                             |
| `<bc>-shared` once ownerless identities exist | missing; shared-kernel carries parts of it for all contexts |
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

1. #554 -- where the five cross-context evaluations live outside the composition root.
2. ADR-48 and ADR-49 (PROPOSED) to be withdrawn and rewritten: Components per Bounded
   Context, neighbour access (own out-port plus adapter, or Published Language; fate of
   the Borrowed In-Port), `api` modules, `<bc>-shared`, shared-kernel. Closes #352. The
   table in section 3 then becomes the target table.
3. ADR-10 and ADR-13 through successor records (the undefined "model context" goes).
4. #438 -- Bounded Contexts and context map into the store; section 2 moves there.
5. Part B (#439/#441): Maven, ArchUnit, module map in `CLAUDE.md`.
6. #77 -- building-block view into the store; this file goes away. Before that, a
   successor of ADR-46 (components move from "outside" to "not yet built"), see the
   comment on #77.
