package de.hauschel.arknet.req.adapter.kogniordf;

import java.util.List;
import java.util.Optional;

import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.WorkspaceId;

/**
 * Out-adapter: {@link RequirementRepository} backed by the kognio-rdf substrate
 * ({@code io.kogn.rdf}, embeddable RDF dataset).
 *
 * <p><strong>Scaffold stub.</strong> No RDF wiring yet - the methods throw
 * {@link UnsupportedOperationException}. When implemented, this adapter will map
 * {@link Requirement} to/from RDF triples via the kognio-rdf ports
 * (GraphStore / SparqlUpdate / DatasetTx).</p>
 *
 * <p><strong>WorkspaceId (local, single-user).</strong> This adapter runs against
 * exactly one embedded dataset, so {@link WorkspaceId} is accepted but currently
 * treated as an implicit default - it is not yet used to select among several
 * datasets. A future remote/team adapter (against kognio-memory) will use it as
 * the mandatory routing key to address one of several workspaces/projects.</p>
 *
 * <p><strong>SHACL write-gate (deferred, "Weg 2b").</strong> Validation on write
 * is intentionally NOT wired here. kognio-rdf does not yet expose a standalone,
 * technology-neutral ShaclValidation port (tracked as kogn-io/rdf-core#3). Until
 * that is released we do not depend on {@code rdf4j-shacl} directly, to avoid
 * leaking RDF4J into arknet. TODO(kogn-io/rdf-core#3): wire the SHACL write-gate
 * once the standalone validation port ships.</p>
 */
public class KognioRdfRequirementRepository implements RequirementRepository {

    @Override
    public void save(WorkspaceId workspaceId, Requirement requirement) {
        throw new UnsupportedOperationException(
                "scaffold: kognio-rdf persistence not yet implemented");
    }

    @Override
    public Optional<Requirement> findById(WorkspaceId workspaceId, RequirementId id) {
        throw new UnsupportedOperationException(
                "scaffold: kognio-rdf persistence not yet implemented");
    }

    @Override
    public List<Requirement> findAll(WorkspaceId workspaceId) {
        throw new UnsupportedOperationException(
                "scaffold: kognio-rdf persistence not yet implemented");
    }
}
