package dev.obelisk.cli;

import dev.obelisk.core.ProjectContext;
import dev.obelisk.core.RefactorException;
import dev.obelisk.core.refactor.RefactorResult;
import dev.obelisk.core.refactor.RenameFieldRefactor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "rename-field", description = "Rename a field and every read/write obelisk can resolve back to it.")
public class RenameFieldCommand implements Callable<Integer> {

    @Option(names = "--project-dir", description = "Path to the Maven project root (default: current directory)")
    private Path projectDir = Path.of(".");

    @Option(names = "--class", required = true, description = "Simple name of the class declaring the field")
    private String className;

    @Option(names = "--from", required = true, description = "Current field name")
    private String from;

    @Option(names = "--to", required = true, description = "New field name")
    private String to;

    @Option(names = "--dry-run", description = "Show the diff without writing changes to disk")
    private boolean dryRun;

    @Override
    public Integer call() {
        try (ProjectContext ctx = ProjectContext.load(projectDir.toAbsolutePath().normalize())) {
            RefactorResult result = RenameFieldRefactor.run(ctx, className, from, to, !dryRun);

            for (Path file : result.diffs().keySet().stream().sorted().toList()) {
                System.out.println(result.diffs().get(file));
            }

            for (String warning : result.warnings()) {
                System.err.println("warning: " + warning);
            }

            if (result.isEmpty()) {
                System.out.println("No changes: nothing resolved back to " + className + "." + from);
            } else {
                System.out.println((dryRun ? "[dry run] " : "") + "Changed " + result.changedFiles().size()
                        + " file(s) renaming " + className + "." + from + " -> " + to);
            }
            return 0;
        } catch (RefactorException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            System.err.println("error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return 1;
        }
    }
}
