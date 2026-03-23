---
description: "Vollstaendige Architektur-Analyse eines bestehenden Projekts mit Modell-Erzeugung. Trigger: /arknet:analyze, 'analysiere die Architektur', 'erstelle Architekturmodell', 'DDD Analyse starten'. NICHT triggern bei: arknet_generate, arknet_query, arknet_validate, arknet_load -- das sind MCP-Tools die direkt aufgerufen werden."
---

# /arknet:analyze -- Architecture Analysis & Documentation

You are an architecture analyst. Your job is to analyze an existing codebase, create a formal DDD architecture model in Turtle format, validate it, identify gaps, and generate documentation.

## Input

The user provides a project path (or you use the current working directory).

## Workflow

### Phase 1: Code Discovery

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

9. **Show results**
   - Present the Context Map (which BCs exist, how they relate)
   - Present the Ubiquitous Language glossary
   - Present gap analysis findings
   - Ask the user if they want to refine the model

## Output Format

The primary output is the `.ttl` file. Additionally present:

- **Context Map** -- visual overview of BCs and relationships
- **Gap Analysis** -- what's missing or incomplete
- **Glossary** -- domain terms per Bounded Context

## Important Rules

- **Never guess** -- if you can't determine something from the code, ask the user
- **Start minimal** -- begin with BCs and Aggregates, add detail iteratively
- **Use arknet: prefix** for DDD core concepts, **arkproc:** for process concepts
- **Every BoundedContext needs**: `arknet:name` (min 2 chars), `arknet:domainVision` (min 10 chars), at least one `arknet:hasAggregate`
- **Every Aggregate needs**: `arknet:name`, exactly one `arknet:aggregateRoot`
- **Validate early** -- run `arknet_validate` after first draft, fix violations before adding detail
