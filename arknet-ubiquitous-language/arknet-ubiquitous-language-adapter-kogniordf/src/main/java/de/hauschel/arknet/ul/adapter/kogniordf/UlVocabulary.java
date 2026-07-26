// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.adapter.kogniordf;

import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabDct;

import de.hauschel.arknet.persistence.ArkreqVocabulary;

/**
 * The RDF vocabulary this out-adapter serialises a glossary term with - the single place both
 * {@link GraphBackedTerm} (which reads and writes these predicates triple by triple) and
 * {@link KognioRdfTermRepository} (which embeds them into SPARQL) name them.
 *
 * <p>Serialisation constants, not domain vocabulary: the core never sees an IRI (opaque
 * identity), which is why these live in the adapter and not in the shared kernel.</p>
 */
final class UlVocabulary {

    static final String SKOS_NAMESPACE = "http://www.w3.org/2004/02/skos/core#";
    static final String ARKPROC_NAMESPACE = "https://w3id.org/arknet/process#";

    static final String TERMS_GRAPH = "https://w3id.org/arknet/model/ubiquitous-language";
    static final String GLOSSARY_SCHEME = "https://w3id.org/arknet/model/glossary";

    static final String CONCEPT_TYPE = ArkreqVocabulary.CONCEPT_TYPE;
    static final String CONCEPT_SCHEME_TYPE = SKOS_NAMESPACE + "ConceptScheme";
    static final String IN_SCHEME_PROPERTY = SKOS_NAMESPACE + "inScheme";
    static final String PREF_LABEL_PROPERTY = SKOS_NAMESPACE + "prefLabel";
    static final String DEFINITION_PROPERTY = SKOS_NAMESPACE + "definition";
    static final String IDENTIFIER_PROPERTY = VocabDct.NAMESPACE + "identifier";
    static final String HUMAN_ACTOR_TYPE = ARKPROC_NAMESPACE + "HumanActor";
    static final String SYSTEM_ACTOR_TYPE = ARKPROC_NAMESPACE + "SystemActor";
    static final String ACTOR_ROLE_PROPERTY = ARKPROC_NAMESPACE + "actorRole";

    private static final RDF RDF_FACTORY = new SimpleRdf();

    static final IRI TERMS_GRAPH_IRI = RDF_FACTORY.createIRI(TERMS_GRAPH);
    static final IRI GLOSSARY_SCHEME_IRI = RDF_FACTORY.createIRI(GLOSSARY_SCHEME);
    static final IRI CONCEPT_TYPE_IRI = RDF_FACTORY.createIRI(CONCEPT_TYPE);
    static final IRI CONCEPT_SCHEME_TYPE_IRI = RDF_FACTORY.createIRI(CONCEPT_SCHEME_TYPE);
    static final IRI IN_SCHEME_IRI = RDF_FACTORY.createIRI(IN_SCHEME_PROPERTY);
    static final IRI PREF_LABEL_IRI = RDF_FACTORY.createIRI(PREF_LABEL_PROPERTY);
    static final IRI DEFINITION_IRI = RDF_FACTORY.createIRI(DEFINITION_PROPERTY);
    static final IRI IDENTIFIER_IRI = RDF_FACTORY.createIRI(IDENTIFIER_PROPERTY);
    static final IRI HUMAN_ACTOR_IRI = RDF_FACTORY.createIRI(HUMAN_ACTOR_TYPE);
    static final IRI SYSTEM_ACTOR_IRI = RDF_FACTORY.createIRI(SYSTEM_ACTOR_TYPE);
    static final IRI ACTOR_ROLE_IRI = RDF_FACTORY.createIRI(ACTOR_ROLE_PROPERTY);

    private UlVocabulary() {
    }
}
