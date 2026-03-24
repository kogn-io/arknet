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

5. **Identify Context Relationships**
   - Use `mcp__jdt-mcp__jdt_find_references` on shared types to detect cross-context coupling
   - Use `mcp__jdt-mcp__jdt_find_annotated_elements` for:
     - `@FeignClient`, `@KafkaListener`, `@RabbitListener` (integration points)
   - Use `mcp__jdt-mcp__jdt_find_callers` on API/client classes to trace inter-context calls
   - Classify: CustomerSupplier, ACL, OHS, PublishedLanguage, SharedKernel, etc.

6. **Identify Domain Terms (Ubiquitous Language)**
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

5. **Identify Context Relationships**
   - Look for inter-module dependencies (imports across packages)
   - Look for API clients, Feign clients, message consumers/producers
   - Classify: CustomerSupplier, ACL, OHS, PublishedLanguage, SharedKernel, etc.

### Phase 2: Model Creation

6. **Generate Turtle model**
   - Write a `.ttl` file using arknet ontology namespaces:
     ```turtle
     @prefix :        <https://example.com/model#> .
     @prefix arknet:  <https://w3id.org/arknet/core#> .
     @prefix arkproc: <https://w3id.org/arknet/process#> .
     ```
   - Include all discovered BCs, Aggregates, Entities, VOs, Commands, Events
   - Include Context Map relationships
   - Add `arknet:ubiquitousLanguageTerm` for domain terms found in code
   - Save to `{project}/architecture-model.ttl`

### Phase 3: Validation & Gap Analysis

7. **Validate the model**
   - Call `arknet_validate` with the generated .ttl file
   - Show violations and warnings to the user
   - Fix violations iteratively (missing names, missing aggregates, etc.)

8. **Load and query**
   - Call `arknet_load` to load the validated model
   - Call `arknet_query` with gap analysis queries:
     - `Q09` -- Processes without failure outcome
     - `Q10` -- Orphaned domain events
     - `Q12` -- Dead commands
     - `Q13` -- Aggregates without state machine
     - `Q19` -- Ubiquitous Language glossary
   - Present findings to the user

### Phase 4: Documentation

9. **Generate projections**
   - Call `arknet_generate` with projection `glossary` for UL overview
   - Call `arknet_generate` with projection `bounded-context-canvas` for BC details
   - Call `arknet_generate` with projection `context-map` for visual overview
   - Present generated files to the user

10. **Show results**
    - Present the Context Map (which BCs exist, how they relate)
    - Present the Ubiquitous Language glossary
    - Present gap analysis findings
    - Ask the user if they want to refine the model

## Output Format

The primary output is the `.ttl` file. Additionally present:

- **Context Map** -- visual overview of BCs and relationships
- **Bounded Context Canvas** -- detail view per BC with UL, Aggregates, Commands, Events
- **Glossary** -- domain terms per Bounded Context
- **Gap Analysis** -- what's missing or incomplete

## Important Rules

- **Never guess** -- if you can't determine something from the code, ask the user
- **Start minimal** -- begin with BCs and Aggregates, add detail iteratively
- **Use arknet: prefix** for DDD core concepts, **arkproc:** for process concepts
- **Every BoundedContext needs**: `arknet:name` (min 2 chars), `arknet:domainVision` (min 10 chars), at least one `arknet:hasAggregate`
- **Every Aggregate needs**: `arknet:name`, exactly one `arknet:aggregateRoot`
- **Validate early** -- run `arknet_validate` after first draft, fix violations before adding detail
- **JDT first** -- for Java projects, always try JDT MCP before falling back to text search
