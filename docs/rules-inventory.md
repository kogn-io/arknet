# arknet -- Rules inventory of the model resources

This document lists every rule the typed cores of the four model contexts enforce on a resource
and names the place where `submit` enforces it instead: a shape, a rule datum in the `arkres:`
vocabulary, a guarantee of the engine itself, or a documented exception. It is the acceptance
inventory of kogn-io/arknet#742.

Sources of the rule list:

- the checks in the constructors of the domain records (`Requirement`, `UseCase`, `Step`, `Term`,
  `BoundedContext`, `ContextRelationship`, `Adr`, `Consequence`, `ConsideredOption`, `Actor`,
  `Role` and the code types);
- the mandatory joins and type filters of the typed read paths (`KognioRdf*Repository`): what a
  typed tool does not see or fails to read;
- the checks the application services make beyond the records: status, transitions, before/after
  comparisons, delete guards, values the server sets.

How to read a row:

- **Kind** is one of
  - `shape` -- an end-state shape; **Place** names it,
  - `rule data` -- an `arkres:` rule datum on a property shape, applied by the `submit` engine;
    **Place** names the shape carrying it,
  - `engine` -- a guarantee of the `submit` engine itself, for a case no document can produce
    (codes, IRIs, types, child positions); no shape exists for it,
  - `exception` -- not enforced by `submit`, with the reason in section 6.
- Section 7 lists the intended differences between a typed core and `submit` for the comparison
  test of kogn-io/arknet#744, section 8 what looks like a rule but is none.
- **Pinned by** names the test that submits a violating document and sees it rejected with a
  message naming the rule, as `Class#method`; for the one parametrised class
  `EdgeAndRequiredFieldRulesRealStoreTest#aDocumentBreakingAnEdgeOrRequiredFieldRuleIsRejectedWithTheField`
  the case names are given in parentheses. `RulesInventoryNamesShippedShapesTest` also fails when a
  test named here does not exist. Unqualified classes live in
  `arknet-resource-access-adapter-kogniordf`; `app:` marks `arknet-app`, `core:` marks
  `arknet-resource-access-core`.
- Shape names use the prefixes of the shapes files. `RulesInventoryNamesShippedShapesTest` in
  `arknet-architecture-tests` fails when a shape named here is not shipped.

## Counts

| Kind | Rules in sections 1-5 |
|---|---|
| `shape` | 58 |
| `rule data` | 15 |
| `engine` | 32 |
| `exception` | 4 |
| **total** | **109** |

Section 6 holds 9 documented exceptions (the 4 rows above and 5 without a row of their own),
section 7 holds 7 intended differences, section 8 holds 10 items that are no rule.

## 1. Product & Requirements (BC-1)

Shapes in `requirements-shapes.ttl`; tests in `ProductRequirementsRulesRealStoreTest` unless named.

