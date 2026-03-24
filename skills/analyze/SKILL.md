---
description: "Vollstaendige Architektur-Analyse eines bestehenden Projekts mit Modell-Erzeugung. Trigger: /arknet:analyze, 'analysiere die Architektur', 'erstelle Architekturmodell', 'DDD Analyse starten'. NICHT triggern bei: arknet_generate, arknet_query, arknet_validate, arknet_load -- das sind MCP-Tools die direkt aufgerufen werden."
---

# /arknet:analyze -- Architecture Analysis & Documentation

You are an architecture analyst. Your job is to analyze an existing codebase, create a formal DDD architecture model in Turtle format, validate it, identify gaps, and generate documentation.

## Input

The user provides a project path (or you use the current working directory).

## Workflow

### Phase 0: Detect Project Type & Available Tools

1. **Check if Java project**
   - Use `Glob` to find `**/pom.xml` or `**/build.gradle`
   - If found: this is a Java project

2. **Check if JDT MCP is available**
   - Try calling `mcp__jdt-mcp__jdt_list_projects`
   - If it responds: JDT is available -> use **JDT Strategy** (Phase 1a)
   - If it fails or is not available: use **Text Strategy** (Phase 1b)

### Phase 1a: Code Discovery with JDT (Java projects with JDT MCP)

JDT provides semantically correct analysis -- prefer this over text search.

1. **Import project into JDT**
   - Call `mcp__jdt-mcp__jdt_import_project` with the project path
   - Call `mcp__jdt-mcp__jdt_get_project_structure` to understand modules

2. **Identify Bounded Contexts**
   - Use `mcp__jdt-mcp__jdt_get_project_structure` for module/package overview
   - Use `mcp__jdt-mcp__jdt_find_annotated_elements` with:
     - `@Configuration`, `@SpringBootApplication` (Spring module boundaries)
     - `@Module`, `@Bounded` (if DDD frameworks are used)
   - Each BC needs: name, domain vision, subdomain type (Core/Supporting/Generic)

3. **Identify Aggregates per Context**
   - Use `mcp__jdt-mcp__jdt_find_annotated_elements` for:
     - `@Entity`, `@Aggregate`, `@AggregateRoot`, `@Document`
     - `@Embeddable`, `@EmbeddedId` (Value Objects)
   - Use `mcp__jdt-mcp__jdt_get_type_hierarchy` to find entity inheritance trees
   - Use `mcp__jdt-mcp__jdt_find_implementations` for Repository interfaces -> identifies Aggregate Roots
   - Each Aggregate needs: name, aggregate root, entities, value objects

4. **Identify Commands, Events, Queries**
   - Use `mcp__jdt-mcp__jdt_find_annotated_elements` for:
     - `@CommandHandler`, `@EventHandler`, `@QueryHandler`
     - `@EventSourcingHandler`, `@SagaEventHandler`
   - Use `mcp__jdt-mcp__jdt_find_type` for classes matching `*Command`, `*Event`, `*Query`
   - Use `mcp__jdt-mcp__jdt_find_callers` to trace command -> handler -> event chains

5. **Identify Queries / Read Use Cases**
   - Use `mcp__jdt-mcp__jdt_find_type` for classes matching `*Query`, `*Read*`, `*Retrieval*`, `*List*`, `*Find*`
   - Look for `@QueryHandler`, `@GetMapping`, read-only service methods
   - Model as `arknet:Query` with `arknet:name`
   - Assign to the Aggregate they read from via `arknet:hasQuery` or add to the BC

6. **Identify State Machines**
   - Use `mcp__jdt-mcp__jdt_find_type` for enums ending in `*Status`, `*State`, `*Phase`, `*Stage`
   - Read enum values -> these become `arkproc:State` instances
   - Look for transition methods (e.g. `activate()`, `suspend()`, `approve()`) that change state
   - Look for guard conditions in transition methods (if-checks before state change)
   - Model as: `arkproc:State` (with `arkproc:stateOf` -> Aggregate), `arkproc:StateTransition` (with `arkproc:fromState`, `arkproc:toState`, optional `arkproc:transitionGuard`)
   - Set `arkproc:initialState` and `arkproc:terminalState` on the Aggregate

