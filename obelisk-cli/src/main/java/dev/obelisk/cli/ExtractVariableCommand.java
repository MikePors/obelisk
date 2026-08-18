package dev.obelisk.cli;

import dev.obelisk.core.ProjectContext;
import dev.obelisk.core.RefactorException;
import dev.obelisk.core.refactor.ExtractVariableRefactor;
import dev.obelisk.core.refactor.RefactorResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "extract-variable",
        description = "Extract a single, precisely-addressed expression into a new 'var' local variable.")
public class ExtractVariableCommand implements Callable<Integer> {

    @Option(names = "--project-dir", description = "Path to the Maven project root (default: current directory)")
    private Path projectDir = Path.of(".");

    @Option(names = "--file", required = true, description = "Path to the source file (absolute, or relative to --project-dir)")
    private Path file;

    @Option(names = "--start-line", required = true, description = "1-based line of the expression's first character")
    private int startLine;

    @Option(names = "--start-column", required = true, description = "1-based column of the expression's first character")
    private int startColumn;

    @Option(names = "--end-line", required = true, description = "1-based line of the expression's last character")
    private int endLine;

    @Option(names = "--end-column", required = true, description = "1-based column of the expression's last character")
    private int endColumn;

    @Option(names = "--name", required = true, description = "Name for the new local variable")
    private String name;

    @Option(names = "--dry-run", description = "Show the diff without writing changes to disk")
    private boolean dryRun;

    @Override
    public Integer call() {
        try (ProjectContext ctx = ProjectContext.load(projectDir.toAbsolutePath().normalize())) {
            RefactorResult result = ExtractVariableRefactor.run(ctx, file, startLine, startColumn, endLine,
                    endColumn, name, !dryRun);

            for (Path f : result.diffs().keySet().stream().sorted().toList()) {
                System.out.println(result.diffs().get(f));
            }

            for (String warning : result.warnings()) {
                System.err.println("warning: " + warning);
            }

            if (result.isEmpty()) {
                System.out.println("No changes.");
            } else {
                System.out.println((dryRun ? "[dry run] " : "") + "Extracted to new local variable '" + name + "'");
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