| Rule | Core source | Kind | Place | Pinned by |
|---|---|---|---|---|
| A requirement carries a status | read path joins `arkreq:status` | `shape` | `rshapes:Requirement-status` (`sh:minCount 1`) | `#theStatusShapeRequiresExactlyOneOfProposedAndAccepted` |
| The status is Proposed or Accepted | `RequirementStatus`, read path throws on other values | `shape` | `rshapes:Requirement-status` (`sh:in`) | `#theStatusShapeRequiresExactlyOneOfProposedAndAccepted` |
| A requirement starts Proposed | `RequirementService` | `rule data` | `rshapes:Requirement-status` (`arkres:initialStatus`) | `#aRequirementStartsProposedWithoutAStatusInTheDocument`, `#aRequirementCreatedProposedIsAcceptedAndOneCreatedAcceptedIsRejected` |
| Proposed <-> Accepted, nothing else | `Requirement#accept`/`Requirement#propose` | `rule data` | `rshapes:Requirement-status` (`arkres:transition`) | `#aRequirementMovesBetweenProposedAndAccepted`, `#aRequirementCannotMoveToAStatusNoTransitionLeadsTo`, `#clearingTheStatusOfARequirementIsRejectedByTheEngine` |
| A requirement is deletable in both statuses | `RequirementService#delete` (no status gate) | `rule data` | `rshapes:Requirement-status` (`arkres:deletableIn`) | `#aRequirementIsDeletableInEveryStatus` |
| Priority is one of MoSCoW | read path throws on another IRI | `shape` | `rshapes:Requirement-priority` | `#aRequirementPriorityOutsideMoscowIsRejected` |
| Only a non-functional requirement has a quality category | `Requirement` constructor | `shape` | `rshapes:FR-noQualityCategory` | `#aFunctionalRequirementWithAQualityCategoryIsRejected` |
| The quality category stays as created | `RequirementService#update` | `rule data` | `rshapes:NFR-qualityCategory` (`arkres:immutable`) | `#theQualityCategoryOfANonFunctionalRequirementStaysAsTheCreateWroteIt`, `#aQualityCategoryCannotBeAddedAfterTheCreateLeftItOut` |
| The requirement type stays as created | `RequirementService#update` | `engine` | `rdf:type` is not writable by an update | `#theTypeOfARequirementAndOfAConstraintStays`, `EngineGuaranteesRealStoreTest#clearingTheTypeIsRejected` |
| A requirement is typed FR or NFR, not only `arkreq:Requirement` | read path filters on the two subtypes | `engine` | a create names one resource type | `EngineGuaranteesRealStoreTest#aResourceTypedOnlyWithAnAbstractSuperclassIsNoResourceType` |
| Title is not blank | `Requirement` constructor | `shape` | `rshapes:Requirement-title-notBlank` | `#aTextOfOnlyWhitespaceIsRejected`, `#everyKindOfBlankCharacterCountsAsWhitespace` |
| Description is not blank | `Requirement` constructor | `shape` | `rshapes:Requirement-description-notBlank` | `#aTextOfOnlyWhitespaceIsRejected` |
| Rationale is not blank | `Requirement` constructor | `shape` | `rshapes:Requirement-rationale-notBlank` | `#aTextOfOnlyWhitespaceIsRejected` |
| At least one acceptance criterion | `Requirement` constructor | `shape` | `rshapes:Requirement-acceptanceCriterion` | `EdgeAndRequiredFieldRulesRealStoreTest` (requirement without criterion) |
| Criterion text is not blank | read path drops a blank criterion | `shape` | `rshapes:AcceptanceCriterion-text-notBlank` | `#aTextOfOnlyWhitespaceIsRejected` |
| Criteria of one requirement have distinct texts | `Requirement` constructor | `shape` | `rshapes:Requirement-acceptanceCriterion-textsDiffer` | `#twoAcceptanceCriteriaWithTheSameTextAreRejected`, `#twoCriteriaSharingAStringUnderAnyTagsAreRejected`, `#anAcceptanceCriterionAddedWithATextTheRequirementAlreadyHasIsRejected` |
| Criteria positions run from 1 without gaps or duplicates | `Requirement` constructor, read path joins the position | `engine` | the engine numbers children | `EngineGuaranteesRealStoreTest#positionsADocumentGivesOnlyOrderTheChildrenAndTheStoreNumbersThemFromOne`, `EngineGuaranteesRealStoreTest#childrenOfACreateWithoutOrWithTheSamePositionAreRejected` |
| Constraint title and statement are not blank | `Constraint` constructor | `shape` | `rshapes:Constraint-title-notBlank`, `rshapes:Constraint-statement-notBlank` | `#aTextOfOnlyWhitespaceIsRejected` |
| A constraint is typed TCON, BCON or RCON, not only `arkreq:Constraint` | read path filters on the three subtypes | `engine` | a create names one resource type | `EngineGuaranteesRealStoreTest#aResourceTypedOnlyWithAnAbstractSuperclassIsNoResourceType` |
| The constraint type stays as created | `ConstraintService#update` | `engine` | `rdf:type` is not writable by an update | `#theTypeOfARequirementAndOfAConstraintStays` |
| A use case has a goal | `UseCase` constructor, read path joins it | `shape` | `rshapes:UseCase-goal` | `#aUseCaseWithoutAGoalIsRejected` |
| The goal is not blank | `UseCase` constructor | `shape` | `rshapes:UseCase-goal-notBlank` | `#aUseCaseTitleGoalOrStepTextOfOnlyWhitespaceIsRejected` |
| The title is not blank | `UseCase` constructor | `shape` | `rshapes:UseCase-title-notBlank` | `#aUseCaseTitleGoalOrStepTextOfOnlyWhitespaceIsRejected` |
| A use case has a main step | `UseCase` constructor, read path joins it | `shape` | `rshapes:UseCase-mainStep` | `#aUseCaseWithoutAMainStepIsRejected` |
| Step text is not blank | `Step` constructor | `shape` | `rshapes:Step-text-notBlank` | `#aUseCaseTitleGoalOrStepTextOfOnlyWhitespaceIsRejected` |
| Step positions run from 1 without gaps | `UseCase` constructor, read path | `engine` | the engine numbers children | `EngineGuaranteesRealStoreTest#positionsADocumentGivesOnlyOrderTheChildrenAndTheStoreNumbersThemFromOne` |
| A use case has exactly one primary role | `UseCase` constructor (`MissingPrimaryRoleException`) | `shape` | `rshapes:UseCase-primaryRole` (`sh:minCount 1`, `sh:maxCount 1`) | `EdgeAndRequiredFieldRulesRealStoreTest` (use case without primary role) |
| A primary role is a role | `UseCaseService` resolves `ROLE-N` | `shape` | `rshapes:UseCase-primaryRole` | `EdgeAndRequiredFieldRulesRealStoreTest` (use case with a term as primary role) |
| A supporting role is a role | `UseCaseService` resolves `ROLE-N` | `shape` | `rshapes:UseCase-supportingRole` | `#aSupportingRoleThatIsNoRoleIsRejected` |
| Included and extended use cases are use cases | `UseCaseService` resolves `UCn` | `shape` | `rshapes:UseCase-includesUseCase`, `rshapes:UseCase-extendsUseCase` | `#anIncludedOrExtendedResourceThatIsNoUseCaseIsRejected` |
| `satisfies` is exactly what the main steps realise | the adapter derives `satisfies` from the steps | `shape` | `rshapes:UseCase-satisfies-isWhatTheStepsRealise` | `#aUseCaseWhoseStepRealisesARequirementItDoesNotSatisfyIsRejected`, `#aUseCaseSatisfyingARequirementNoStepRealisesIsRejected`, `#aStepAddedWithoutTheMatchingSatisfiesIsRejectedAndAcceptedWithIt` |
| A step realises a requirement | `UseCaseService` resolves the code | `shape` | `rshapes:Step-realises` | `EdgeAndRequiredFieldRulesRealStoreTest` (step realises a term) |
| A term a requirement or use case uses is a term | services resolve `TERM-N` | `shape` | `rshapes:Requirement-usesTerm`, `rshapes:UseCase-usesTerm` | `EdgeAndRequiredFieldRulesRealStoreTest` (requirement uses a requirement as term; use case uses a requirement as term), `SubmitRealStoreTest#aReferenceToNoResourceIsRejected` |
| A constraint an element is constrained by is a constraint | services resolve the code | `shape` | `rshapes:Requirement-constrainedBy`, `rshapes:UseCase-constrainedBy` | `EdgeAndRequiredFieldRulesRealStoreTest` (requirement constrained by a term; use case constrained by a term) |

