package dev.jbaby.ditto.cli;

import java.io.PrintStream;
import java.util.List;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * {@code java -jar ditto-cli.jar [compare|generate] --option=value ...}
 * <p>
 * Exit codes: 0 GREEN, 1 YELLOW, 2 RED, 3 error (including usage errors). {@code generate} exits with 0 or 3.
 */
@SpringBootApplication
public class CliApplication {

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Runs the CLI and returns the exit code; output goes to {@code out}, messages to {@code err}. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (List.of(args).contains("--help") || List.of(args).contains("-h")) {
            out.print(Usage.TEXT);
            return ExitCodes.OK;
        }
        SpringApplication application = new SpringApplication(CliApplication.class);
        application.addInitializers(context -> context.getBeanFactory()
                .registerSingleton("cliConsole", new CliConsole(out, err)));
        try (ConfigurableApplicationContext context = application.run(args)) {
            return context.getBean(CliRunner.class).exitCode();
        } catch (RuntimeException e) {
            err.println("Error: " + e.getMessage());
            return ExitCodes.ERROR;
        }
    }
}
