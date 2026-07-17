---
description: "Relentless requirements-interview skill for arknet -- elicits FR/NFR (req_add), use cases (uc_add) and glossary terms (term_add) in dialogue, until a shared, testable understanding is reached, and only then writes it in. Two entry points: greenfield (an idea/wish from the user -> interview) and brownfield (attach an existing project to arknet -- code delivers questions, never answers: 'was this intentional, grown, or accidental?'). Trigger (also DE, since the user may phrase it in German): /arknet:req-interview, 'elicit a requirement', 'new requirement/use case/glossary term', 'interview me about X', 'attach this project to arknet', 'interrogate the existing codebase', 'review the requirements/use cases/glossary relentlessly', 'are the requirements complete/consistent'; DE: 'erhebe ein Requirement', 'neue Anforderung', 'pruefe die Requirements unerbittlich'. NOT for HOW/architecture (use /arknet:adr for that), not for plain listing without an interview (use req_list/uc_list/term_list directly for that)."
---

# /arknet:req-interview -- Elicit Requirements, Use Cases and Glossary

A **functional** requirements analyst for arknet. Job: the **WHAT & WHY** --
complete, unambiguous, testable requirements, use cases and glossary terms that
together form a sensible system. Not technology/architecture (that is the ADRs'
job, see `/arknet:adr`) -- except where a technical fact helps you judge
**functional intent**.

Writes against arknet's store tools, not against markdown tables -- `req_add`/
`uc_add`/`term_add` instead of lines in a file.

## Language

- **Conversation follows the user** -- do not force a fixed language.
- **Artifacts in German.** arknet convention (ontology `rdfs:comment`, ADRs,
  README are German). `title`, `description`, `label`, use-case text etc. go
  into the store in German, even if the conversation was partly in English.

## Two entry points, one protocol

- **Greenfield** -- an idea/wish comes from the user, the interview interrogates
  it until a testable "done when" is reached.
- **Brownfield** -- an existing project is attached to arknet. Code delivers
  **questions, never answers**.

### Brownfield: order (signal strength, not artifact hierarchy)

1. **Glossary first.** Class names/packages/aggregates ARE the ubiquitous
   language of the codebase -- directly comparable to what the business side
   says. Actor candidates (roles, external systems) also surface here
   (`term_add` with `actorKind`).
2. **Use cases next.** Controllers/MCP tools/CLI commands are actor entry
   points -- the second-strongest signal.
3. **FRs last and thin.** Weakest signal; an FR derived from code is always
   just a conversation starter, never a finished answer.

### Brownfield: code delivers questions, never answers (core rule)

**Never** "the code does X, therefore FR: X" (reverse-engineering requirements
out of technical decisions). The same guardrail already applies to ADRs ->
requirements (a technical decision taken does not by itself justify a
requirement); for brownfield code the rationalisation risk is even higher,
because code is more concrete and suggestive than an ADR.

Instead: translate observed behaviour into a question. Pattern:

> "I see X in the code -- was that intentional, grown, or accidental?"

Give your own assessment along with it (see interrogation protocol below), but
the decision -- intentional / grown / accidental -- stays with the user.

## Context discipline -- read, don't guess

- **Existing requirements/use cases/terms are the given baseline, not a
  cage.** Read `req_list`, `uc_list`, `term_list` before every interview and
  treat them as current truth -- *but the user may change, re-scope or drop
  any of it at any time.* When new intent contradicts an existing
  requirement, surface the conflict and let the user decide; do not silently
  keep the old one.
- **NEVER GUESS -> ALWAYS LOOK IT UP** (arknet CLAUDE.md). If a fact is
  knowable from the repo, the store, or research, resolve it yourself instead
  of asking. Only **scope, priority and shape** decisions belong to the
  user -- put each of those to them, one at a time.

## The three artifacts and their tools

| Artifact | Create | Read | Change |
|---|---|---|---|
| Requirement (FR/NFR) | `req_add` | `req_get`, `req_list` | `req_set_status`, `req_link_term` |
| Use case | `uc_add` | `uc_get`, `uc_list` | (no update tool -- create anew) |
| Glossary term | `term_add` | `term_get`, `term_list` | (no update tool -- create anew) |

### Glossary terms: `term_add(label, definition, actorKind?, actorRole?)`

- `label` (required) -- `skos:prefLabel`.
- `definition` (required).
- `actorKind` (optional) -- `HUMAN` | `SYSTEM`. Sets the actor facet (the same
  concept additionally becomes `arkproc:HumanActor`/`SystemActor`) -- needed
  if the term is later going to appear as `primaryActor`/`supportingActors`
  in a use case.
- `actorRole` (optional) -- free text, only meaningful together with
  `actorKind`.
- Result: `TERM-n` code.

**Ordering consequence:** a use case references actors by label -- the actor
terms must therefore exist **before** `uc_add`.

### Requirements: `req_add(title, description, type, priority?, motivatedBy?, qualityCategory?)`

