package io.kognio.arknet.projection;

import org.eclipse.rdf4j.repository.Repository;

import java.io.IOException;
import java.nio.file.Path;

public interface Projection {

    String name();

    String description();

    void generate(Repository repo, Path outputDir, String format) throws IOException;
}
