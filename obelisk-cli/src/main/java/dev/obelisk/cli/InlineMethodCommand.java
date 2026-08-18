package dev.obelisk.cli;

import dev.obelisk.core.ProjectContext;
import dev.obelisk.core.RefactorException;
import dev.obelisk.core.refactor.InlineMethodRefactor;
import dev.obelisk.core.refactor.RefactorResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "inline-method",
        description = "Inline a static or private single-expression method into every call site, then remove it.")
public class InlineMethodCommand implements Callable<Integer> {

    @Option(names = "--project-dir", description = "Path to the Maven project root (default: current directory)")
    private Path projectDir = Path.of(".");

    @Option(names = "--class", required = true, description = "Simple name of the class declaring the method")
    private String className;

    @Option(names = "--from", required = true, description = "Name of the method to inline")
    private String from;

    @Option(names = "--params", description = "Comma-separated parameter type names (simple or fully-qualified) "
            + "to disambiguate an overloaded method, e.g. 'String,int'. Only needed if --from is overloaded on "
            + "the target class.")
    private String params;

    @Option(names = "--dry-run", description = "Show the diff without writing changes to disk")
    private boolean dryRun;

    @Override
    public Integer call() {
        try (ProjectContext ctx = ProjectContext.load(projectDir.toAbsolutePath().normalize())) {
            RefactorResult result = InlineMethodRefactor.run(ctx, className, from, params, !dryRun);

            for (Path file : result.diffs().keySet().stream().sorted().toList()) {
                System.out.println(result.diffs().get(file));
            }

            for (String warning : result.warnings()) {
                System.err.println("warning: " + warning);
            }

            if (result.isEmpty()) {
                System.out.println("No changes: nothing resolved back to " + className + "." + from + "()");
            } else {
                System.out.println((dryRun ? "[dry run] " : "") + "Inlined " + className + "." + from + "() into "
                        + result.changedFiles().size() + " file(s) and removed the declaration");
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
