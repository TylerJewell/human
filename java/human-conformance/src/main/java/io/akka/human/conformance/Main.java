package io.akka.human.conformance;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** {@code java -jar human-conformance.jar --url http://localhost:9164 [--pattern ...] [--report report.json]}. */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  static int run(String[] args, PrintStream out, PrintStream err) {
    Options options;
    try {
      options = Options.parse(args);
    } catch (IllegalArgumentException e) {
      err.println(e.getMessage());
      err.println(Options.USAGE);
      return 2;
    }
    if (options.help()) {
      out.println(Options.USAGE);
      return 0;
    }
    if (!options.allowReset()) {
      err.println("Refusing to run: the suite resets every service it checks, and --allow-reset was not given.");
      err.println("It would reset:");
      err.println("  " + options.url() + "  (POST /oversight/dev/reset, and its development clock)");
      err.println(Options.USAGE);
      return 2;
    }
    out.println("*".repeat(78));
    out.println("WARNING: --allow-reset. This run resets the service below before every scenario:");
    out.println("every pending event is closed, every halt lifted, every standing decision revoked.");
    out.println("  " + options.url());
    out.println("*".repeat(78));
    out.println("human-conformance against " + options.url());
    String[] pattern = {null};
    Report report = new Suite(options, result -> {
      if (!result.pattern().equals(pattern[0])) {
        pattern[0] = result.pattern();
        out.println();
        out.println(result.pattern());
      }
      out.println(Report.line(result));
    }).run();
    out.print(report.summary());
    if (options.report() != null) {
      try {
        Path file = options.report().toAbsolutePath();
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), report.toJson());
        out.println("Report written to " + file);
      } catch (IOException e) {
        err.println("Cannot write the report: " + e.getMessage());
        return 2;
      }
    }
    return report.passed() ? 0 : 1;
  }
}