## 2. Domain Modelling (BC-2)

Shapes in `ul-shapes.ttl` and `arknet-shapes.ttl`; tests in `DomainModellingRulesRealStoreTest` unless named.

| Rule | Core source | Kind | Place | Pinned by |
|---|---|---|---|---|
| A term has a definition | `Term` constructor, read path joins it | `shape` | `ulshapes:Term-definition` (`sh:minCount 1`) | `#aTermWithoutADefinitionOrWithABlankTextIsRejected` |
| The definition is not empty or blank | `Term` constructor | `shape` | `ulshapes:Term-definition` (`sh:minLength`, `sh:pattern`) | `#aTermWithoutADefinitionOrWithABlankTextIsRejected`, `#aTermDefinitionAndABoundedContextNameOfOnlyBlankCharactersAreRejected` |
| The preferred label is not blank | `Term` constructor | `shape` | `ulshapes:Term-prefLabel` | `#aTermWithoutADefinitionOrWithABlankTextIsRejected` |
| One word under every language tag | `KognioRdfTermRepository` (label mismatch) | `shape` | `ulshapes:Term-prefLabel-oneWord` | `#aTermLabelledWithAnotherWordInAnotherLanguageIsRejected` |
| A term is not its own broader term | `Term` constructor | `shape` | `ulshapes:Term-broader-notSelf` | `#aTermReferencingItselfIsRejected` |
| A term is not related to itself | `Term` constructor | `shape` | `ulshapes:Term-related-notSelf` | `#aTermReferencingItselfIsRejected` |
| No cycle along `skos:broader` | `KognioRdfTermRepository` (term cycle) | `shape` | `ulshapes:Term-broader-noCycle` | `#aBroaderChainIsAcceptedButClosingItIntoACycleIsRejected` |
| Broader and related terms are terms | services resolve `TERM-N` | `shape` | `ulshapes:Term-broader`, `ulshapes:Term-related` | `EdgeAndRequiredFieldRulesRealStoreTest` (term broader than a requirement; term related to a requirement) |
| A term carries its code, not blank | `TermCode`, read path joins `dcterms:identifier` | `engine` | the store assigns the code | `EngineGuaranteesRealStoreTest#aCreateWritingItsOwnIdentifierIsRejected`, `EngineGuaranteesRealStoreTest#clearingTheIdentifierIsRejected` |
| A term is stored under an IRI | read path filters on IRIs | `engine` | the store mints every resource IRI | `EngineGuaranteesRealStoreTest#anEdgeToAResourceWrittenInlineAsABlankNodeIsRejected` |
| Bounded context name and domain vision are not blank | `BoundedContext` constructor | `shape` | `shapes:BoundedContext-name`, `shapes:BoundedContext-domainVision` | `#aBoundedContextWithABlankOrMistypedTextIsRejected` |
| The owning team is one plain, non-blank string | `BoundedContext` constructor, read path casts to a literal | `shape` | `shapes:BoundedContext-ownedBy-text` | `#aBoundedContextWithABlankOrMistypedTextIsRejected` |
| Language terms are terms | `BoundedContextService` resolves `TERM-N` | `shape` | `ulshapes:BoundedContext-ubiquitousLanguageTerm`, `ulshapes:BoundedContext-delimitsTerm` | `EdgeAndRequiredFieldRulesRealStoreTest` (context speaks a requirement; context delimits a requirement) |
| A term is not both a language term and a delimited term | `BoundedContext` constructor | `shape` | `shapes:BoundedContext-delimitsTerm-disjoint` | `EdgeAndRequiredFieldRulesRealStoreTest` (context speaks and delimits one term) |
| A bounded context carries its code | `BoundedContextCode`, read path joins it | `engine` | the store assigns the code | `EngineGuaranteesRealStoreTest#aCreateWritingItsOwnIdentifierIsRejected` |
| The subdomain type is a known one | read path throws on another IRI | `shape` | `shapes:Subdomain-subdomainType` | `#aSubdomainOfAnUnknownTypeIsRejectedByTheShapes` (against the shapes) |
| The subdomain classification is writable | `KognioRdfBoundedContextRepository` writes it as a fixed child | `exception` | section 6 | -- |
| A context relationship joins two different contexts | `ContextRelationship` constructor | `shape` | `shapes:ContextRelationship-upstream` (`sh:disjoint`) | `#aContextRelationshipFromAContextToItselfIsRejected` |
| Upstream and downstream are bounded contexts, one each | `ContextRelationship` constructor | `shape` | `shapes:ContextRelationship-upstream`, `shapes:ContextRelationship-downstream` | `EdgeAndRequiredFieldRulesRealStoreTest` (relationship without upstream; relationship without downstream; relationship with two upstream contexts; relationship with two downstream contexts; relationship upstream of a term) |
| The relationship type is one of eight | `RelationshipType` | `shape` | `shapes:ContextRelationship-relationshipType` | `EdgeAndRequiredFieldRulesRealStoreTest` (relationship of an unknown type) |
| A context relationship is stored under an IRI | read path casts the subject to an IRI | `engine` | the store mints every resource IRI; no inline resource | section 5 (every resource is stored under its own IRI) |
| Every term belongs to the one glossary scheme | `KognioRdfTermRepository` writes `skos:inScheme` | `exception` | section 6 | -- |

