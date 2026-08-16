package dev.obelisk.cli;

import dev.obelisk.core.ProjectContext;
import dev.obelisk.core.RefactorException;
import dev.obelisk.core.refactor.RefactorResult;
import dev.obelisk.core.refactor.RenameMethodRefactor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "rename-method", description = "Rename a method and every call site obelisk can resolve back to it.")
public class RenameMethodCommand implements Callable<Integer> {

    @Option(names = "--project-dir", description = "Path to the Maven project root (default: current directory)")
    private Path projectDir = Path.of(".");

    @Option(names = "--class", required = true, description = "Simple name of the class declaring the method")
    private String className;

    @Option(names = "--from", required = true, description = "Current method name")
    private String from;

    @Option(names = "--to", required = true, description = "New method name")
    private String to;

    @Option(names = "--dry-run", description = "Show the diff without writing changes to disk")
    private boolean dryRun;

    @Override
    public Integer call() {
        try {
            ProjectContext ctx = ProjectContext.load(projectDir.toAbsolutePath().normalize());
            RefactorResult result = RenameMethodRefactor.run(ctx, className, from, to, !dryRun);

            if (result.isEmpty()) {
                System.out.println("No changes: nothing resolved back to " + className + "." + from + "()");
                return 0;
            }

            for (String file : result.diffs().keySet().stream().map(Object::toString).sorted().toList()) {
                System.out.println(result.diffs().get(Path.of(file)));
            }

            for (String warning : result.warnings()) {
                System.err.println("warning: " + warning);
            }

            System.out.println((dryRun ? "[dry run] " : "") + "Changed " + result.changedFiles().size()
                    + " file(s) renaming " + className + "." + from + "() -> " + to + "()");
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