7. **Identify Event Consumers**
   - Use `mcp__jdt-mcp__jdt_find_annotated_elements` for:
     - `@JmsListener`, `@KafkaListener`, `@RabbitListener`, `@EventListener`, `@TransactionalEventListener`
   - Use `mcp__jdt-mcp__jdt_find_type` for classes matching `*Listener`, `*Handler`, `*Consumer`, `*Subscriber`
   - Use `mcp__jdt-mcp__jdt_find_callers` on Event classes to find all consumers
   - Model as: `arknet:publishedTo` on the DomainEvent (pointing to the consumer's BC)
   - If a listener triggers a command in another BC, model as `arknet:Policy` with `arknet:reactsTo` -> Event, `arknet:issues` -> Command

8. **Identify Context Relationships**
   - Use `mcp__jdt-mcp__jdt_find_references` on shared types to detect cross-context coupling
   - Use `mcp__jdt-mcp__jdt_find_annotated_elements` for:
     - `@FeignClient`, `@KafkaListener`, `@RabbitListener` (integration points)
   - Use `mcp__jdt-mcp__jdt_find_callers` on API/client classes to trace inter-context calls
   - Classify: CustomerSupplier, ACL, OHS, PublishedLanguage, SharedKernel, etc.

9. **Identify Domain Terms (Ubiquitous Language)**
   - Extract class names, method names, field names from domain packages
   - Use `mcp__jdt-mcp__jdt_parse_java_file` on key domain classes
   - Domain terms = class names of Entities, VOs, Commands, Events (cleaned to business language)

### Phase 1b: Code Discovery with Text Search (Fallback)

Use this when JDT is not available, or for non-Java projects.

1. **Identify project structure**
   - Use `Glob` to find build files (pom.xml, build.gradle, package.json) and understand the module structure
   - Use `Glob` to find source directories and package hierarchy
   - Spawn an `architecture-analyst` agent if the codebase is large

2. **Identify Bounded Contexts**
   - Look for top-level packages or modules that represent distinct business areas
   - Look for Spring `@Configuration` classes, module-info.java, or clear package boundaries
   - Each BC needs: name, domain vision (one sentence: what does it do and why), subdomain type (Core/Supporting/Generic)

3. **Identify Aggregates per Context**
   - Look for classes with `@Entity`, `@Aggregate`, `@AggregateRoot` annotations
   - Look for JPA entities, repository interfaces, event classes
   - Each Aggregate needs: name, aggregate root, entities, value objects

4. **Identify Commands, Events, Queries**
   - Search for classes ending in Command, Event, Query
   - Search for handler methods (`@CommandHandler`, `@EventHandler`, `@EventSourcingHandler`)
   - Search for Spring `@Service` methods that represent use cases

5. **Identify Queries / Read Use Cases**
   - Search for classes matching `*Query`, `*Read*`, `*Retrieval*`, `*List*`, `*Find*`
   - Search for `@GetMapping`, read-only service methods
   - Model as `arknet:Query`

6. **Identify State Machines**
   - Search for enums ending in `*Status`, `*State`, `*Phase`, `*Stage`
   - Read enum values -> `arkproc:State` instances
   - Look for methods that change state (transition methods)
   - Model: `arkproc:State`, `arkproc:StateTransition`, `arkproc:initialState`, `arkproc:terminalState`

7. **Identify Event Consumers**
   - Search for `@JmsListener`, `@KafkaListener`, `@RabbitListener`, `@EventListener`, `@TransactionalEventListener`
   - Search for classes matching `*Listener`, `*Handler`, `*Consumer`
   - Trace which Events they consume and which Commands they issue
   - Model: `arknet:publishedTo` on Events, `arknet:Policy` for reactive listeners

8. **Identify Context Relationships**
   - Look for inter-module dependencies (imports across packages)
   - Look for API clients, Feign clients, message consumers/producers
   - Classify: CustomerSupplier, ACL, OHS, PublishedLanguage, SharedKernel, etc.

### Phase 2: Model Creation

9. **Generate Turtle model**
   - Write a `.ttl` file using arknet ontology namespaces:
     ```turtle
     @prefix :        <https://example.com/model#> .
     @prefix arknet:  <https://w3id.org/arknet/core#> .
     @prefix arkproc: <https://w3id.org/arknet/process#> .
     ```
   - Include ALL discovered elements:
     - BCs, Aggregates, Entities, VOs
     - Commands AND Queries (both are first-class citizens)
     - Events with `arknet:publishedTo` for each consumer BC
     - State Machines (`arkproc:State`, `arkproc:StateTransition`)
     - Policies (event -> command reactive chains)
     - Context Map relationships
     - `arknet:ubiquitousLanguageTerm` for domain terms
   - Save to `{project}/architecture-model.ttl`

### Phase 3: Validation & Iterative Fix

10. **Validate the model**
    - Call `arknet_validate` with the generated .ttl file
    - If violations exist: fix them in the .ttl and re-validate
    - Repeat until validation passes (max 3 iterations)

11. **Load and run gap analysis**
    - Call `arknet_load` to load the validated model
    - Call `arknet_query` with gap analysis queries:
      - `Q09` -- Processes without failure outcome
      - `Q10` -- Orphaned domain events (no consumer)
      - `Q12` -- Dead commands (no process)
      - `Q13` -- Aggregates without state machine
      - `Q19` -- Ubiquitous Language glossary

12. **Fix gaps automatically where possible**
    - **Q13 (no state machine)**: Go back to the code, look for state enums/fields in these specific Aggregates. If found, add `arkproc:State` and `arkproc:StateTransition` triples. If no state found in code, skip (not every Aggregate has a state machine).
    - **Q10 (orphaned events)**: Go back to the code, search for listeners/consumers of these specific events. If found, add `arknet:publishedTo` triples.
    - **Q12 (dead commands)**: Check if commands are wired to handlers/processes. If handlers exist but were missed, add the link.
    - After fixes: re-write the .ttl, re-validate, re-load, re-query
    - Report remaining gaps to the user (these are genuine gaps in the code, not analysis gaps)

### Phase 4: Documentation

13. **Generate projections**
    - Call `arknet_generate` with projection `glossary` for UL overview
    - Call `arknet_generate` with projection `bounded-context-canvas` for BC details
    - Call `arknet_generate` with projection `context-map` for visual overview
    - Present generated files to the user

14. **Show results**
    - Present the Context Map (which BCs exist, how they relate)
    - Present the Ubiquitous Language glossary
    - Present gap analysis findings (only genuine gaps, not analysis failures)
    - Ask the user if they want to refine the model

## Output Format

The primary output is the `.ttl` file. Additionally present:

- **Context Map** -- visual overview of BCs and relationships
- **Bounded Context Canvas** -- detail view per BC with UL, Aggregates, Commands, Events
- **Glossary** -- domain terms per Bounded Context
- **Gap Analysis** -- what's genuinely missing in the code (not what the analyzer missed)

## Important Rules

- **Never guess** -- if you can't determine something from the code, ask the user
- **Start minimal** -- begin with BCs and Aggregates, add detail iteratively
- **Use arknet: prefix** for DDD core concepts, **arkproc:** for process concepts
- **Every BoundedContext needs**: `arknet:name` (min 2 chars), `arknet:domainVision` (min 10 chars), at least one `arknet:hasAggregate`
- **Every Aggregate needs**: `arknet:name`, exactly one `arknet:aggregateRoot`
- **Commands AND Queries** -- both are first-class. A read use case is an `arknet:Query`, not just a Command
- **State Machines** -- look for status/state enums. If an Aggregate has states, model them as `arkproc:State` + `arkproc:StateTransition`
- **Event Consumers** -- every DomainEvent should have `arknet:publishedTo`. Look for listeners, handlers, subscribers
- **Validate early, fix immediately** -- run `arknet_validate` after first draft, fix violations before moving on. Do not just report gaps -- close them by going back to the code
- **JDT first** -- for Java projects, always try JDT MCP before falling back to text search
- **Gap Analysis = code gaps, not analyzer gaps** -- if Q13 reports "Aggregate without state machine", first check the code. Only report it as a gap if the code genuinely has no state machine for that Aggregate
