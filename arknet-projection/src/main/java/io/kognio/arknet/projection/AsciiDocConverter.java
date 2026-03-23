package io.kognio.arknet.projection;

import org.asciidoctor.Asciidoctor;
import org.asciidoctor.Options;
import org.asciidoctor.SafeMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class AsciiDocConverter {

    public void convert(String adocContent, Path outputDir, String baseName, String format) throws IOException {
        Files.createDirectories(outputDir);

        Path adocFile = outputDir.resolve(baseName + ".adoc");
        Files.writeString(adocFile, adocContent);

        String backend = "pdf".equals(format) ? "pdf" : "html5";

        try (Asciidoctor asciidoctor = Asciidoctor.Factory.create()) {
            asciidoctor.requireLibrary("asciidoctor-diagram");

            Options options = Options.builder()
                    .backend(backend)
                    .safe(SafeMode.UNSAFE)
                    .toDir(outputDir.toFile())
                    .build();

            asciidoctor.convertFile(adocFile.toFile(), options);
        }
    }
}
