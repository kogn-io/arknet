package de.hauschel.arknet.core;

import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;

import java.util.ArrayList;
import java.util.List;

public class SparqlExecutor {

    public List<BindingSet> select(Repository repo, String sparql) {
        var results = new ArrayList<BindingSet>();
        try (var conn = repo.getConnection()) {
            var query = conn.prepareTupleQuery(sparql);
            try (TupleQueryResult result = query.evaluate()) {
                while (result.hasNext()) {
                    results.add(result.next());
                }
            }
        }
        return results;
    }
}
