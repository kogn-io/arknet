# Generated store snapshot

Everything in this directory (`arknet.trig`, `report.html`) is **generated**,
not hand-written. It lets a repository visitor, a contributor before their
first store setup, and the build environment see arknet's own architecture
decisions (and the rest of its own store data) without a running arknet MCP
daemon ([issue #415](https://github.com/kogn-io/arknet/issues/415)).

- **Never a source of truth.** The store is the only place arknet's own
  decisions are written -- see [CONTRIBUTING.md](../../CONTRIBUTING.md). Never
  hand-edit these files, and never use them as a merge base -- a text merge on
  a `.trig` file can be syntactically valid and semantically wrong.
- **`arknet.trig`** -- a full backup dump of this project's store (all six
  bounded contexts, plus the provenance trail), produced by the `arknet` MCP
  tool `project_export` (`projectOnly=true`). Its `project-identity` graph is
  stripped before it lands here: that graph carries the local, machine-specific
  anchor value (e.g. a filesystem path) of whoever ran the export, which has
  no place in a public repository. Everything else is exported unchanged --
  including the `export-metadata` graph every dump ends with (issue #194):
  which arknet version wrote it, when that version was built, when the export
  ran, and the `owl:versionInfo` of each shipped ontology module. Those are
  server facts, not machine-specific ones, and they are what lets a reader of
  this file years from now know which vocabulary it was written against.
- **`report.html`** -- the same self-contained HTML report `store_overview`
  produces, fetched via the daemon's `GET /report` endpoint. Already free of
  the `project-identity` graph on its own (`StoreReader.HIDDEN_GRAPHS`).

## Regenerating

Requires the shared arknet MCP daemon running locally with this project's
data loaded (see the root `README.md`).

1. Call the `project_export` MCP tool with `projectOnly=true`; note the
   `.trig` path it reports.
2. `scripts/export-store-docs.sh <that-path>` -- copies the filtered dump and
   fetches the report into this directory.
3. Review the diff (see below) and commit if it reflects real changes.

Empirically the store's own graphs are byte-identical across repeated exports
of an unchanged store (RDF4J's TriG writer orders deterministically), so a
diff there reflects an actual store change, not serialization noise -- useful
for review, though nothing relies on that: the dump is a product of the store,
never a merge basis (see `CONTRIBUTING.md`). The trailing `export-metadata`
graph is the one exception and always differs: it states when this export ran,
which is the point of it.
