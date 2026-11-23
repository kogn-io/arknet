// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.kogn.rdf.rdf4j.dataset.GraphImportRdf4j;
import io.kogn.rdf.rdf4j.shacl.ShaclValidationRdf4j;
import io.kogn.rdf.shacl.ShaclReport;
import io.kogn.rdf.shacl.Severity;
import io.kogn.rdf.shacl.ValidationOptions;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;
import io.kogn.rdf.terms.vocab.VocabRdf;

class UnifiedGateSpikeTest {

    private static final List<String> SHAPES = List.of("/requirements-shapes.ttl", "/ul-shapes.ttl",
            "/architecture-shapes.ttl", "/actor-shapes.ttl", "/arknet-shapes.ttl");
    private static final List<String> AXIOMS = List.of("/arknet-core.ttl", "/arknet-ddd.ttl", "/arknet-actor.ttl",
            "/arknet-requirements.ttl", "/arknet-architecture.ttl");

    private final SimpleRdf rdf = new SimpleRdf();

    @Test
    void spike() {
        Graph shapes = union(SHAPES);
        Graph axioms = union(AXIOMS);
        Graph fixture = load("/fixtures/every-resource-type.ttl");

        System.out.println("SPIKE axioms alone: " + report(rdf.createGraph(), shapes, axioms));
        System.out.println("SPIKE whole fixture: " + report(fixture, shapes, axioms));

        Set<IRI> roots = fixture.stream(null, VocabRdf.TYPE, null)
                .map(Triple::getSubject).filter(IRI.class::isInstance).map(IRI.class::cast)
                .filter(s -> !s.getIRIString().contains("#")).collect(Collectors.toSet());
        for (IRI root : roots) {
            Graph slice = slice(fixture, root);
            Graph closure = rdf.createGraph();
            Deque<IRI> todo = new ArrayDeque<>(objects(slice, root));
            Set<IRI> seen = new HashSet<>(Set.of(root));
            while (!todo.isEmpty()) {
                IRI next = todo.pop();
                if (!seen.add(next)) {
                    continue;
                }
                Graph s = slice(fixture, next);
                s.stream().forEach(closure::add);
                todo.addAll(objects(s, next));
            }
            Graph typeOnly = rdf.createGraph();
            objects(slice, root).forEach(o -> fixture.stream(o, VocabRdf.TYPE, null).forEach(typeOnly::add));
            Graph data = rdf.createGraph();
            slice.stream().forEach(data::add);
            closure.stream().forEach(data::add);
            Graph data2 = rdf.createGraph();
            slice.stream().forEach(data2::add);
            typeOnly.stream().forEach(data2::add);
            System.out.println("SPIKE " + root.getIRIString().substring(29) + " closure(" + closure.size() + "): "
                    + report(data, shapes, axioms) + " | typeOnly: " + report(data2, shapes, axioms));
        }
    }

    private Set<IRI> objects(Graph slice, IRI root) {
        return slice.stream().map(Triple::getObject).filter(IRI.class::isInstance).map(IRI.class::cast)
                .filter(o -> o.getIRIString().startsWith("https://w3id.org/arknet/model/"))
                .filter(o -> !o.getIRIString().startsWith(root.getIRIString() + "#"))
                .collect(Collectors.toSet());
    }

    private Graph slice(Graph all, IRI root) {
        Graph slice = rdf.createGraph();
        all.stream().filter(t -> t.getSubject() instanceof IRI s
                && (s.equals(root) || s.getIRIString().startsWith(root.getIRIString() + "#")))
                .forEach(slice::add);
        return slice;
    }

    private String report(Graph data, Graph shapes, Graph axioms) {
        Graph merged = rdf.createGraph();
        data.stream().forEach(merged::add);
        axioms.stream().forEach(merged::add);
        ShaclReport report = new ShaclValidationRdf4j().validate(merged, shapes, new ValidationOptions(true));
        if (report.conforms()) {
            return "OK";
        }
        return report.results().stream().filter(r -> r.severity() == Severity.VIOLATION)
                .map(r -> shortName(r.focusNode()) + " " + String.valueOf(r.path()).replaceAll(".*[/#]", ""))
                .collect(Collectors.joining(", ", "FAIL[", "]"));
    }

    private static String shortName(RDFTerm term) {
        if (term == null) {
            return "-";
        }
        String s = term.toString();
        return s.substring(Math.max(0, s.lastIndexOf('/') + 1));
    }

    private Graph union(List<String> resources) {
        Graph union = rdf.createGraph();
        resources.forEach(r -> load(r).stream().forEach(union::add));
        return union;
    }

    private static Graph load(String resource) {
        try (InputStream in = UnifiedGateSpikeTest.class.getResourceAsStream(resource)) {
            return new GraphImportRdf4j().readTurtle(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
