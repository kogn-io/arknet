# arknet -- Architecture Knowledge Net

DDD architecture models that machines can understand.

W3C standards (RDF/OWL) instead of a proprietary DSL -- validatable (SHACL),
queryable (SPARQL), AI-ready (MCP).

## Repository

Code and pull requests live on GitHub
([`github.com/kogn-io/arknet`](https://github.com/kogn-io/arknet)). For bugs,
feature requests, and questions, please open a thread in
[GitHub Discussions](https://github.com/kogn-io/arknet/discussions).

## Requirements

- Java 25+
- Maven 3.9+

## Claude Code Plugin

```bash
claude --plugin-dir /path/to/arknet
```

### Prerequisite: start the MCP server daemon

The arknet MCP server is a single, long-lived process that serves **all** arknet
workspaces on the machine over Streamable HTTP at `127.0.0.1:47331` -- it is
**not** a subprocess that Claude Code starts. An HTTP entry in `.mcp.json` is
purely passive in Claude Code: it only connects to the URL, it does not start or
manage anything. So you start the daemon yourself once before first use. There
are three ways; **Docker is the recommended one** (no local Java/Maven needed).

#### Option A: Docker (recommended)

Build the image from the repo root -- the build context MUST be the repo root,
because `arknet-mcp` is a multi-module reactor and needs its neighbouring
modules:

```bash
docker build -f arknet-mcp/Dockerfile -t arknet-mcp .
docker run --rm -d --name arknet-mcp \
  -p 127.0.0.1:47331:47331 \
  -v ~/.arknet/rdf:/data/rdf \
  arknet-mcp
```

The `-p 127.0.0.1:47331:47331` is **deliberate, do not simplify it**: inside the
container the server binds to `0.0.0.0` (via the `SERVER_ADDRESS` env in the
image), because Docker's port-publish NAT delivers inbound connections to the
container's `eth0`, not to loopback -- a plain `127.0.0.1` bind would be
unreachable from outside. That moves the trust boundary to the **host side**: the
publish MUST explicitly bind to host loopback (`127.0.0.1:47331:47331`). A bare
`-p 47331:47331` would expose the **unauthenticated** daemon to the whole LAN
(ADR-009). The volume maps the same host path the bare-jar run uses
(`~/.arknet/rdf`, `arknet.rdf.storage`), so the model survives container
restarts.

#### Option B: Docker Compose

Wires up the port publish (host loopback) and volume mount for you:

```bash
docker compose up --build
```

#### Option C: from source (for contributors, local JDK 25 + Maven needed)

```bash
mvn -pl arknet-mcp -am package -DskipTests
java -jar arknet-mcp/target/arknet-mcp-*.jar
```

As long as the process runs, any number of Claude Code sessions (including
parallel worktrees of the same workspace) can share the same store without
blocking each other on the NativeStore directory lock. If the daemon is not
running, Claude Code reports the MCP connection as failed.

Which workspace a call hits is decided by the directory the Claude Code session
was started from: `.mcp.json` sends it in the header
`X-Arknet-Workspace-Dir: ${PWD}`, and the server derives the WorkspaceId from it
(via git-common-dir, just as in a stdio session). So **start Claude Code from
the project directory** -- `${PWD}` carries environment-variable semantics, not
a dynamic working directory. A call without that header falls back to the
workspace of the daemon's working directory. The header is not authentication,
only workspace routing at a loopback / single-user boundary (ADR-009). Because
the workspace comes per call from the header, all projects share this one port
without collision.

### MCP tools

Requirements BC (`arknet-requirements`) -- requirement lifecycle:

| Tool | Description |
|------|-------------|
| `req_add` | Create a requirement (functional / non-functional) |
| `req_list` | List all managed requirements |
| `req_get` | Fetch a single requirement by identity (e.g. FR-1, NFR-7) |
| `req_set_status` | Change lifecycle status (PROPOSED / ACCEPTED) |
| `req_link_term` | Link a requirement to a glossary term (`arkreq:usesTerm`; the term must exist) |
| `req_schema` | The `arkreq:` vocabulary (RequirementType, RequirementStatus, Priority) as data -- definition + allowed values, so a client need not guess |

Ubiquitous Language BC -- glossary terms (SKOS Concepts):

| Tool | Description |
|------|-------------|
| `term_add` | Create a new glossary term (mints a SKOS Concept; optionally markable as an actor via `actorKind`/`actorRole`) |
| `term_list` | List all glossary terms |
| `term_get` | Fetch a single term by identity (e.g. TERM-1) |

Use Cases BC (`arknet-use-cases`) -- flow-oriented Cockburn use cases (bind FRs via an interaction flow):

| Tool | Description |
|------|-------------|
| `uc_add` | Create a complete use case in one call (goal, actor, trigger, numbered step flow with FR references) |
| `uc_list` | List all use cases |
| `uc_get` | Fetch a single use case with resolved steps and FR/actor edges (e.g. UC1) |

Bounded Context BC (`arknet-bounded-context`) -- BoundedContext lifecycle (assigns glossary terms to a domain cut):

| Tool | Description |
|------|-------------|
| `bc_add` | Create a new bounded context |
| `bc_list` | List all bounded contexts |
| `bc_get` | Fetch a single bounded context with its linked glossary terms |
| `bc_link_term` | Link a bounded context to a glossary term (`arknet:hasAggregate`; the term must exist) |

Store report -- generic, cross-BC read path (readOnly; works for any BC without type mapping):

| Tool | Description |
|------|-------------|
| `store_overview` | Compact text digest of the workspace store (prefix legend, type counts, entity rows with `resource_get` drill-down, integrity hint) + writes a self-contained HTML resource browser and returns its path |
| `resource_get` | All triples of a resource (outgoing and incoming); handle as CURIE (`req:FR-1`), full IRI, or bare business id (`FR-1`) |

Traceability -- readOnly graph traversal over the same store snapshot (no second SPARQL path):

| Tool | Description |
|------|-------------|
| `trace_matrix` | Per requirement (FR/NFR): the glossary terms used (`arkreq:usesTerm`) and the realizing use case(s) (via the step flow) |
| `orphan_check` | Orphaned artefacts: requirements without a realizing use case, glossary terms without any usage |
| `impact_analysis` | Transitive "who references this" closure for a resource handle -- what is affected if X changes |

### Storage model (store-first)

The model lives primarily in the local RDF store (kognio-rdf), **persistent
across sessions** -- not in-memory and not in a Turtle file. Per workspace
(= project, derived from the git top level or working directory) the store keeps
an isolated dataset; default location `~/.arknet/rdf`, configurable via
`arknet.rdf.storage`.

**Managing the model:** through the store-based BC tools (`req_*`, `term_*`,
`uc_*`) -- not by text-editing a `.ttl`. SHACL validation applies uniformly at
the store's write gate: an invalid write is rejected and nothing is persisted.

The formerly tolerated file-based `arknet_*` tools
(`arknet_load`/`arknet_validate`/`arknet_query`/`arknet_generate` from a `.ttl`)
have been removed -- store-first is the only model lifecycle, no parallel file
truth anymore. Background:
[ADR-005](docs/adr/adr-005-store-first-model-lifecycle.md) including its
addendum.

## Modules

| Module | Description |
|--------|-------------|
| `arknet-ontology` | OWL ontology and SHACL shapes (.ttl resources only, no Java) |
| `arknet-mcp` | MCP server (Streamable HTTP, local daemon) + composition root: wires the BC hexagons (requirements / ubiquitous-language / use-cases / bounded-context) via a shared DatasetLifecycle + the generic store report (`store_overview`/`resource_get`) + the traceability read path (`trace_matrix`/`orphan_check`/`impact_analysis`) |
| `arknet-shared-kernel` | DDD shared kernel: domain building blocks shared by several BCs (`WorkspaceId`, opaque `ResourceId`/`ResourceIdFactory`) |
| `arknet-persistence-support` | Technical support for the kognio-rdf out-adapters: the shared SHACL write gate (validate-before-commit) |
| `arknet-requirements` | First hexagonal BC: requirement lifecycle (core + kognio-rdf out-adapter + MCP/Spring AI in-adapter) |
| `arknet-ubiquitous-language` | Second hexagonal BC: glossary terms as SKOS Concepts (core + kognio-rdf out-adapter + MCP/Spring AI in-adapter) |
| `arknet-use-cases` | Third hexagonal BC: flow-oriented Cockburn use cases (core + kognio-rdf out-adapter + MCP/Spring AI in-adapter) |
| `arknet-bounded-context` | Fourth hexagonal BC: BoundedContext lifecycle, assigns glossary terms to a domain cut (core + kognio-rdf out-adapter + MCP/Spring AI in-adapter) |
| `arknet-architecture-tests` | ArchUnit rules for the dependency invariants the module cut cannot enforce (only `src/test`, no production code) |

## Ontology

Modular layout under the namespace `https://w3id.org/arknet/`:

| Module | Prefix | Concepts |
|--------|--------|----------|
| `arknet-core.ttl` | `arknet:` | BoundedContext, Aggregate, Entity, ValueObject, Command, DomainEvent, ContextMap |
| `arknet-process.ttl` | `arkproc:` | Process, Step, State, StateTransition, BusinessRule, Outcome, Actor |
| `arknet-requirements.ttl` | `arkreq:` | Requirement (FR/NFR), UseCase, Goal, Constraint, Priority (MoSCoW), Status, Milestone, Release |
| `arknet-architecture.ttl` | `arkarch:` | Architecture, View, Viewpoint, ADR, Stakeholder, Concern |
| `arknet-tech.ttl` | `arktech:` | Service, Container, API, Database (planned) |
| `arknet-privacy.ttl` | `arkpriv:` | DataCategory, LegalBasis, ProcessingPurpose, DataSubjectRight, TechnicalMeasure, PrivacyImpactAssessment |

## Architecture

Pipes & Filters (**not implemented** -- no generating output path, see
[ADR-005](docs/adr/adr-005-store-first-model-lifecycle.md)):

```
Turtle (.ttl) -> Parse -> Validate (SHACL) -> Triple Store (RDF4J) -> SPARQL -> Mustache -> AsciiDoc -> HTML/PDF
```

What is actually lived today is store-first (MCP write tools -> RDF4J store ->
generic read path `store_overview`/`resource_get`, see above).

## Origin

Consolidated from three projects:

- **doc42** -- walking skeleton (Java/RDF4J pipeline)
- **dddprocess** -- DDD process ontology (state machines, gap analysis)
- **ddd-forge** -- Claude plugin (privacy ontology, AI skills)

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
