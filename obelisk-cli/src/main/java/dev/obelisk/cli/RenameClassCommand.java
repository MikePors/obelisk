package dev.obelisk.cli;

import dev.obelisk.core.ProjectContext;
import dev.obelisk.core.RefactorException;
import dev.obelisk.core.refactor.RefactorResult;
import dev.obelisk.core.refactor.RenameClassRefactor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "rename-class", description = "Rename a class/interface/enum/record and every reference obelisk can resolve back to it.")
public class RenameClassCommand implements Callable<Integer> {

    @Option(names = "--project-dir", description = "Path to the Maven project root (default: current directory)")
    private Path projectDir = Path.of(".");

    @Option(names = "--class", required = true, description = "Simple name of the class/interface/enum/record to rename")
    private String className;

    @Option(names = "--to", required = true, description = "New type name")
    private String to;

    @Option(names = "--dry-run", description = "Show the diff without writing changes to disk")
    private boolean dryRun;

    @Override
    public Integer call() {
        try (ProjectContext ctx = ProjectContext.load(projectDir.toAbsolutePath().normalize())) {
            RefactorResult result = RenameClassRefactor.run(ctx, className, to, !dryRun);

            for (Path file : result.diffs().keySet().stream().sorted().toList()) {
                System.out.println(result.diffs().get(file));
            }

            for (Path oldFile : result.renamedFiles().keySet().stream().sorted().toList()) {
                System.out.println((dryRun ? "[dry run] " : "") + "Rename file: " + oldFile + " -> "
                        + result.renamedFiles().get(oldFile));
            }

            for (String warning : result.warnings()) {
                System.err.println("warning: " + warning);
            }

            if (result.isEmpty()) {
                System.out.println("No changes: nothing resolved back to " + className);
            } else {
                System.out.println((dryRun ? "[dry run] " : "") + "Changed " + result.changedFiles().size()
                        + " file(s) renaming " + className + " -> " + to);
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
