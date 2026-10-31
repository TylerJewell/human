package io.akka.human.conformance;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** The command line: the service URL, an optional pattern filter, an optional report file. */
record Options(String url, Set<String> patterns, Path report, boolean allowReset, boolean help) {
  static final String USAGE = """
      usage: java -jar human-conformance.jar --url <base-url> --allow-reset [--pattern <id>,...] [--report <file.json>]
        --url          the service serving /oversight/*, for example http://localhost:9164
        --allow-reset  required. The suite calls POST /oversight/dev/reset before every scenario, which closes every
                       pending event, lifts every halt and revokes every standing decision of that service, and it moves
                       the service's development clock, putting it back at the end. Do not run it against a service
                       holding state you keep.
        --pattern      run only these patterns (comma list); the service checks always run
        --report       also write the JSON report to this file
      Exits 0 when every applicable scenario passes, 1 when one fails, 2 on a usage error or when --allow-reset is missing.""";

  static Options parse(String[] args) {
    String url = null;
    Set<String> patterns = new LinkedHashSet<>();
    Path report = null;
    boolean allowReset = false;
    boolean help = false;
    for (int i = 0; i < args.length; i++) {
      String name = args[i];
      String value = null;
      int eq = name.indexOf('=');
      if (name.startsWith("--") && eq > 0) {
        value = name.substring(eq + 1);
        name = name.substring(0, eq);
      }
      switch (name) {
        case "--help", "-h" -> help = true;
        case "--allow-reset" -> allowReset = true;
        case "--url" -> url = value != null ? value : valueAt(args, ++i, name);
        case "--pattern", "--patterns" -> {
          String list = value != null ? value : valueAt(args, ++i, name);
          for (String p : list.split(",")) {
            String id = p.trim();
            if (id.isEmpty()) continue;
            if (!Patterns.ALL.contains(id)) {
              throw new IllegalArgumentException("unknown pattern " + id + "; one of " + String.join(", ", Patterns.ALL));
            }
            patterns.add(id);
          }
        }
        case "--report" -> report = Path.of(value != null ? value : valueAt(args, ++i, name));
        default -> throw new IllegalArgumentException("unknown option " + name);
      }
    }
    if (!help) {
      if (url == null) throw new IllegalArgumentException("--url is required");
      if (!url.startsWith("http://") && !url.startsWith("https://")) {
        throw new IllegalArgumentException("--url must start with http:// or https://");
      }
    }
    return new Options(url, Set.copyOf(patterns), report, allowReset, help);
  }

  boolean includes(String pattern) {
    return patterns.isEmpty() || patterns.contains(pattern);
  }

  private static String valueAt(String[] args, int i, String name) {
    if (i >= args.length || Arrays.asList("--url", "--pattern", "--patterns", "--report").contains(args[i])) {
      throw new IllegalArgumentException(name + " needs a value");
    }
    return args[i];
  }
}
