package io.kognio.arknet.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "arknet",
        description = "Architecture Knowledge Net – DDD-Architekturmodelle, die Maschinen verstehen.",
        mixinStandardHelpOptions = true,
        version = "arknet 0.1.0",
        subcommands = {
                ValidateCommand.class,
                GenerateCommand.class
        }
)
public class ArknetCommand implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new ArknetCommand()).execute(args);
        System.exit(exitCode);
    }
}
