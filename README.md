# arknet -- Architecture Knowledge Net

arknet is an **MCP server**: it stores a software project's architecture
model -- requirements, use cases, glossary terms, bounded contexts, ADRs -- as
structured data instead of prose, and exposes it as tools that an AI coding
agent (or any [MCP](https://modelcontextprotocol.io/) client) can query,
validate and write to.

## Contents

- [Why](#why)
- [Getting started](#getting-started)
- [Repository](#repository)
- [MCP Server](#mcp-server)
  - [Prerequisite: start the MCP server daemon](#prerequisite-start-the-mcp-server-daemon)
  - [Register your project](#register-your-project)
  - [MCP tools](#mcp-tools)
  - [Storage model (store-first)](#storage-model-store-first)
- [Modules](#modules)
- [Ontology](#ontology)
- [Architecture](#architecture)
- [Origin](#origin)
- [License](#license)

## Why

arknet is built on a specific bet: that architecture knowledge is
increasingly read and written by AI coding agents, not only by humans
skimming a wiki. That raises the bar past "readable" -- an agent needs a
model it can act on without guessing, which prose was never designed to
guarantee.

Architecture documentation written as prose -- wikis, Word docs, code
comments -- drifts from the code and from itself: a use case can go on
referencing a requirement that was deleted months ago, a glossary term can
mean two different things in two documents, and nothing notices until a human
happens to re-read both side by side. An AI agent working from that prose
inherits the same drift -- it can only guess whether "the order" in one
paragraph is the `Order` concept defined three pages earlier.

arknet keeps the same content, but as structured
[DDD](https://en.wikipedia.org/wiki/Domain-driven_design)-shaped data in an
RDF store instead of unstructured text:

| Without arknet | With arknet |
|---|---|
| Requirements live in a wiki page; nobody notices when a use case keeps referencing one that was deleted. | Deleting a requirement a use case still realizes is refused at the write gate ([SHACL](https://www.w3.org/TR/shacl/) validation); `orphan_check` surfaces dangling references that already slipped through. |
| "What breaks if we change the `Order` concept?" means grepping the wiki and hoping you found every mention. | `impact_analysis(handle: "TERM-4")` returns the transitive closure of every requirement, use case and ADR that references it. |
| An AI agent reads prose and guesses whether two documents mean the same thing by "the customer". | The agent calls `term_get`/`req_get`/`uc_get` over MCP and gets back the same model every other client sees -- one glossary, one term, one meaning. |

No proprietary [DSL](https://en.wikipedia.org/wiki/Domain-specific_language)
to learn either: the model is plain W3C standards --
[RDF](https://www.w3.org/RDF/)/[OWL](https://www.w3.org/OWL/) for the data,
SHACL to validate it on write,
[SPARQL](https://www.w3.org/TR/sparql11-query/) to query it -- so anything
that speaks RDF can read an arknet store, not just arknet itself.

This is what a use case (`UC1`) looks like once it is in the store --
rendered by `store_overview`'s self-contained HTML report, not hand-written:

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/img/use-case-card-dark.png">
  <img src="docs/img/use-case-card.png" alt="Rendered use case card: numbered flow, realizes-links to FRs, raw triples on demand">
</picture>

## Getting started

This repository builds and ships only the MCP server -- it has no UI or CLI of
its own. To actually use it:

1. Install the [`kogn-io/arknet-plugin`](https://github.com/kogn-io/arknet-plugin)
   Claude Code plugin -- it ships the skills (`/arknet:req-interview`,
   `/arknet:adr`, `/arknet:bc-audit`, ...) that are the real entry point for
   working with a model day to day, on top of the raw MCP tools this repo
   provides.
2. [Start the MCP server daemon](#prerequisite-start-the-mcp-server-daemon)
   once and [register your project](#register-your-project).
3. Follow the plugin's own
   ["Getting started" walkthrough](https://github.com/kogn-io/arknet-plugin#getting-started)
   for the actual workflow -- running `/arknet:req-interview`, then reading
   the result back with `store_overview`.

## Repository

Code and pull requests live on GitHub
([`github.com/kogn-io/arknet`](https://github.com/kogn-io/arknet)). Bugs and
feature requests go to the
[issue tracker](https://github.com/kogn-io/arknet/issues); open-ended
questions go to
[GitHub Discussions](https://github.com/kogn-io/arknet/discussions).

## MCP Server

The arknet MCP server exposes the model as tools over the Model Context
Protocol. See [Getting started](#getting-started) above for how to actually
put it to use.

### Prerequisite: start the MCP server daemon

The arknet MCP server is a single, long-lived process that serves **all** arknet
projects on the machine over Streamable HTTP at `127.0.0.1:47331` -- it is
**not** a subprocess that Claude Code starts. An HTTP entry in `.mcp.json` is
purely passive in Claude Code: it only connects to the URL, it does not start or
manage anything. So you start the daemon yourself once before first use. There
are three ways, all of them Docker: arknet exists only as a server, run locally
or on the network.

#### Option A: pre-built image from GHCR (recommended)

Every push to `main` publishes the daemon image, so nothing has to be built
locally:

```bash
docker run --rm -d --name arknet-mcp \
  -p 127.0.0.1:47331:47331 \
  -v ~/.arknet/rdf:/data/rdf \
  -v ~/.arknet/report:/data/report \
  -e ARKNET_REPORT_HOST_DIR=$HOME/.arknet/report \
  -v ~/.arknet/export:/data/export \
  -e ARKNET_EXPORT_HOST_DIR=$HOME/.arknet/export \
  ghcr.io/kogn-io/arknet:latest
```

Read the note under Option B on why the port publish must stay bound to
`127.0.0.1` and what `ARKNET_REPORT_HOST_DIR`/`ARKNET_EXPORT_HOST_DIR` are for
-- both apply here just the same.

#### Option B: Docker, built from source

Build the image from the repo root -- the build context MUST be the repo root,
because `arknet-mcp` is a multi-module reactor and needs its neighbouring
modules:

```bash
docker build -f arknet-mcp/Dockerfile -t arknet-mcp .
docker run --rm -d --name arknet-mcp \
  -p 127.0.0.1:47331:47331 \
  -v ~/.arknet/rdf:/data/rdf \
  -v ~/.arknet/report:/data/report \
  -e ARKNET_REPORT_HOST_DIR=$HOME/.arknet/report \
  -v ~/.arknet/export:/data/export \
  -e ARKNET_EXPORT_HOST_DIR=$HOME/.arknet/export \
  arknet-mcp
```

The `-p 127.0.0.1:47331:47331` is **deliberate, do not simplify it**: inside the
container the server binds to `0.0.0.0` (via the `SERVER_ADDRESS` env in the
image), because Docker's port-publish NAT delivers inbound connections to the
container's `eth0`, not to loopback -- a plain `127.0.0.1` bind would be
unreachable from outside. That moves the trust boundary to the **host side**: the
publish MUST explicitly bind to host loopback (`127.0.0.1:47331:47331`). A bare
`-p 47331:47331` would expose the **unauthenticated** daemon to the whole LAN
(ADR-009). The first volume holds the store itself (`~/.arknet/rdf`,
`arknet.rdf.storage`), so the model survives container restarts. The second
volume (`arknet.report.dir`) is where `store_overview` writes its self-contained
HTML report -- without it, the report has nowhere to go and its write fails.
`ARKNET_REPORT_HOST_DIR` must name the same path as that volume's host side
(`arknet.report.host-dir`): the container cannot discover its own bind mount's
host-side path on its own, so without this the digest's `# HTML report: ...`
line would name the container-internal `/data/report`, which the calling
agent -- running outside the container -- cannot reach (issue #160). The third
volume (`arknet.export.dir`) is where `project_export` writes its backups --
without it, the fallback export dir defaults to the container's root
filesystem and every `project_export` call fails with an
`AccessDeniedException`. `ARKNET_EXPORT_HOST_DIR` (`arknet.export.host-dir`)
mirrors `ARKNET_REPORT_HOST_DIR` for the same reason.

The container adopts that volume's existing owner automatically at startup (a
fresh, container-only volume falls back to the image's own non-root user) --
no host-side `chown`/`chmod` needed, even when reusing a directory an earlier
run already wrote to. Set `PUID`/`PGID` (`-e PUID=... -e PGID=...`)
only if you want to force a specific uid:gid regardless of what currently owns
the volume.

#### Option C: Docker Compose

Wires up the port publish (host loopback) and volume mount for you:

```bash
docker compose up --build
```

As long as the process runs, any number of Claude Code sessions (including
parallel worktrees of the same project) can share the same store without
blocking each other on the NativeStore directory lock. If the daemon is not
running, Claude Code reports the MCP connection as failed.

**Never run a second daemon instance -- of any kind -- against the same
`~/.arknet/rdf` (or bind-mounted equivalent) directory.** A second `docker run`,
a second `docker compose up`, or a hand-rolled `stdio` MCP entry that launches
the server as a local process all try to open the same storage root a second
time. Each daemon takes an exclusive lock over the whole storage root at
startup, before it opens any project's dataset, so a second instance now fails
immediately on its own start -- not just once a call happens to reach a
project the first daemon already opened. This applies to every client that
talks to arknet, including any custom subagent configuration you write
yourself: point it at the one running daemon's HTTP endpoint
(`http://127.0.0.1:47331/mcp`), never at an inline/stdio server definition of
its own.

### Register your project

Which project a call hits is decided by an **anchor**: an opaque string your
client presents and the server looks up. `.mcp.json` sends the directory the
Claude Code session was started from, in the header
`X-Arknet-Project-Anchor: ${PWD}`. So **start Claude Code from the project
directory** -- `${PWD}` carries environment-variable semantics, not a dynamic
working directory.

The server never interprets that value: it does not shorten it, parse it, or
guess. An anchor nobody registered is an error, not a route to a default
project. Register once per project, from its directory:

```
project_add(label: "my-project")
```

Working on the same project from a second directory -- a git worktree, another
checkout -- start Claude Code there and attach it:

```
project_attach_anchor(anchor: "/path/to/the/worktree")
```

If you used arknet before projects were registered, your data sits in a dataset
named after the old derived id. `project_list` shows those under "unregistered
datasets"; claim one from the directory it belongs to, and it keeps its data:

```
project_adopt(datasetId: "my-project", label: "my-project")
```

A client that cannot set headers can pass the anchor to any tool instead, via
its optional `projectAnchor` parameter. The header stays the primary path: an
anchor supplied as a tool argument comes from the language model, and a guessed
one that happens to exist would silently hit the wrong project.

The header is not authentication, only project routing at a loopback /
single-user boundary (ADR-009). Because the project comes per call from the
anchor, all projects share this one port without collision.

### MCP tools

Requirements BC (`arknet-requirements`) -- requirement lifecycle:

| Tool | Description |
|------|-------------|
| `req_add` | Create a requirement (functional / non-functional), with one or more testable acceptance criteria, each its own positioned resource (`arkreq:AcceptanceCriterion`). Title, description and every criterion's text are natively multilingual -- an optional `language` argument tags the literals being written (BCP-47, e.g. `"en"`); omitted, it falls back to the project's configured default language, and rejects the call if the project has none either |
| `req_list` | List all managed requirements |
| `req_get` | Fetch a single requirement by identity (e.g. FR-1, NFR-7). An optional `displayLocale` argument picks which language variant of a multilingual title/description/acceptance-criterion text to return, falling back to the calling project's `defaultLanguage`, then to an untagged value |
| `req_set_status` | Change lifecycle status (PROPOSED / ACCEPTED) |
| `req_link_term` | Link a requirement to a glossary term (`arkreq:usesTerm`; the term must exist) |
| `req_link_constraint` | Link a requirement to a constraint it is bound by (`oslc_rm:constrainedBy`; the constraint must already exist -- create it first with `constraint_add`). Linking the same constraint twice is a no-op |
| `req_update` | Correct a requirement's title, description and/or MoSCoW priority (each optional, unchanged if omitted); append new acceptance criteria and/or correct an existing one's text by position -- position is purely technical, so mid-list insert/delete/reorder is not supported. `language` scopes a non-omitted title/description/touched-criterion write to that one language's literal (falling back to the project's default language if omitted, rejecting the call if neither is set), leaving other language variants untouched -- except a stale untagged one, swept away once the resolved tag equals the project's default |
| `req_schema` | The `arkreq:` vocabulary (RequirementType, RequirementStatus, Priority) as data -- definition + allowed values, so a client need not guess |

The same hexagon also carries `arkreq:Constraint` -- a non-negotiable, externally-imposed boundary on the solution space (ISO 29148), not a bounded context of its own. A constraint's type and code are fixed at creation (there is no status to change either), but its text is correctable and natively multilingual:

| Tool | Description |
|------|-------------|
| `constraint_add` | Register a new constraint: `TECHNICAL`, `BUSINESS` or `REGULATORY` (each subtype numbered independently: `TCON-N`/`BCON-N`/`RCON-N`). Title and statement are natively multilingual -- an optional `language` argument tags the literals being written (BCP-47, e.g. `"en"`); omitted, it falls back to the project's configured default language, and rejects the call if the project has none either |
| `constraint_update` | Correct a constraint's title and/or statement, or state either of them in a further language, keeping its identity, code and type unchanged (each text argument optional, unchanged if omitted). `language` scopes the write to that one language's literal (falling back to the project's default language if omitted, rejecting the call if neither is set), leaving other language variants untouched -- except a stale untagged one, swept away once the resolved tag equals the project's default |
| `constraint_list` | List all managed constraints |
| `constraint_get` | Fetch a single constraint by identity (e.g. TCON-1, BCON-1, RCON-1). An optional `displayLocale` argument picks which language variant of a multilingual title/statement to return, falling back to the calling project's `defaultLanguage`, then to an untagged value |

Ubiquitous Language BC -- glossary terms (SKOS Concepts):

| Tool | Description |
|------|-------------|
| `term_add` | Create a new glossary term (mints a SKOS Concept; optionally markable as an actor via `actorKind`/`actorRole`). An optional `broader` argument names an already-existing term this one specializes (`skos:broader`); an unresolvable code is rejected. Preferred label and definition are natively multilingual -- an optional `language` argument tags the literal being written (BCP-47, e.g. `"en"`); omitted, it falls back to the project's configured default language, and rejects the call if the project has none either |
| `term_update` | Correct a term's preferred label, definition, actor facette and/or broader term, keeping its identity (and all links into it) unchanged (each argument optional, unchanged if omitted). `broader` is the one exception to "omitted = unchanged": passing an empty string explicitly clears an already-set broader term, since omitting it already means "leave alone" -- rejected if the code does not resolve, or if it would make the term its own (direct or transitive) broader term. `language` scopes the write to that one language's literal (falling back to the project's default language if omitted, rejecting the call if neither is set), leaving other language variants untouched -- except a stale untagged one, swept away once the resolved tag equals the project's default |
| `term_list` | List all glossary terms |
| `term_get` | Fetch a single term by identity (e.g. TERM-1). An optional `displayLocale` argument picks which language variant of a multilingual label/definition to return, falling back to the calling project's `defaultLanguage`, then to an untagged value |

Use Cases BC (`arknet-use-cases`) -- flow-oriented Cockburn use cases (bind FRs via an interaction flow):

| Tool | Description |
|------|-------------|
| `uc_add` | Create a complete use case in one call (goal, actor, trigger, numbered step flow with FR references). Title, goal, scope, trigger, precondition, postcondition and every step's/extension's text are natively multilingual -- an optional `language` argument tags the literals being written (BCP-47, e.g. `"en"`); omitted, it falls back to the project's configured default language, and rejects the call if the project has none either |
| `uc_list` | List all use cases |
| `uc_get` | Fetch a single use case with resolved steps and FR/actor edges (e.g. UC1). An optional `displayLocale` argument picks which language variant of a multilingual title/goal/scope/trigger/precondition/postcondition/step/extension text to return, falling back to the calling project's `defaultLanguage`, then to an untagged value |
| `uc_update` | Correct a use case's title, goal, scope, trigger, precondition, postcondition and/or extensions (each optional, unchanged if omitted), and/or individual existing steps' text and/or FR-realises references by position (a listed step's realises set is replaced wholesale, empty to clear it) -- does not touch primaryActor, supportingActors or the step list's structure. `language` scopes a non-omitted title/goal/scope/trigger/precondition/postcondition/step-text/extension-text write to that one field's/position's literal (falling back to the project's default language if omitted, rejecting the call if neither is set), leaving other language variants untouched -- except a stale untagged one, swept away once the resolved tag equals the project's default |
| `uc_link_term` | Link a use case to a glossary term of the ubiquitous language it uses (`arkreq:usesTerm`; the term must exist). Linking the same term twice is a no-op |
| `uc_link_constraint` | Link a use case to a constraint it is bound by (`oslc_rm:constrainedBy`; the constraint must already exist -- create it first with `constraint_add`). Linking the same constraint twice is a no-op |

Bounded Context BC (`arknet-bounded-context`) -- BoundedContext lifecycle (assigns glossary terms to a domain cut):

| Tool | Description |
|------|-------------|
| `bc_add` | Create a new bounded context |
| `bc_list` | List all bounded contexts |
| `bc_get` | Fetch a single bounded context with its linked glossary terms |
| `bc_link_term` | Link a bounded context to a glossary term (`arkddd:ubiquitousLanguageTerm`; the term must exist) |
| `bc_link_context` | Record a directed DDD context-mapping relationship between two existing bounded contexts (`arkddd:ContextRelationship`; both must exist), classified by one of eight `arkddd:RelationshipType` values. Pure CRUD, not idempotent -- every call creates a new relationship |

ADR BC (`arknet-adr`) -- architecture decision records, store-backed and numbered independently of the hand-written markdown records under `docs/adr/`:

| Tool | Description |
|------|-------------|
| `adr_add` | Record a new decision in one call (title, context, decision, optional consequences, considered options and decision date, plus the requirement codes it addresses and the bounded-context codes it affects; each referenced resource must already exist). Starts out PROPOSED |
| `adr_list` | List all decisions, one compact line each |
| `adr_get` | Fetch a single decision with its full text and both directions of the supersedes relation (e.g. ADR-1) |
| `adr_set_status` | Change a decision's lifecycle status: PROPOSED -> ACCEPTED, PROPOSED -> REJECTED, or ACCEPTED -> DEPRECATED |
| `adr_supersede` | Record that one decision replaces an older one (`arkarch:supersedes`). Only the forward edge is stored -- the superseded decision reports it as "superseded by" from a reverse read, not from a second triple |

Project BC (`arknet-project`) -- the project registry: which anchor a call arrives with belongs to which project ([ADR-016](docs/adr/adr-016-projekt-identitaet-ueber-registrierte-anker.md)). An anchor is an opaque, typed string (`path`, `url`, `uuid`) the client sends and the server only ever looks up -- never parses, never derives an identity from. One project holds several anchors (a git worktree, a second checkout); one anchor belongs to exactly one project. Unlike every other bounded context, these tools are not scoped to one project -- their registry is what answers the routing question:

| Tool | Description |
|------|-------------|
| `project_add` | Register a project; the calling client's origin directory becomes its first anchor (or pass `anchor`/`anchorType` explicitly for clients that cannot supply one). Optional `description` (multilingual, tagged via `language`) and `defaultLanguage` (a single BCP-47 tag) can be set at creation |
| `project_adopt` | Claim an existing dataset as the project the call comes from -- for data written before projects were registered, or a dataset restored from a backup; the dataset keeps its identity and all its data |
| `project_attach_anchor` | Attach a further anchor to the project the call comes from -- for the same project worked on from a second directory (`callerAnchor` names the calling project explicitly when the transport carries no origin directory) |
| `project_rename` | Rename the project the call comes from; identity and anchors are unaffected (same optional `callerAnchor`) |
| `project_update` | Change a project's description and/or default display language (each optional, unchanged if omitted); `language` scopes a description write to that one language's literal, other language variants untouched -- same pattern as `term_update` |
| `project_list` | List all registered projects with their anchors and identities, plus any datasets no project claims yet (adoptable with `project_adopt`) |

Store report -- generic, cross-BC read path (readOnly; works for any BC without type mapping):

| Tool | Description |
|------|-------------|
| `store_overview` | Compact text digest of the project store (prefix legend, type counts, entity rows with `resource_get` drill-down, integrity hint) + writes a self-contained HTML report and returns its path. The report reads as the model rather than as triples -- use cases with their numbered flow, requirements with their acceptance criteria, glossary, bounded contexts -- and keeps a raw section for everything no bounded context claims, so nothing in the store can hide from it. References show the term itself rather than its running number, and requirement and bounded-context prose is marked up against the glossary: a mention the model links to becomes a link, a mention of a glossary term with no such link is flagged as a gap -- the link is only ever created by an explicit `req_link_term`/`bc_link_term` call, so text and model drift apart by default. A card's title, its description-like fields and its positioned items (flow steps, extensions, acceptance criteria) carry a client-side language switch when the store holds more than one language for them, toggled from the report's own toolbar -- no server round-trip needed |
| `resource_get` | The model triples of a resource (outgoing and incoming); handle as CURIE (`req:FR-1`), full IRI, bare business id (`FR-1`), or a blank-node reference (`_:...`) as shown by `store_overview` for a store-first resource with no minted IRI. The revision trail is left out -- it is change history, not model ([ADR-014](docs/adr/adr-014-revision-als-concurrency-token.md)); read it with `resource_history` |
| `resource_history` | The change history the model view leaves out: every PROV-O revision the shared write funnel has recorded for a resource, oldest first, with the current one marked -- same handle contract as `resource_get`. A resource written only store-first, or predating the funnel, has no history (empty, not an error) |

Traceability -- readOnly graph traversal over the same store snapshot (no second SPARQL path):

| Tool | Description |
|------|-------------|
| `trace_matrix` | Per requirement (FR/NFR): the glossary terms used (`arkreq:usesTerm`) and the realizing use case(s) (via the step flow) |
| `orphan_check` | Orphaned artefacts: requirements without a realizing use case, glossary terms never referenced (used by a requirement or a use case, bounded-context language, or being another term's broader term), text mentions of a term missing its `usesTerm`/`ubiquitousLanguageTerm`/`broader` edge -- including a term's own definition mentioning another term it does not name as its broader term -- and constraints no requirement or use case is bound by via `constrainedBy` |
| `impact_analysis` | Transitive "who references this" closure for a resource handle -- what is affected if X changes, following the requirement/use-case/glossary/bounded-context edges plus an ADR's `addressesRequirement`/`affectsContext`/`supersedes` (see sample below) |
| `actor_usecase_matrix` | Raw bipartite view of actor/use-case involvement (`arkreq:primaryActor`/`supportingActor`), in both directions -- no clustering, no bounded-context judgement |
| `term_cooccurrence` | Which glossary terms are named together in the same requirement or use-case text -- literal text co-occurrence only, not a model-edge comparison; raw data for spotting a shared term vs. a homonym with two meanings |

```
> impact_analysis(handle: "TERM-4")

# Impact analysis -- target: TERM-4 [Concept] "Order"

## Transitively affected (4)
- FR-1 [FunctionalRequirement] "Place an order from a confirmed cart"
- FR-2 [FunctionalRequirement] "Authorise the payment before the order is placed"
- FR-3 [FunctionalRequirement] "Confirm the placed order to the customer"
- UC1  [UseCase] "Place an order"
```

Backup -- not project-scoped, one call covers every registered project:

| Tool | Description |
|------|-------------|
| `project_export` | Export every registered project's complete RDF store (every named graph, including provenance and project self-description -- unlike `store_overview`, this hides nothing) as a `.trig` file into a timestamped subdirectory of a configurable export directory. There is no matching import/restore tool yet -- a dataset restored by hand is claimed back into the registry with `project_adopt` |

### Storage model (store-first)

The model lives primarily in the local RDF store (kognio-rdf), **persistent
across sessions** -- not in-memory and not in a Turtle file. One dataset holds
the data of exactly one registered project, so the project boundary is the data
boundary: two projects share no requirement, no use case, not one glossary term.
Default location `~/.arknet/rdf`, configurable via `arknet.rdf.storage`.

**Managing the model:** through the store-based BC tools (`req_*`, `term_*`,
`uc_*`, `bc_*`, `adr_*`) -- not by text-editing a `.ttl`. SHACL validation applies uniformly at
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
| `arknet-mcp` | MCP server (Streamable HTTP, local daemon) + composition root: wires the BC hexagons (requirements / ubiquitous-language / use-cases / bounded-context / project / adr) via a shared DatasetLifecycle + the generic store read path (`store_overview`/`resource_get`/`resource_history`, whose HTML report is assembled per bounded context through their read in-ports) + the traceability read path (`trace_matrix`/`orphan_check`/`impact_analysis`/`actor_usecase_matrix`/`term_cooccurrence`) |
| `arknet-shared-kernel` | DDD shared kernel: domain building blocks shared by several BCs (`ProjectId`, the `ProjectResolver` port, opaque `ResourceId`/`ResourceIdFactory`) |
| `arknet-persistence-support` | Technical support for the kognio-rdf out-adapters: the shared SHACL write gate (validate-before-commit) and the shared write funnel (ADR-013) |
| `arknet-persistence-test-support` | Test-side counterpart to `arknet-persistence-support`: shared `DatasetLifecycle`/`DatasetHandle`/`DatasetTx` decorators that pin a deterministic write interleaving for real-store concurrency tests, consumed at test scope by the requirement/use-case/bounded-context/term adapters |
| `arknet-requirements` | First hexagonal BC: requirement lifecycle (core + kognio-rdf out-adapter + MCP/Spring AI in-adapter) |
| `arknet-ubiquitous-language` | Second hexagonal BC: glossary terms as SKOS Concepts (core + kognio-rdf out-adapter + MCP/Spring AI in-adapter) |
| `arknet-use-cases` | Third hexagonal BC: flow-oriented Cockburn use cases (core + kognio-rdf out-adapter + MCP/Spring AI in-adapter) |
| `arknet-bounded-context` | Fourth hexagonal BC: BoundedContext lifecycle, assigns glossary terms to a domain cut (core + kognio-rdf out-adapter + MCP/Spring AI in-adapter) |
| `arknet-project` | Fifth hexagonal BC: the project registry, mapping a client's opaque anchor to the project whose dataset holds its data (core + kognio-rdf out-adapter + MCP/Spring AI in-adapter); unlike the other BCs it is not itself project-scoped ([ADR-016](docs/adr/adr-016-projekt-identitaet-ueber-registrierte-anker.md)) |
| `arknet-adr` | Sixth hexagonal BC: architecture decision records -- context, decision, consequences and considered options, plus the edges to the requirement a decision addresses, the bounded context it affects and the older decision it supersedes (core + kognio-rdf out-adapter + MCP/Spring AI in-adapter). Store-backed and numbered independently of the hand-written markdown decision records under `docs/adr/` |
| `arknet-architecture-tests` | ArchUnit rules for the dependency invariants the module cut cannot enforce (only `src/test`, no production code) |

## Ontology

Modular layout under the namespace `https://w3id.org/arknet/`:

Active modules (consumed by a BC, published under `w3id.org/arknet/`):

| Module | Prefix | Concepts |
|--------|--------|----------|
| `arknet-core.ttl` | `arknet:` | Generic utility vocabulary (name, description, ...), reusable across every module |
| `arknet-ddd.ttl` | `arkddd:` | BoundedContext, Domain, Subdomain -- the strategic-DDD concepts `arknet-bounded-context` actually writes. Namespace shared with the parked `arknet-ddd_parked.ttl` below (Context Mapping, tactical DDD) |
| `arknet-actor.ttl` | `arkproc:` | Actor, HumanActor, SystemActor, LegalActor, actorRole -- split out of `parked/arknet-process.ttl`; the only slice of that module `arknet-use-cases`/`arknet-bounded-context` actually write |
| `arknet-requirements.ttl` | `arkreq:` | Requirement (FR/NFR), UseCase, Goal, Constraint, Priority (MoSCoW), Status, Milestone, Release |
| `arknet-provenance.ttl` | `arkprov:` | Revision, head -- PROV-O-based revision trail written by the shared write funnel ([ADR-014](docs/adr/adr-014-revision-als-concurrency-token.md)) |
| `arknet-project.ttl` | `arkprj:` | Project, Anchor, AnchorType -- the registered store identity ([ADR-016](docs/adr/adr-016-projekt-identitaet-ueber-registrierte-anker.md)) |
| `arknet-architecture.ttl` | `arkarch:` | ArchitectureDecisionRecord, its text properties, the supersedes/supersededBy/relatedTo/addressesRequirement/affectsContext relations and the five ADRStatus individuals -- the ISO-42010 slice `arknet-adr` actually writes. Namespace shared with the parked `arknet-architecture_parked.ttl` below |

Parked modules (`arknet-ontology/src/main/resources/parked/`, no BC consumes them yet, not published):

| Module | Prefix | Concepts |
|--------|--------|----------|
| `arknet-ddd_parked.ttl` | `arkddd:` | ContextMap, ContextRelationship, RelationshipType, plus the full tactical-DDD layer (Aggregate, Entity, ValueObject, DomainEvent, Command, Query, DomainService, Repository, Factory, Policy, Saga, Invariant); no BC, no Java reference. Shares its namespace with the active `arknet-ddd.ttl` above (BoundedContext, Domain, Subdomain) |
| `arknet-process.ttl` | `arkproc:` | Process, Step, State, StateTransition, BusinessRule, Outcome -- `Actor` moved to `arknet-actor.ttl` above; the rest is unconsumed and `arkproc:Step` predates/duplicates the live `arkreq:Step` (different properties entirely) -- reconcile before reviving |
| `arknet-architecture_parked.ttl` | `arkarch:` | Architecture, ArchitectureDescription, Stakeholder, Concern, Viewpoint, View -- the rest of the ISO-42010 architecture description; no BC, no Java reference. Shares its namespace with the active `arknet-architecture.ttl` above (ADR) |
| `arknet-privacy.ttl` | `arkpriv:` | DataCategory, LegalBasis, ProcessingPurpose, DataSubjectRight, TechnicalMeasure, PrivacyImpactAssessment |

Not created yet (no file at all, just an intended future namespace):

| Module | Prefix | Concepts |
|--------|--------|----------|
| `arknet-tech.ttl` | `arktech:` | Service, Container, API, Database (planned) |

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
