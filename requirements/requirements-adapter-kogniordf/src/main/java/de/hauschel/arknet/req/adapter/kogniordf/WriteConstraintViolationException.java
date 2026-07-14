package de.hauschel.arknet.req.adapter.kogniordf;

/**
 * Thrown by the {@link ShaclWriteGate} when a candidate requirement graph violates the
 * requirements SHACL shapes and is therefore rejected before persistence.
 *
 * <p>Carries a human-readable aggregation of the violated SHACL results (focus node, path,
 * message) - never the RDF-technology-specific {@code ShaclReport} itself, so that this
 * exception stays a plain, adapter-external signal.</p>
 */
public class WriteConstraintViolationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    WriteConstraintViolationException(String message) {
        super(message);
    }
}