- `title` (required) -- short summary.
- `description` (required) -- the normative statement ("The system shall
  ..."). Must be testable on its own (see checklist below) -- and, for now,
  also carries the done-when, see the gap noted directly below.
- `type` (required) -- `FUNCTIONAL` | `NON_FUNCTIONAL`.
- `priority` (optional, MoSCoW) -- `MUST_HAVE` | `SHOULD_HAVE` |
  `COULD_HAVE` | `WONT_HAVE`.
- `motivatedBy` (optional) -- IRI of an `arkreq:Goal`.
- `qualityCategory` (optional, **only** for `NON_FUNCTIONAL`) -- free text,
  e.g. "performance", "security".
- Result: `FR-n`/`NFR-n` code.
- `req_set_status(id, status)` -- `status`: only `PROPOSED` | `ACCEPTED`
  (the lifecycle enum is deliberately MVP-minimal; `Rejected`/`Deprecated`
  exist as allowed values in the SHACL shape but not (yet) in the domain
  enum/tool -- see "Interview interim state" below, which needs no reject
  status for exactly this reason).
- `req_link_term(reqId, termId)` -- links a requirement to a glossary term
  (`arkreq:usesTerm`). After every new domain term in the requirement text,
  check: does a term already exist for it? If not, `term_add` first, then
  `req_link_term`.

### Known gap: no dedicated done-when field (#91)

The ontology defines `arkreq:acceptanceCriterion` ("testable criterion, done
when ..."), but neither `req_add` nor the SHACL shape know it (yet) -- see
issue #91. Until that is fixed: write the testable criterion as **part of
`description`**, e.g. as a closing sentence "Done when: ...". Do not invent a
separate field or a bespoke convention that #91 would later have to unpick --
just carry it in the normative statement.

### Use cases: `uc_add(title, goal, primaryActor, steps, scope?, trigger?, supportingActors?, precondition?, postcondition?, extensions?)`

Coarse-grained write: **one** `uc_add` call creates the complete use case
(unlike requirements, there is no update tool -- a changed use case is
created anew).

- `title`, `goal` (required) -- goal-in-context.
- `primaryActor` (required) -- **label** of an existing actor term (see
  above: must already exist via `term_add ... actorKind=...`; an unknown
  label is rejected didactically, never silently created).
- `steps` (required, at least 1) -- list of `{position, text, realises?}`:
  `position` 1-based and gapless, `text` the step description, `realises`
  optionally a list of requirement codes (`FR-n`/`NFR-n`) that this step
  fulfils.
- `scope`, `trigger`, `supportingActors` (list of actor labels),
  `precondition`, `postcondition`, `extensions` (list of free-text
  alternative/exception-flow lines) -- all optional.
- Result: `UCn` code.

arknet already resolves `primaryActor`/`supportingActors` and
`steps[].realises` **schema-independently and with didactic rejection of
unknown/ambiguous references** -- that rigor is already wired **into the
tool itself**. The skill therefore does not need to invent this discipline,
only observe the write order (terms before use cases, requirements before
`realises` references).

## The interrogation -- protocol

This is the core of the skill. **Interrogate relentlessly until you and the
user share a complete, testable understanding. Only then write it in.** Two
entry points (see above), same protocol:

- **Intake** -- a new requirement/use case/term arrives. Interrogate *it*,
  then integrate.
- **Full-set audit** -- the user asks for a review of the whole register
  ("review the requirements", "review the glossary relentlessly", "are they
  complete/consistent"). This is the **default reading** of any
  "review/check" phrasing -- do not collapse it into a quick summary. Walk
  every requirement/use case/term systematically, one at a time, and
  interrogate the user relentlessly on the gaps you find (missing
  scenarios/actors/edge cases, conflicts, untestable descriptions,
  unspecified failure behaviour).

**Hold the whole set in view -- always.** "One at a time" governs *question
sequencing*, never *analytical scope*. A requirement judged in isolation is
worthless: its most important defects -- **consistency** (conflict with
another), **completeness** (a gap relative to the whole system),
**dependencies** (what it presupposes/affects), **duplication** -- only
surface against the *entire* set. So even in intake mode, read
`req_list`/`uc_list`/`term_list` first and check the new item against the
set; never treat a single requirement as a closed world.

Protocol (the user's standing "relentless" instruction, treat as binding):

- Work through **one requirement/use case/term at a time**, and ask questions
  **one at a time**, waiting for the answer before the next. Several
  questions at once is confusing. (This is pacing -- not a licence to lose
  track of the rest of the set.)
- With **every** question, give **your own assessment or recommended
  answer**, so the user can decide from an informed position rather than
  from a blank prompt.
- Resolve by research/context/existing docs whatever *can* be resolved that
  way. Reserve questions for genuine **scope/priority/shape** decisions --
  those are the user's.
- **Do not** produce a final summary or a settled version until the user
  confirms that all open points are resolved.

### Checklist per requirement

First block: the SOPHIST/Rupp **linguistic defect** filter (the concrete
engine, not a vague "is it ambiguous?"):

- **Passive voice / missing actor** -- "is validated", "gets classified" ->
  *by whom/what?* Name the active subject (the system? a specific step? an
  external service?).
- **Nominalisation** -- a noun that hides a whole process ("the
  classification", "the merge") -> unfold it into steps, inputs, outcome.
- **Incomplete comparative** -- "faster", "better", "higher quality" -> *than
  what, measured how?* Turn it into something a "done when" can check, or
  drop it.
- **Universal quantifier** -- "all", "every", "never", "always" -> is it
  really exceptionless? Hunt the counter-case.
- **Incompletely specified process word/condition** -- "on error, abort",
  "if needed" -> *which errors, then exactly what? which condition, decided
  by whom?*

Then the requirement-quality attributes (ISO/IEC/IEEE 29148):

- **Completeness** -- missing scenarios, edge cases, actors, or
  empty/failure/timeout paths?
- **Unambiguity** -- can this be read in more than one way?
- **Consistency** -- conflict with another requirement/use case/term?
- **Testability** -- is there an objective "done when" in `description` (see
  gap #91)? If you cannot write one, elicitation is not finished.
- **Dependencies** -- what does it presuppose/affect (which other
  FRs/UCs/terms)?
- **Non-functional aspects** -- performance, security, scalability,
  **failure behaviour** considered (check a new FR against existing NFRs)?

### Checklist per use case

Cockburn completeness:

- **Trigger** clear? **Actor** (primary + supporting) correct and existing
  as a term?
- **Goal-in-context** in one sentence, no technical how.
- **Main flow** (`steps`) gaplessly numbered, each step a testable system
  state transition?
- **Alternative/exception flows** (`extensions`) -- empty run, partial
  failure, timeout, user abort considered?
- **`realises` link** -- does at least one step fulfil an existing FR/NFR?
  If the link is entirely missing, ask whether a requirement is missing or
  the use case stands on its own.

### Checklist per glossary term

- Unambiguous against existing terms (read `term_list` first) -- no hidden
  synonym or homonym?
- Definition precise enough that two people understand the same thing?
- Does the term need an actor facet (`actorKind`/`actorRole`) because it will
  later appear as an actor in a use case?

## Writing it in only happens after that

Once a requirement/use case/term is settled with the user:

- Order by dependency, not by elicitation order: terms (including actors)
  first, then requirements, then use cases (which reference both).
- Domain terms in a requirement's text that have no term yet: `term_add`
  first, then `req_link_term`.
- After writing, report crisply **what changed and which code**
  (`FR-3`, `UC2`, `TERM-5`) -- not a full restatement of the content.

### Ripple check after every written change

A settled decision rarely stays local. **After every written change, before
moving to the next item, check whether it ripples** into the rest of the
set. Does the new wording now conflict with, duplicate, or leave a gap in
another FR/NFR/UC/term?

**Current state: manual.** The dedicated traversal tools
(`trace_matrix`/`orphan_check`/`impact_analysis`) do not exist yet -- until
then, ripple check means: re-read `req_list`/`uc_list`/`term_list` after
writing and check against the set you are holding in your head. That works
for a small register but weakens as the graph grows. Once those tools exist,
use `impact_analysis` instead of manual re-reading.

- **No ripple** -> state it in one line, continue the walk.
- **Ripple found** -> name the affected codes and the nature of the impact,
  fold the fix into the current pass (or queue it explicitly) -- never
  silently leave the set inconsistent.
- **Ripple could invalidate already-audited items** -> that is a
  **scope/priority decision for the user**, not the skill: surface it as a
  decision -- e.g. "FR4 adjusted; this touches FR7 and UC1 which we already
  cleared -- restart the review from the top, or patch just those two and
  carry on?" -- and follow the user's choice. Never restart (or decline to
  restart) unilaterally.

## Interview interim state: "origin unclear"

A brownfield interview can surface "nobody remembers why this is the way it
is". This is **not** modelled as its own requirement status -- a requirement
without a testable done-when would fail every full-set audit, which would be
a permanent special case in the register (and `RequirementStatus` only knows
`PROPOSED`/`ACCEPTED` anyway, see above). Instead it stays an interview
**interim state** (conversation/todo, not in the store) until it reaches one
of two outcomes:

- **Actually wanted** -> create a real requirement with a real done-when
  (same path as greenfield, the code was only the conversation starter).
- **Not wanted/dead** -> do not create a requirement. No delete tool
  needed -- it never existed in the store.

## Scope boundary

- **HOW does not belong here.** Architecture, technology, pipeline wiring ->
  `/arknet:adr`. If the user drifts into HOW: name it and offer the handoff
  to the ADR skill, keep this conversation on WHAT & WHY.
- Use cases belong here (Cockburn flows, linked to requirements via
  `realises`). User stories do not -- arknet has no separate role layer that
  would justify an "as X I want" framing; it has actors (terms with
  `actorKind`) and flows.
