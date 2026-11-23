# arknet -- Building-block view (transitional)

This document describes arknet's own structure in the vocabulary of its module
schema: Bounded Context > Component > Maven module. It is transitional: arknet's
metamodel does not yet hold this view itself (kogn-io/arknet#77, milestone 0.12.0).
Once it does, this file goes away.

Decisions do **not** live here; they are records in the arknet store (`adr_get ADR-n`,
rendered in `docs/adr-export/`), and so is their status. This file states the shape
as built and the target shape, and points at the record each part rests on -- where a
part rests on no record but on the module schema alone, it says so. It repeats
nothing the store already holds (glossary, records, context map).

## 1. Terms (glossary in the store)

- **Bounded Context** (TERM-19): a boundary within which a domain model and its terms
  hold uniformly. In the build a Maven parent and nothing else: no hexagon, no core --
  module schema, no record behind it.
- **Ubiquitous Language** (TERM-25): the one language of a Bounded Context.
- **Component** (TERM-26): has a responsibility of its own and offers related
  functionality to the outside behind an interface; whatever only serves other
  Components and offers nothing to the outside itself is not a Component. A Component
  belongs to at most one Bounded Context -- the one whose Ubiquitous Language it speaks --
  and to none if it works the same way for every Resource without speaking the language
  of any Bounded Context (ADR-69). A Component is a hexagon, built as the modules
  `core | adapter-<tech>`. An
  `api` module is an extension stage, built only once a module outside the Component
  calls an in-port at compile time; arknet has no such caller (ADR-58).
- **Application**: the only deployable unit, outside every Bounded Context, holding the
  composition root, which wires the Components and carries no application logic (ADR-70).
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
| BC-1 | Product & Requirements       | Requirement, Constraint, AcceptanceCriterion, UseCase, Steps  | ADR-10                    |
| BC-2 | Domain Modelling             | Term (SKOS), BoundedContext, ContextRelationship              | ADR-10                    |
| BC-3 | Architecture & Decisions     | ArchitectureDecisionRecord, Consequence, ConsideredOption     | ADR-10                    |
| BC-4 | Actor                        | Actor, Role                                                   | ADR-36, ADR-37 |
| BC-5 | Project Registry             | Project, Anchor, language commitment -- supporting context outside the eight lifecycle contexts, upstream of every other | ADR-53 (succeeds ADR-13) |
| BC-6 | Model Analysis               | no resource of its own; reads every other context: impact analysis, trace matrix, orphans, role/use-case matrix, term co-occurrence, `store_check`; the HTML report is its target too and still sits in the composition root | ADR-54 |

Three core contexts (BC-1..3) are the realised part of the eight-context lifecycle
target cut (ADR-46); three supporting contexts (BC-4..6) sit outside it, each resting
on a record of its own -- ADR-10 states that its list of model contexts is not a closed
catalogue and that a later context gets its own decision.

Context map as recorded in the store, against the code:

- `PUBLISHED_LANGUAGE`, nine relationships: Domain Modelling and Actor upstream of
  Product & Requirements (term codes, role codes); Product & Requirements and Domain
  Modelling upstream of Architecture & Decisions; every other context upstream of
  Model Analysis. The four edges among the core contexts and Actor are as built: the
  out-adapter reads the neighbour's named graph (the ontology as schema). The five
  into Model Analysis are as built since #560: `KognioRdfModelSnapshots` reads one
  snapshot of every context's published language per call and the component names no
  module of any of them (`DependencyRulesTest` rule 13). `store_check` still takes the
  maintained languages from `ResolvedProject`, not from the registry's graph.
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
- Read side as built: outside Product & Requirements and Domain Modelling the
  in-adapter still borrows the neighbour's read in-port (a "Borrowed In-Port").
  Inside those two contexts that edge is gone (ADR-49), see section 4.

## 3. Building blocks

Eight Components, three modules each (`core`, `adapter-kogniordf`, `adapter-mcp`), no
`api` module (see Component, section 1). Every Bounded Context is a Maven parent
above its Component(s) -- module schema, no record behind it. The two Bounded
Contexts with more than one Component, Product &
Requirements and Domain Modelling, additionally carry a `<bc>-shared` vocabulary
module (typed codes, the context's one `TermRef`); Architecture & Decisions, Actor,
Project Registry and Model Analysis hold one Component each and need none.
Model Analysis is an eighth Component (ADR-54).

`arknet-product-requirements` is the Maven parent of its two Components
(`arknet-requirements`, `arknet-use-cases`) and of the vocabulary module
`arknet-product-requirements-shared`, which holds `RequirementCode`, `ConstraintCode`
and the context's one `TermRef`.

`arknet-domain-modelling` is the Maven parent of its two Components
(`arknet-ubiquitous-language`, `arknet-bounded-context`) and of the vocabulary module
`arknet-domain-modelling-shared`, which holds `TermCode`.

`arknet-architecture-decisions`, `arknet-actor`, `arknet-project-registry` and
`arknet-model-analysis` are the Maven parents of their one Component each --
`arknet-adr`, `arknet-actor-register`, `arknet-project` and
`arknet-model-evaluation`; none carries a vocabulary module (ADR-57).

`arknet-model-evaluation`'s core holds the traced graph and the three store checks,
its `-adapter-kogniordf` serves the two out-ports (`ModelSnapshots`,
`ResourceHandleLookup`) out of the shared store read path, and its `-adapter-mcp`
carries the five evaluation tools and `store_check`. The generic read model those
tools read (`StoreReader`, `StoreSnapshot`, `StoreResource`, `Triple`, `RdfNode`,
`Prefixes`, `HandleResolver`, `ResourceRenderer`, `VersionEntry`) lives in
`arknet-persistence-support`, alongside the vocabulary constants: it is the mechanism
by which any published language is read, and both the component and the
application's type-independent read path speak it.

`arknet-resource-access` is the Component for generic resource access and belongs to no
Bounded Context (ADR-69); its parent hangs directly off the root. It reads every resource
type at runtime from the ontology and the shapes and names no namespace of a context in its
code; its own vocabulary is `arkres:` (`ArkresVocabulary` in its core, `arknet-resource.ttl`).
Its core works on triples, so ADR-8 and ADR-45 do not bind it (ADR-69, consequence 4).

The three technical-library modules (`arknet-persistence-support`,
`arknet-mcp-support`, `arknet-persistence-test-support`) sit under the aggregator
`arknet-support`.

| Bounded Context               | Component                          | Modules                                   |
|---------------------------------|--------------------------------------|----------------------------------------------|
| Product & Requirements          | arknet-requirements                   | -core, -adapter-kogniordf, -adapter-mcp     |
| Product & Requirements          | arknet-use-cases                      | -core, -adapter-kogniordf, -adapter-mcp     |
| Product & Requirements          | (vocabulary module, no Component)     | arknet-product-requirements-shared          |
| Domain Modelling                | arknet-ubiquitous-language            | -core, -adapter-kogniordf, -adapter-mcp     |
| Domain Modelling                | arknet-bounded-context                | -core, -adapter-kogniordf, -adapter-mcp     |
| Domain Modelling                | (vocabulary module, no Component)     | arknet-domain-modelling-shared              |
| Architecture & Decisions        | arknet-adr (Maven parent: `arknet-architecture-decisions`) | -core, -adapter-kogniordf, -adapter-mcp |
| Actor                            | arknet-actor-register (Maven parent: `arknet-actor`) | -core, -adapter-kogniordf, -adapter-mcp |
| Project Registry                | arknet-project (Maven parent: `arknet-project-registry`) | -core, -adapter-kogniordf, -adapter-mcp |
| Model Analysis                   | arknet-model-evaluation (Maven parent: `arknet-model-analysis`) | -core, -adapter-kogniordf, -adapter-mcp (an -adapter-html follows with the report, #642) |
| (none, ADR-69)                   | arknet-resource-access (Maven parent: itself, directly under the root) | -core, -adapter-kogniordf, -adapter-mcp |

Outside every Bounded Context:

| Role in the schema                 | Module                          | Note |
|------------------------------------|---------------------------------|------|
| Application (composition root)     | arknet-app                      | Spring Boot daemon; carries the type-independent tools `resource_get`, `resource_history` and `text_search`, whose target is the Component for generic resource access that belongs to no Bounded Context (ADR-69, ADR-70, #743). It also carries `store_overview` and `project_export`; no target is decided for them. The HTML report lives here and composes the in-ports of every context; its target is Model Analysis (ADR-54, #642) |
| Shared Kernel                      | arknet-shared-kernel            | ADR-56: ProjectId, ResourceId + factory, CodeCounter/CodeAssignment, LanguageTag, DisplayLocale, LocalizedLiteral. `DependencyRulesTest` rule 12 lists the admitted terms, so a new type in the package turns a test red |
| Technical library                  | arknet-persistence-support      | Under the `arknet-support` aggregator. SHACL gate, the versioned write path VersionedWriteFunnel (draft, publish and tombstone over kognio-rdf rdf-versioning, one content graph per project dataset), vocabulary constants and the type-independent read path over a project's dataset (StoreReader, the triple read model, Prefixes, HandleResolver, ResourceRenderer) -- no model term, hence neither Shared Kernel nor vocabulary |
| Technical library                  | arknet-mcp-support              | Under the `arknet-support` aggregator. Support of the driving MCP adapters and the composition root: ProjectResolver/ResolvedProject, StaleTranslationHint/FieldLanguageLookup, WriteResponse, ToolParameterDescriptions. No core and no vocabulary module depends on it (rule 11) |
| Technical library (test scope)     | arknet-persistence-test-support | Under the `arknet-support` aggregator. Guarded* decorators |
| Published Language (schema)        | arknet-ontology                 | .ttl ontologies and shapes |
| Checker                            | arknet-architecture-tests       | ArchUnit rules and ontology cross-checks |

## 4. Dependencies

As built (from the POMs):

- Every `-core` depends only on `arknet-shared-kernel`, plus its own context's
  vocabulary module where one exists (`arknet-product-requirements-shared`,
  `arknet-domain-modelling-shared`). No core depends on a neighbouring core. One
  exception, #560: `arknet-model-evaluation-core` additionally names
  `arknet-persistence-support`, because its subject is the published language itself --
  the type-independent read model and the `Ark*Vocabulary` constants are what a snapshot
  of that language is made of. It uses nothing of kognio-rdf (`DependencyRulesTest`
  rule 3) and nothing of the module's write half (rule 14).
- Every `-adapter-kogniordf` depends on its own core and `arknet-persistence-support`,
  the writing ones additionally on `arknet-ontology`; on no neighbouring module. It resolves neighbour codes
  by SPARQL in the neighbour's graph (Published Language).
- Every `-adapter-mcp` and `arknet-app` depend on `arknet-mcp-support`; it is not
  reachable transitively, because no core depends on it.
- `-adapter-mcp` depends on neighbouring cores (Borrowed In-Port), except between the
  Components of one and the same Bounded Context, where the edge is gone (ADR-49):
  - requirements-mcp -> ubiquitous-language-core
  - use-cases-mcp -> ubiquitous-language-core, actor-core
  - bounded-context-mcp: none since #441 -- it asks its own `ResolveLinkedTerms`
    in-port, served by `TermLookup#codesById` over the glossary's Published Language
  - adr-mcp -> requirements-core, ubiquitous-language-core, bounded-context-core
  - actor-mcp, project-mcp, ubiquitous-language-mcp, model-analysis-mcp: none
- The modules of `arknet-resource-access` depend on their own core and on no module of a
  Bounded Context; it reads their resource types at runtime from ontology and shapes (ADR-69,
  consequence 8). Only the composition root may depend on it (`DependencyRulesTest`
  rule 17).

Target (ADR-49): one mechanism for every edge, inside and across context boundaries.
The core defines its own out-port for whatever it needs from a neighbour; the
`adapter-kogniordf` serves it through the neighbour's Published Language in both
directions (code to identity when writing, identity to code when reading), and
in-port results carry foreign codes themselves. Every `-adapter-mcp` then depends on
its own core only; no module of a Component depends on a module of another.

## 5. Check against the schema's mandatory parts

| Mandatory per schema                          | arknet's state                                       |
|-----------------------------------------------|-----------------------------------------------------|
| Bounded Context as Maven parent               | built for all six contexts                           |
| `api` per Component                           | not required: extension stage, no foreign in-port caller in arknet (ADR-58) |
| `core` per Component, framework-free          | present                                              |
| one adapter per technology per Component      | present (kogniordf, mcp)                             |
| `<bc>-shared` once ownerless identities exist | built for both contexts that need one: `arknet-product-requirements-shared` (RequirementCode, ConstraintCode, TermRef) and `arknet-domain-modelling-shared` (TermCode) |
| Application outside every Bounded Context     | present (arknet-app); it holds no evaluation; besides the wiring it carries the type-independent tools, of which `resource_get`, `resource_history` and `text_search` move to the Component for generic resource access (ADR-69), and the HTML report, which moves to Model Analysis (ADR-54) |
| dependency rules as a checker                 | partial: ArchUnit carries the rule that no module of a Component depends on a module of another for Product & Requirements and Domain Modelling, plus that a driving adapter sees only in-ports and domain types, that a `<bc>-shared` hangs on nothing but the Shared Kernel, that no core or vocabulary module reaches into `arknet-mcp-support`, that the Shared Kernel holds exactly the terms ADR-56 admits, and that Model Analysis names no module of the contexts it reads; the other contexts still borrow in-ports (ADR-49); generic resource access names no module of a context, only the composition root names it, and its core stays free of RDF4J and Spring, with a scan of its class files for any namespace of the model |
| extension stages (starter, bom, adapter-events) | no trigger met -- correctly absent                 |

## 6. Cross-cutting concepts (pointers only)

- Write funnel, versioning, concurrency token, content graph, child fragments: ADR-3, ADR-61, ADR-63, ADR-64
- Persistence only through domain-near out-ports, records without graph access: ADR-8, ADR-45
- Project identity: anchor, one dataset per project, system dataset: ADR-20, ADR-25, ADR-27
- Operation: one shared daemon, Spring AI: ADR-16, ADR-14
- Composition root wires the Components, no application logic: ADR-70 (succeeds ADR-55)
- Generic resource access in one Component outside every Bounded Context: ADR-69
- Every writing Bounded Context has exactly one namespace of its own: ADR-67
- Foreign concepts through an anti-corruption layer: ADR-19
- Single user, no tenancy in the cores: ADR-6

## 7. What moves this file

- #642: the HTML report leaves the composition root into an
  `-adapter-html` of Model Analysis, with its read model separated from its rendering,
  which is what ends the report's borrowed in-ports into every context (ADR-49,
  ADR-70). Sections 3 to 5 then describe one shape instead of two.
- #746 and #743: the Component for generic resource access is built outside every
  Bounded Context. Its `submit` engine and `describe` (core and kognio-rdf out-adapter), the
  MCP tools for both and their wiring in the composition root
  (`ArknetMcpConfiguration.ResourceAccessConfiguration`) exist. The wiring is conditional on
  `arknet.resource-access.enabled=true`, which is off by default, so `describe` and `submit`
  are not offered before #746. #743 moves the generic read tools
  (`resource_get`, `resource_history`, `text_search`) out of the composition root (ADR-69).
- #77: the building-block view moves into the store; this file goes away. Before
  that, a successor of ADR-46 (components move from "outside" to "not yet built"),
  see the comment on #77.
