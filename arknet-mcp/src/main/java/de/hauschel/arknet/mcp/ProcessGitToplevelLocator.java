package de.hauschel.arknet.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@link GitToplevelLocator} that shells out to {@code git rev-parse --show-toplevel}.
 *
 * <p>Runs the command with {@code dir} as its working directory and returns the
 * reported top-level path. Any failure mode - {@code dir} not inside a git working
 * tree (non-zero exit), git not installed ({@link IOException} on start), or the
 * command not finishing within {@value #TIMEOUT_SECONDS} seconds - is mapped to
 * {@link Optional#empty()} so that workspace resolution degrades gracefully to a
 * directory-name fallback.</p>
 */
public final class ProcessGitToplevelLocator implements GitToplevelLocator {

    private static final long TIMEOUT_SECONDS = 5;

    @Override
    public Optional<Path> toplevelOf(Path dir) {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--show-toplevel")
                    .directory(dir.toFile())
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            if (process.exitValue() != 0 || output.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(Path.of(output));
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