## 3. Architecture & Decisions (BC-3)

Shapes in `architecture-shapes.ttl`; tests in `ArchitectureDecisionsRulesRealStoreTest` unless named.

| Rule | Core source | Kind | Place | Pinned by |
|---|---|---|---|---|
| Name is not empty or blank | `Adr` constructor | `shape` | `ashapes:ADR-name` | `#aDocumentBreakingAnEndStateRuleIsRejectedWithTheRule`, `#everyTextTheCoreCallsBlankIsRejected` |
| Context and decision are not blank | `Adr` constructor | `shape` | `ashapes:ADR-context`, `ashapes:ADR-decision` | `#aDocumentBreakingAnEndStateRuleIsRejectedWithTheRule`, `#everyTextTheCoreCallsBlankIsRejected` |
| Consequence statement is not blank | the read path drops a blank one | `shape` | `ashapes:Consequence-statement` | `#everyTextTheCoreCallsBlankIsRejected` |
| Option name and rationale are not blank | `ConsideredOption` constructor | `shape` | `ashapes:ConsideredOption-name`, `ashapes:ConsideredOption-rationale` | `#everyTextTheCoreCallsBlankIsRejected` |
| Consequence type and option outcome are given | `Consequence`, `ConsideredOption` constructors | `shape` | `ashapes:Consequence-type`, `ashapes:ConsideredOption-outcome` | `EdgeAndRequiredFieldRulesRealStoreTest` (consequence without type; option without outcome) |
| At most one chosen option | `Adr` constructor | `shape` | `ashapes:ADR-consideredOption-atMostOneChosen` | `#acceptingNeedsExactlyOneChosenOptionWhenOptionsExist` |
| A record points at itself neither as related nor as successor | `Adr` constructor | `shape` | `ashapes:ADR-noSelfReference` | `#aDocumentBreakingAnEndStateRuleIsRejectedWithTheRule` |
| The decision date is one `xsd:date` | read path turns an unparsable date into null | `shape` | `ashapes:ADR-decisionDate` | `#aDecisionDateTheDocumentNamesIsOneDate` |
| Edges of a record point at a requirement, a bounded context, a term, a record | `AdrService` resolves the codes | `shape` | `ashapes:ADR-addressesRequirement`, `ashapes:ADR-affectsContext`, `ashapes:ADR-usesTerm`, `ashapes:ADR-relatedTo`, `ashapes:ADR-supersededBy` | `EdgeAndRequiredFieldRulesRealStoreTest` (decision addresses a term; decision affects a term; decision uses a requirement as term; decision related to a term; proposed decision superseded by a term) |
| A successor edge goes with the Superseded status | `Adr` constructor | `shape` | `ashapes:ADR-supersededByRequiresSupersededStatus` | `EdgeAndRequiredFieldRulesRealStoreTest` (proposed decision superseded by a decision) |
| Child positions run from 1 without gaps, within the range of an int | `Adr` constructor | `engine` | the engine numbers children; `ashapes:Consequence-position`, `ashapes:ConsideredOption-position` bound the range | `EngineGuaranteesRealStoreTest#positionsADocumentGivesOnlyOrderTheChildrenAndTheStoreNumbersThemFromOne` |
| A record carries its code | `AdrCode`, read path joins it | `engine` | the store assigns the code | `EngineGuaranteesRealStoreTest#aCreateWritingItsOwnIdentifierIsRejected` |
| A record is stored under an IRI | read path filters on IRIs | `engine` | the store mints every resource IRI | section 5 (every resource is stored under its own IRI) |
| A record starts Proposed | `AdrService#add` | `rule data` | `ashapes:ADR-status` (`arkres:initialStatus`) | `#aDecisionStartsProposedAndUndated`, `#aDecisionIsNotCreatedDecided` |
| A Proposed record carries no decision date | `AdrService#add`, nothing leads back to Proposed | `shape` | `ashapes:ADR-proposedUndated` | `#aProposedDecisionIsNotDatedWithoutBeingDecided`, `#aDocumentBreakingAnEndStateRuleIsRejectedWithTheRule` |
| Proposed -> Accepted or Rejected, Accepted -> Deprecated or Superseded, Superseded -> Accepted; Rejected and Deprecated final | `Adr#accept`, `Adr#reject`, `Adr#deprecate`, `Adr#supersededBy`, `Adr#unsupersede` | `rule data` | `ashapes:ADR-status` (`arkres:transition`) | `#aStatusChangesOnlyAlongADeclaredTransition` |
| Accepting needs exactly one chosen option when options exist | `Adr#accept` | `rule data` | condition `ashapes:ADR-oneChosenOption` | `#acceptingNeedsExactlyOneChosenOptionWhenOptionsExist`, `#aProposedDecisionChoosesAnOptionInTheDocumentThatAcceptsIt` |
| Superseding needs the edge to an accepted successor | `Adr#supersededBy`, `AdrService` | `rule data` | condition `ashapes:ADR-supersededByAcceptedDecision` (via `ashapes:ADR-accepted`) | `#supersedingNeedsTheEdgeToAnAcceptedDecision`, `#theSupersessionChainOfTheRealCorpusIsLegal` |
| Undoing a supersession clears the edge | `Adr#unsupersede` | `rule data` | condition `ashapes:ADR-notSuperseded` | `#undoingASupersessionClearsTheEdgeAndKeepsTheDate` |
| The decision date is set when accepting or rejecting, unless given | `AdrService#accept`, `AdrService#reject` | `rule data` | `ashapes:ADR-status` (`arkres:default` with `arkres:today`) | `#decidingWritesTodayUnlessTheDocumentNamesTheDay` |
| An accepted or rejected record keeps a decision date | `AdrService#accept`, `AdrService#reject` | `rule data` | condition `ashapes:ADR-dated` | `#decidingWithAClearedDateIsRejected` |
| Deletable only while Proposed or Accepted | `AdrStatus#isDeletable` | `rule data` | `ashapes:ADR-status` (`arkres:deletableIn`) | `#aDecisionIsDeletedOnlyWhileProposedOrAccepted`, `#aSupersededDecisionIsNotDeleted` |
| Name, context and decision are frozen once decided; a new language is allowed | `Adr#reviseText`, `AdrService` | `rule data` | `ashapes:ADR-status` (`arkres:fieldGroup`, editable in Proposed) | `#theTextOfADecidedRecordTakesOnlyALanguageNewToNameContextAndDecision`, `#aProposedDecisionRewritesItsTextFreely` |
| The decision date is frozen once decided | `Adr#unsupersede` keeps it | `rule data` | `ashapes:ADR-status` (`arkres:fieldGroup`) | `#theDateOfADecidedRecordIsKept`, `#undoingASupersessionClearsTheEdgeAndKeepsTheDate` |
| Consequences and options are append-only once decided; type and outcome of a kept child stay | `Adr#without*`, `#withAppended*`, `#with*Corrections` | `rule data` | `ashapes:ADR-status` (`arkres:fieldGroup` over the owned fields) | `#theChildListsOfADecidedRecordKeepEveryChild`, `#anAcceptedDecisionWithoutAChosenOptionMayGetRejectedOptionsAppended` |
| A kept child of a decided record takes text only in a new language | `Adr#with*Corrections` | `exception` | engine branch untested, section 6 | -- |
| A Superseded record keeps its successor edge | `Adr` constructor, `Adr#supersededBy` | `exception` | section 6 | -- |

