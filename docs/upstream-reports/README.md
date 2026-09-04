# Upstream bug reports

Drafts of bug reports for defects found in a dependency this project relies on, not filed
automatically by any tooling here. Filing them with the upstream tracker is a deliberate,
human action - this directory only holds the prepared text (minimal reproduction, expected vs.
observed behaviour, version), so nobody has to reconstruct it from a shape comment and a git log
when it is finally time to submit it, or to re-derive it from scratch after the next dependency
upgrade to check whether the underlying defect is still present.

- **[rdf4j-shaclsail-qualifiedmaxcount-two-focus-nodes.md](rdf4j-shaclsail-qualifiedmaxcount-two-focus-nodes.md)**
  -- RDF4J's `ShaclSail` misfires `sh:qualifiedValueShape`/`sh:qualifiedMaxCount` when the
  validated data graph carries a second focus node of the same shape's target class
  (kogn-io/arknet#376, kogn-io/arknet#378). The workaround lives in
  `arknet-ontology/src/main/resources/architecture-shapes.ttl`, at
  `ashapes:ADR-consideredOption-atMostOneChosen`.
