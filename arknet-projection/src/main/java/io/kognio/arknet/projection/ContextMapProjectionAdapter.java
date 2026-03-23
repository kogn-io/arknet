package io.kognio.arknet.projection;

import org.eclipse.rdf4j.repository.Repository;

import java.io.IOException;
import java.nio.file.Path;

public class ContextMapProjectionAdapter implements Projection {

    private final ContextMapProjection contextMap = new ContextMapProjection();
    private final AsciiDocConverter converter = new AsciiDocConverter();

    @Override
    public String name() {
        return "context-map";
    }

    @Override
    public String description() {
        return "Context Map with PlantUML diagram (HTML/PDF via AsciiDoc)";
    }

    @Override
    public void generate(Repository repo, Path outputDir, String format) throws IOException {
        String adocContent = contextMap.generate(repo);
        converter.convert(adocContent, outputDir, "context-map", format);
    }
}