## 4. Actor (BC-4)

Shapes in `actor-shapes.ttl`; tests in `ActorRulesRealStoreTest` unless named.

| Rule | Core source | Kind | Place | Pinned by |
|---|---|---|---|---|
| An actor has one of the four concrete types | read path filters on the four subtypes | `shape` | `actshapes:ActorShape` (`sh:or`); `arkproc:Actor` is no resource type | `#anActorTypedOnlyAsTheAbstractSuperclassIsNoResourceType` |
| The actor type stays as created | `Actor` | `engine` | `rdf:type` is not writable by an update | `#theTypeOfAnActorStays` |
| Actor name is not blank | `Actor` constructor | `shape` | `actshapes:Actor-name` | `#aBlankNameIsRejected` |
| Actor description is not empty or blank | `Actor` constructor | `shape` | `actshapes:Actor-description` | `#aBlankDescriptionIsRejected`, `#anEmptyDescriptionIsRejected` |
| Role name is not blank | `Role` constructor | `shape` | `actshapes:Role-name` | `#aBlankNameIsRejected` |
| Role description is not empty or blank | `Role` constructor | `shape` | `actshapes:Role-description` | `#aBlankDescriptionIsRejected`, `#anEmptyDescriptionIsRejected` |
| A role is filled by actors | `RoleService` resolves `ACTOR-N` | `shape` | `actshapes:Role-filledBy` | `#aRoleFilledByAResourceThatIsNoActorIsRejected` |
| Actors and roles carry their code and an IRI | `ActorCode`, `RoleCode`, read paths join and filter on IRIs | `engine` | the store assigns codes and IRIs | `EngineGuaranteesRealStoreTest#aCreateWritingItsOwnIdentifierIsRejected` |

