package de.hauschel.arknet.cli;

import de.hauschel.arknet.core.ModelLoader;
import de.hauschel.arknet.projection.Projection;
import de.hauschel.arknet.projection.ProjectionRegistry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "generate", description = "Generate projections from an architecture model")
public class GenerateCommand implements Callable<Integer> {

    @Option(names = "--input", required = true, description = "Path to the Turtle model file (.ttl)")
    private Path modelFile;

    @Option(names = "--projection", required = true, description = "Projection name (e.g. context-map, glossary, bounded-context-canvas, explorer)")
    private String projectionName;

    @Option(names = "--format", defaultValue = "html", description = "Output format: html or pdf")
    private String format;

    @Option(names = "--output", defaultValue = "docs", description = "Output directory")
    private Path outputDir;

    @Override
    public Integer call() {
        try {
            var registry = new ProjectionRegistry();
            Projection projection = registry.get(projectionName);
            if (projection == null) {
                System.err.printf("Unknown projection: %s%n", projectionName);
                System.err.printf("Available: %s%n", String.join(", ", registry.available()));
                return 1;
            }

            var loader = new ModelLoader();
            var repo = loader.loadModel(modelFile);

            projection.generate(repo, outputDir, format);
            repo.shutDown();

            System.out.printf("Generated %s in %s%n", projectionName, outputDir);
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }
}
