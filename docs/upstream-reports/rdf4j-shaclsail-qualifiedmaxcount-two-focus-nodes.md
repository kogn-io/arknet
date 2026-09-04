# RDF4J ShaclSail: `sh:qualifiedValueShape`/`sh:qualifiedMaxCount` misfires non-deterministically when the transaction touches a second focus node of the same shape's target class

**Status: draft, not yet filed.** Filing this with the [RDF4J issue tracker](https://github.com/eclipse-rdf4j/rdf4j/issues)
is a deliberate action for a human to take, checking first whether a matching issue already
exists there - see kogn-io/arknet#378. This file is the prepared report text.

## Environment

- RDF4J version: **6.0.1** (also reported against 6.0.0 in the originating issue,
  kogn-io/arknet#376)
- Reproduced: 2026-09-04, against the version this project currently depends on
  (`<rdf4j.version>` in `pom.xml`) - **still reproducible**, not fixed by the 6.0.0 -> 6.0.1
  upgrade
- Sail under test: `org.eclipse.rdf4j.sail.shacl.ShaclSail` wrapping an in-memory
  `org.eclipse.rdf4j.sail.memory.MemoryStore`
- Observed failure rate: roughly 60% of runs (18 of 30 in the measurement backing this report),
  matching the "about two thirds" originally measured in kogn-io/arknet#376

## Summary

A `sh:qualifiedValueShape`/`sh:qualifiedMaxCount` constraint on a property shape produces a
validation failure **non-deterministically** - across otherwise-identical, single-threaded,
single-transaction runs - whenever the committed transaction's data contains a **second**
resource of the shape's node shape's `sh:targetClass`, even though:

- that second resource has **zero** values for the constrained path (so it can never itself be
  the reason for a violation), and
- the resource that actually carries the values being counted conforms to the constraint on its
  own (exactly one qualifying value, at or below `sh:qualifiedMaxCount`).

## Minimal shapes graph

```turtle
@prefix sh:   <http://www.w3.org/ns/shacl#> .
@prefix ex:   <http://example.org/> .

ex:ExampleShape
    a sh:NodeShape ;
    sh:targetClass ex:Thing ;
    sh:property ex:atMostOneChosenOption .

ex:atMostOneChosenOption
    a sh:PropertyShape ;
    sh:path ex:hasOption ;
    sh:qualifiedValueShape [ sh:path ex:outcome ; sh:hasValue ex:Chosen ] ;
    sh:qualifiedMaxCount 1 ;
    sh:severity sh:Violation .
```

## Minimal data graph (one transaction)

```turtle
@prefix ex: <http://example.org/> .

# The node actually being validated: two options, exactly one Chosen - conforms on its own.
ex:subject a ex:Thing ;
    ex:hasOption ex:option1 , ex:option2 .
ex:option1 ex:outcome ex:Rejected .
ex:option2 ex:outcome ex:Chosen .

# A second focus node of the SAME target class, unrelated, with NO ex:hasOption at all.
ex:peer a ex:Thing .
```

## Expected behaviour

The transaction commits (`report.conforms() == true`): `ex:subject` has exactly one
`ex:outcome ex:Chosen` value among its `ex:hasOption` values, satisfying
`sh:qualifiedMaxCount 1`; `ex:peer` has no `ex:hasOption` values at all and trivially satisfies
the same constraint (0 &le; 1).

## Observed behaviour

Across repeated, otherwise-identical runs of committing this exact transaction against a fresh
`ShaclSail`, validation **intermittently** reports a violation of `ex:atMostOneChosenOption` -
sometimes against `ex:subject`, even though its own data never changes between runs. Removing
`ex:peer` (the unrelated second focus node with zero `ex:hasOption` values) from the data graph
makes the failure disappear entirely across the same number of repeated runs. The
non-determinism and its dependence on the presence of an unrelated second focus node of the same
target class, rather than on the actual constrained data, is the anomaly being reported - not
merely "a qualified shape can be strict."

## Workaround in use

`kogn-io/arknet` replaced the `sh:qualifiedValueShape`/`sh:qualifiedMaxCount` form with an
equivalent `sh:sparql` `$this`-scoped `SELECT` constraint
(`ashapes:ADR-consideredOption-atMostOneChosen` in
`arknet-ontology/src/main/resources/architecture-shapes.ttl`), which evaluates one focus node at
a time and is unaffected by a sibling focus node of the same shape. See that file's comment and
kogn-io/arknet#376/#378 for the full history, including the concrete production trigger (a
`relatedTo`/`supersededBy` peer's validation-only type-and-mandatory-fields copy landing in the
same transaction as the node actually being written).

## Suspected area

Given the failure depends on a *second, otherwise-unrelated* focus node of the shape's own
target class being present in the same validated transaction, and not on the data actually
reachable via the constrained path, this looks like a scoping/caching defect in how
`ShaclSail`'s qualified-shape evaluation determines or reuses candidate value sets across focus
nodes of the same shape within one transaction, rather than a defect in the qualified-shape
semantics themselves.