## 5. Across the contexts

Rules every typed core enforces for its own resources, enforced once by the engine for all.

| Rule | Core source | Kind | Place | Pinned by |
|---|---|---|---|---|
| Codes are assigned by the store, never by the caller | every `*Service#add` | `engine` | a create carries no `dcterms:identifier` | `EngineGuaranteesRealStoreTest#aCreateWritingItsOwnIdentifierIsRejected` |
| A code never changes and never disappears | no tool changes a code | `engine` | `dcterms:identifier` is not writable by an update | `EngineGuaranteesRealStoreTest#clearingTheIdentifierIsRejected`, `SubmitRealStoreTest#anUpdateMayNotTouchTheCodeOrAnOwnedField` |
| A deleted code is never assigned again | `CodeCounter` | `engine` | code assignment over live and deleted codes | `SubmitRealStoreTest#aDeletedCodeIsNeverAssignedAgain` |
| A type never changes | no tool changes a type | `engine` | `rdf:type` is not writable by an update | `EngineGuaranteesRealStoreTest#clearingTheTypeIsRejected`, `ProductRequirementsRulesRealStoreTest#theTypeOfARequirementAndOfAConstraintStays` |
| A created resource has exactly one resource type | every `*Service#add` | `engine` | a create names one resource type | `EngineGuaranteesRealStoreTest#aResourceTypedOnlyWithAnAbstractSuperclassIsNoResourceType`, `SubmittedDocumentTest#aCreateNamesANewBlankNodeOfOneResourceType` |
| Every resource is stored under its own IRI | every read path filters or casts to IRIs | `engine` | the store mints IRIs; a resource is never a value written inline | `EngineGuaranteesRealStoreTest#anEdgeToAResourceWrittenInlineAsABlankNodeIsRejected`, `EngineGuaranteesRealStoreTest#anEdgeWithoutANodeKindRuleToAResourceWrittenInlineAsABlankNodeIsRejected`, `core:DocumentApplicationTest#anInlineValueTypedWithAResourceTypeIsRejected` |
| Every owned child is stored under a fragment IRI of its resource | read paths filter or cast child IRIs | `engine` | children named `<resource>#<kind>-<id>` | `EngineGuaranteesRealStoreTest#aCreatedResourceAndItsChildrenAreStoredUnderIris`, `SubmitRealStoreTest#addToAndRemoveFromKeepTheNamesOfTheChildrenThatStay` |
| An owned child has the class of its field and nothing else | read paths read the field's class | `engine` | a new child is typed with its field's class | `SubmitRealStoreTest#aChildAddedWithAnotherOrASecondTypeIsRejected`, `SubmitRealStoreTest#aChildTypedWithAnotherClassThanItsFieldRequiresIsRejected` |
| A position a document gives only orders the new children | records number children from 1 | `engine` | positions are renumbered from 1 on every write | `EngineGuaranteesRealStoreTest#positionsADocumentGivesOnlyOrderTheChildrenAndTheStoreNumbersThemFromOne`, `EngineGuaranteesRealStoreTest#aChildAddedBeyondTheLastPositionIsAppendedRightAfterIt` |
| Every child of a create names its position, no two the same | records reject duplicate positions | `engine` | position check on create | `EngineGuaranteesRealStoreTest#childrenOfACreateWithoutOrWithTheSamePositionAreRejected` |
| An owned field changes only by adding and removing children | typed tools change children one by one | `engine` | an update does not set or clear an owned field | `SubmitRealStoreTest#anUpdateMayNotTouchTheCodeOrAnOwnedField` |
| Only known fields are written | typed tools have fixed parameters | `engine` | unknown predicates are rejected | `SubmitDocumentChecksRealStoreTest#aCreateWritingAFieldOfAnotherTypeIsRejected`, `SubmitDocumentChecksRealStoreTest#anInlineChildWritingAFieldOfItsParentIsRejected`, `SubmitDocumentChecksRealStoreTest#aSetOfAFieldOfAnotherTypeIsRejected`, `SubmitDocumentChecksRealStoreTest#anAddToOfAFieldOfAnotherTypeIsRejected` |
| A resource something still points at is not deleted | the `REFERENCING_PREDICATES` guards of every adapter | `engine` | delete guard over the edges derived from `sh:class` and `rdfs:range` | `SubmitDocumentChecksRealStoreTest#deletingATermARequirementStillUsesIsRejected`, `SubmitDocumentChecksRealStoreTest#deletingARequirementAStepOfAUseCaseRealisesIsRejectedNamingTheStep`, `app:SubmitOnTypedStoreDataTest#rejectsDeletingATypedTermATypedRequirementStillUses` |
| An edge points at an existing resource of the right class | services resolve codes | `shape` | `sh:class` on every edge shape | `SubmitRealStoreTest#aReferenceToNoResourceIsRejected`, `EdgeAndRequiredFieldRulesRealStoreTest` |
| A status changes only by a declared transition, conditions checked at the transition | `Requirement`, `Adr` | `engine` | status mechanism over the rule data | `StatusTransitionsRealStoreTest`, `core:DocumentApplicationStatusTest` |
| Fields frozen by status, immutable fields, defaults at a transition | `Adr`, `RequirementService` | `engine` | before/after comparison over the rule data | `BeforeAfterRulesRealStoreTest`, `core:DocumentApplicationBeforeAfterTest` |
| A document is applied completely or not at all | every tool writes in one transaction | `engine` | one `writeAll` batch per document | `SubmitRealStoreTest#aDocumentWithOneInvalidActivityChangesNothing` |
| Multilingual text carries a language tag | `LanguageTag#resolveWriteLanguage` | `engine` | a literal a document writes to a field with `sh:uniqueLang true` (create, inline child, set, add-to) carries a tag; stored literals are not checked | `EngineGuaranteesRealStoreTest#aTextWrittenWithoutALanguageTagIsRejected`, `EngineGuaranteesRealStoreTest#aTextSetWithoutALanguageTagIsRejected`, `EngineGuaranteesRealStoreTest#aStoredTextWithoutALanguageTagBlocksNeitherItsResourceNorItsNeighbours`, `core:DocumentApplicationTest#aSetOrAddToOfATextFieldWithoutALanguageTagIsRejected` |

