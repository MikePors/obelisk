package dev.obelisk.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "obelisk",
        mixinStandardHelpOptions = true,
        version = "obelisk 0.1.0",
        description = "Structural refactoring for Java projects.",
        subcommands = {RenameMethodCommand.class, RenameClassCommand.class, RenameFieldCommand.class,
                ExtractVariableCommand.class}
)
public class ObeliskCli implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new ObeliskCli()).execute(args);
        System.exit(exitCode);
    }
}
