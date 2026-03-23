package io.kognio.arknet.cli;

import io.kognio.arknet.core.ModelLoader;
import io.kognio.arknet.projection.AsciiDocConverter;
import io.kognio.arknet.projection.ContextMapProjection;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "generate", description = "Generate projections from an architecture model")
public class GenerateCommand implements Callable<Integer> {

    @Option(names = "--input", required = true, description = "Path to the Turtle model file (.ttl)")
    private Path modelFile;

    @Option(names = "--format", defaultValue = "html", description = "Output format: html or pdf")
    private String format;

    @Option(names = "--output", defaultValue = "docs", description = "Output directory")
    private Path outputDir;

    @Override
    public Integer call() {
        try {
            var loader = new ModelLoader();
            var repo = loader.loadModel(modelFile);

            var projection = new ContextMapProjection();
            String adocContent = projection.generate(repo);

            var converter = new AsciiDocConverter();
            converter.convert(adocContent, outputDir, "context-map", format);

            repo.shutDown();

            System.out.printf("Generated context-map.%s in %s%n", format, outputDir);
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }
}