## 6. Documented exceptions

| Exception | Reason | Where it goes |
|---|---|---|
| `skos:inScheme` | Only the glossary adapter writes it, no production code reads it, its shape `ulshapes:Term-inScheme` is a warning, and the scheme node is never stored. Terms created through `submit` carry no scheme. | kogn-io/arknet#745 (target vocabulary) |
| Subdomain classification of a bounded context | The typed core keeps it as a single child with a fixed name (`#Subdomain`); the engine knows only ordered child lists, so no document can write it. Its value is still checked: `shapes:Subdomain-subdomainType`. | kogn-io/arknet#745, as a flat field `subdomainType` on the bounded context |
| A Superseded record keeps its successor edge | The core refuses to clear or re-point `supersededBy` of a Superseded record. `submit` allows both without a status change: no transition is taken, so no condition runs, and a field group cannot hold the edge because undoing the supersession must clear it. An end-state shape for "Superseded needs the edge" would trip the typed adapter's peer copies (kogn-io/arknet#359). A record whose edge was cleared drops out of the typed read path (`KognioRdfAdrRepository` skips it with a warning). | kogn-io/arknet#744 / kogn-io/arknet#747 |
| Legacy Proposed records with a decision date | Written only by releases before the date rule (kogn-io/arknet#374). `ashapes:ADR-proposedUndated` rejects every write that leaves such a record Proposed, and in `submit` every document that reaches it, since the engine validates reached neighbours. Ways out: accept, reject, delete or clear the date while Proposed. Pinned on the typed side by `AdrServiceLegacyProposedDateRealStoreTest` (`arknet-adr-adapter-kogniordf`). | kogn-io/arknet#745 (migration) |
| Legacy flat `arkarch:adrConsequences` / `arkarch:adrAlternatives` | No tool writes them; they stay outside the frozen field group. | kogn-io/arknet#745 (migration) |
| A kept child of a decided record takes text only in a new language | The engine compares a kept child's text by language, but no activity changes a child in place (an update on a child IRI is rejected), so the branch is unreachable and untested. The observable rule -- no child of a decided record changes or disappears -- is pinned by `ArchitectureDecisionsRulesRealStoreTest#theChildListsOfADecidedRecordKeepEveryChild`. | test due with a child-update activity |
| Stored literals without a language tag | The cores never write one (`LanguageTag#resolveWriteLanguage` rejects a write without a tag), but older data holds some (constraints, actors and roles written before the tag was mandatory), and a typed write under another tag leaves them in place. `submit` rejects an untagged literal in the document only; a stored one stays and blocks no write, which is why there is no end-state shape for the tag. | kogn-io/arknet#745 (migration of the stored untagged literals), then an end-state shape |
| Conditions see only outgoing edges | A transition condition sees the changed resource and what its outgoing edges reach; a condition over incoming edges would hold wrongly. No shipped condition needs incoming edges. | limit of the engine, stated in `arknet-resource-access/CLAUDE.md` |
| `arkreq:dependsOn`, `arkreq:scopedTo`, `arkddd:hasContext` have no shape | See section 8: not writable through `submit` at all. | -- |

## 7. Intended differences for kogn-io/arknet#744

| Difference | Typed core | `submit` |
|---|---|---|
| Blank text | `String#isBlank()` | the non-blank pattern `[^\s\p{Z}\p{Cc}]` also rejects a text of no-break spaces (U+00A0) |
| Renaming a term | `term_update` without a language renames under every tag at once | a document writes each tag itself; `ulshapes:Term-prefLabel-oneWord` keeps them equal |
| Superseding by a new successor | two calls: accept the successor, then supersede | one document may do both; the condition sees the state after the document |
| Choosing the option when accepting | `adr_update` corrects the outcome, then `adr_set_status` accepts | one document may do both |
| Consequence type and option outcome | `Adr#with*Corrections` lets a Proposed record correct them | the same, through remove and add; frozen outside Proposed by the field group, not by `arkres:immutable` |
| Requirement status | `req_update` never changes it, `req_accept`/`req_propose` do | an update changes it along the two transitions |
| A legacy actor breaking the actor shapes | blocks no role write | blocks every role write that reaches it (neighbour validation) |

## 8. Not rule subjects

| Item | Why not a rule of a resource |
|---|---|
| Not found / not linked errors | answers of a lookup, not a state of a resource; `submit` rejects an unresolvable handle (`SubmitRealStoreTest#aHandleThatNamesNoResourceIsRejected`) |
| Retry after a conflicting commit | transaction behaviour; `SubmitService` retries the whole document (`SubmitRealStoreConcurrencyTest`) |
| Write language resolution | a tool parameter: the typed tools fall back to the project's default language; a document names its tags |
| Placeholder for a requirement without criteria | read-side rendering; the shape requires a criterion |
| `relatedTo` read in both directions | read-side merge of a symmetric property; written in one direction |
| `adr_check` findings | review hints over the corpus, not write rules |
| No duplicate entries in a list (`Requirement`: used terms, constraints; `Term`: related terms; `Adr`: `requireNoDuplicates` over its four reference lists) | RDF set semantics: a graph holds a triple once, so a document cannot store the same edge twice |
| `arkreq:dependsOn` (requirement -> requirement) | Declared in the ontology with `rdfs:range`, read by the model analysis, written by no typed tool. Without a property shape it is no field, so `submit` rejects it as an unknown predicate; a stored edge still blocks deleting its target (`rdfs:range`). Shaping it would open a write path no typed tool has, not express a rule. |
| `arkreq:scopedTo` (requirement -> bounded context) | Same as `arkreq:dependsOn`. |
| `arkddd:hasContext` (domain -> bounded context) | Its domain `arkddd:Domain` is no resource type, so no document creates a holder; the inverse of `arkddd:partOf`, which the typed core keeps on the subdomain child (section 6). A stored edge still blocks deleting its target. |
