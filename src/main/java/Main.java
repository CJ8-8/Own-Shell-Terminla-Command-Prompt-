import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.Iterator;
import java.util.Set;

public class Main {
    private static final String HOME = "~";
    private static final String PATH = "PATH";
    private static Path pwd = Paths.get(System.getProperty("user.dir"));
    private static final List<String> history = new ArrayList<>();
    // Tracks how many entries have already been flushed to disk via history -a/-w/-r
    private static int historyPersistedIndex = 0;

    // #region agent log
    private static String esc(String s) { return s == null ? "null" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }
    private static void debugLog(String location, String message, String data, String hypothesisId) {
        try {
            String line = String.format("{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"%s\",\"location\":\"%s\",\"message\":\"%s\",\"data\":\"%s\",\"timestamp\":%d}\n",
                esc(hypothesisId), esc(location), esc(message), esc(data), System.currentTimeMillis());
            Files.writeString(Path.of("/Users/chaitanya../codecrafters-shell-java/9de43f6f5f7cd3e1/.cursor/debug.log"), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }
    // #endregion

    // Returns the completed builtin (with trailing space) or null if no match.
    private static String builtinCompletion(String before) {
        if (before == null || before.isEmpty()) {
            return null;
        }

        // Only complete the first word (no whitespace allowed).
        for (int i = 0; i < before.length(); i++) {
            if (Character.isWhitespace(before.charAt(i))) {
                return null;
            }
        }

        if ("echo".startsWith(before)) {
            return "echo ";
        }
        if ("exit".startsWith(before)) {
            return "exit ";
        }
        return null;
    }

    private static TreeSet<String> executableMatches(String before) {
        var matches = new TreeSet<String>();

        if (before == null || before.isEmpty()) {
            return matches;
        }

        // Only complete the first word (no whitespace allowed).
        for (int i = 0; i < before.length(); i++) {
            if (Character.isWhitespace(before.charAt(i))) {
                return matches;
            }
        }

        String pathEnv = System.getenv(PATH);
        if (pathEnv == null || pathEnv.isBlank()) {
            return matches;
        }

        String sep = System.getProperty("path.separator");
        for (String dir : pathEnv.split(java.util.regex.Pattern.quote(sep))) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            try (var stream = Files.list(Paths.get(dir))) {
                stream.forEach(p -> {
                    try {
                        String name = p.getFileName().toString();
                        if (name.startsWith(before) && Files.isExecutable(p)) {
                            matches.add(name);
                        }
                    } catch (Exception ignored) {
                        // ignore unreadable entries
                    }
                });
            } catch (Exception ignored) {
                // ignore unreadable PATH directories
            }
        }

        return matches;
    }

    private static String longestCommonPrefix(TreeSet<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        Iterator<String> it = values.iterator();
        String prefix = it.next();
        while (it.hasNext() && !prefix.isEmpty()) {
            prefix = commonPrefix(prefix, it.next());
        }
        return prefix;
    }

    private static String commonPrefix(String a, String b) {
        int len = Math.min(a.length(), b.length());
        int i = 0;
        while (i < len && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return a.substring(0, i);
    }

    private static class RawMode implements AutoCloseable {
        private final String original;

        private RawMode(String original) {
            this.original = original;
        }

        static RawMode enable() throws IOException, InterruptedException {
            Process p = new ProcessBuilder("sh", "-lc", "stty -g < /dev/tty").redirectErrorStream(true).start();
            String orig = new String(p.getInputStream().readAllBytes());
            p.waitFor();

            new ProcessBuilder("sh", "-lc", "stty raw -echo < /dev/tty")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor();
            return new RawMode(orig.trim());
        }

        @Override
        public void close() throws IOException, InterruptedException {
            if (original != null && !original.isBlank()) {
                new ProcessBuilder("sh", "-lc", "stty " + original + " < /dev/tty")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                        .waitFor();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        final String prompt = "$ ";
        StringBuilder buf = new StringBuilder();
        String lastTabPrefix = null;
        boolean awaitingSecondTabForList = false;
        int historyIndex = history.size(); // points just after the last entry
        boolean inEscape = false;
        int escState = 0; // 0=none, 1=got ESC, 2=got ESC[

        // Load history on startup from HISTFILE (if provided)
        String histfile = System.getenv("HISTFILE");
        if (histfile != null && !histfile.isBlank()) {
            runHistoryRead(histfile);
        }
        // Reset history index after loading history
        historyIndex = history.size();

        RawMode raw = RawMode.enable();
        try {
            System.out.print(prompt);
            System.out.flush();

            while (true) {
                int ch = System.in.read();
                if (ch == -1) {
                    break;
                }

                // TAB completion for builtins (echo/exit).
                if (ch == '\t') {
                    String before = buf.toString();

                    // Builtins take precedence.
                    String completed = builtinCompletion(before);
                    if (completed != null) {
                        awaitingSecondTabForList = false;
                        lastTabPrefix = null;
                    }

                    if (completed == null) {
                        TreeSet<String> matches = executableMatches(before);
                        if (matches.size() == 1) {
                            completed = matches.first() + " ";
                            awaitingSecondTabForList = false;
                            lastTabPrefix = null;
                        } else if (matches.size() > 1) {
                            String lcp = longestCommonPrefix(matches);
                            if (lcp.length() > before.length()) {
                                // Extend to the longest common prefix (no trailing space unless unique).
                                String suffix = lcp.substring(before.length());
                                System.out.print(suffix);
                                System.out.flush();
                                buf.append(suffix);
                                awaitingSecondTabForList = false;
                                lastTabPrefix = null;
                                // clear escape sequence parsing state
                                inEscape = false;
                                escState = 0;
                                continue;
                            }

                            if (awaitingSecondTabForList && before.equals(lastTabPrefix)) {
                                // Second TAB: print matches, then re-print prompt and current buffer.
                                System.out.print("\r\n");
                                System.out.print(String.join("  ", matches));
                                System.out.print("\r\n");
                                System.out.print(prompt);
                                System.out.print(before);
                                System.out.flush();
                                awaitingSecondTabForList = false;
                                lastTabPrefix = null;
                                // clear escape sequence parsing state
                                inEscape = false;
                                escState = 0;
                                continue;
                            }

                            // First TAB with multiple matches and no further prefix: ring bell and arm second-tab behavior.
                            System.out.print("\u0007");
                            System.out.flush();
                            awaitingSecondTabForList = true;
                            lastTabPrefix = before;
                            // clear escape sequence parsing state
                            inEscape = false;
                            escState = 0;
                            continue;
                        } else {
                            // No matches.
                            completed = null;
                        }
                    }

                    if (completed == null) {
                        // No match: ring bell.
                        System.out.print("\u0007");
                        System.out.flush();
                    } else if (!completed.equals(before)) {
                        awaitingSecondTabForList = false;
                        lastTabPrefix = null;
                        // Print only the suffix to avoid relying on terminal control sequences.
                        String suffix = completed.substring(before.length());
                        System.out.print(suffix);
                        System.out.flush();
                        buf.append(suffix);
                    }
                    // clear escape sequence parsing state
                    inEscape = false;
                    escState = 0;
                    continue;
                }

                // ENTER: run command
                if (ch == '\n' || ch == '\r') {
                    awaitingSecondTabForList = false;
                    lastTabPrefix = null;
                    // Use CRLF so the cursor returns to column 0 before external output.
                    System.out.print("\r\n");
                    System.out.flush();

                    String line = buf.toString();
                    buf.setLength(0);
                    historyIndex = history.size();
                    escState = 0;
                    inEscape = false;

                    // Disable raw mode while executing the command so external programs output normally.
                    raw.close();

                    if (line != null && !line.isBlank()) {
                        // Record history for every executed command line (including `history` itself)
                        history.add(line);
                        historyIndex = history.size();
                        try {
                            if (!tryPipeline(line)) {
                                var command = parse(line);
                                run(command);
                            }
                        } catch (IllegalArgumentException ignored) {
                            // ignore invalid/empty commands
                        }
                    }

                    // Re-enable raw mode for next prompt/input.
                    raw = RawMode.enable();

                    System.out.print(prompt);
                    System.out.flush();
                    continue;
                }

                // Handle ANSI escape sequences for arrow keys (Up Arrow: ESC [ A)
                if (escState == 0) {
                    if (ch == 27) { // ESC
                        escState = 1;
                        continue;
                    }
                } else if (escState == 1) {
                    if (ch == '[') {
                        escState = 2;
                        continue;
                    }
                    // Not an escape sequence we care about
                    escState = 0;
                } else if (escState == 2) {
                    // Arrow key code
                    if (ch == 'A') {
                        // Up arrow
                        if (!history.isEmpty() && historyIndex > 0) {
                            historyIndex--;
                            String cmd = history.get(historyIndex);

                            // Rewrite the current line: CR + clear-to-end + prompt + command
                            System.out.print("\r");
                            System.out.print("\033[0K");
                            System.out.print(prompt);
                            System.out.print(cmd);
                            System.out.flush();

                            buf.setLength(0);
                            buf.append(cmd);
                            awaitingSecondTabForList = false;
                            lastTabPrefix = null;
                        }
                        escState = 0;
                        continue;
                    }

                    if (ch == 'B') {
                        // Down arrow
                        if (!history.isEmpty()) {
                            if (historyIndex < history.size() - 1) {
                                historyIndex++;
                                String cmd = history.get(historyIndex);

                                // Rewrite the current line: CR + clear-to-end + prompt + command
                                System.out.print("\r");
                                System.out.print("\033[0K");
                                System.out.print(prompt);
                                System.out.print(cmd);
                                System.out.flush();

                                buf.setLength(0);
                                buf.append(cmd);
                                awaitingSecondTabForList = false;
                                lastTabPrefix = null;
                            } else if (historyIndex == history.size() - 1) {
                                // Move to "empty" current input after the newest entry
                                historyIndex = history.size();

                                System.out.print("\r");
                                System.out.print("\033[0K");
                                System.out.print(prompt);
                                System.out.flush();

                                buf.setLength(0);
                                awaitingSecondTabForList = false;
                                lastTabPrefix = null;
                            }
                        }
                        escState = 0;
                        continue;
                    }

                    // Any other escape sequence: ignore
                    escState = 0;
                    continue;
                }
                // Normal character: append to buffer and echo it.
                awaitingSecondTabForList = false;
                lastTabPrefix = null;
                buf.append((char) ch);
                System.out.print((char) ch);
                System.out.flush();
            }
        } finally {
            if (raw != null) {
                raw.close();
            }
        }
    }

    enum CommandName {
        exit,
        echo,
        type,
        pwd,
        cd,
        ls,
        history;

        static CommandName of(String name) {
            try {
                return valueOf(name);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    record Command(
            String command,
            String[] args,
            String[] commandWithArgs,
            RedirectType redirectType,
            String redirectTo) {}

    private static Command parse(String command) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command cannot be null or empty");
        }

        List<String> split = splitCommand(command);
        return parseTokens(split);
    }

    private static Command parseTokens(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            throw new IllegalArgumentException("command cannot be empty");
        }

        String[] splitArray = tokens.toArray(new String[0]);

        if (splitArray.length == 1) {
            // no args
            return new Command(tokens.get(0), new String[0], splitArray, null, "");
        }

        var rediect = getRedirect(splitArray);
        var rediectAt = rediect.redirectAt;
        String[] args = Arrays.copyOfRange(splitArray, 1, rediectAt);
        var commandWithArgs = Arrays.copyOf(splitArray, rediectAt);
        String redirectTo = "";
        if (rediect.redirectType != null) {
            String op = splitArray[rediectAt];
            String branch = "none";
            if (op.startsWith("2>>") && op.length() > 3) {
                redirectTo = op.substring(3); branch = "2>>attach";
            } else if (op.startsWith("2>") && !op.startsWith("2>>") && op.length() > 2) {
                redirectTo = op.substring(2); branch = "2>attach";
            } else if (op.startsWith("1>>") && op.length() > 3) {
                redirectTo = op.substring(3); branch = "1>>attach";
            } else if (op.startsWith("1>") && !op.startsWith("1>>") && op.length() > 2) {
                redirectTo = op.substring(2); branch = "1>attach";
            } else {
                redirectTo = splitArray[rediectAt + 1]; branch = "else";
            }
            // #region agent log
            debugLog("parseTokens", "redirectTo extraction", String.format("op=%s branch=%s redirectTo=%s nextTok=%s", op, branch, redirectTo, rediectAt + 1 < splitArray.length ? splitArray[rediectAt + 1] : "OOB"), "H1");
            if (rediect.redirectType == RedirectType.stderr_append) debugLog("parseTokens", "stderr_append path", "redirectTo=" + redirectTo + " tokens=" + Arrays.toString(splitArray), "H2");
            // #endregion
        }

        return new Command(tokens.get(0), args, commandWithArgs, rediect.redirectType, redirectTo);
    }

    private static boolean tryPipeline(String line) throws IOException, InterruptedException {
        List<String> tokens = splitCommand(line);
        if (!tokens.contains("|")) {
            return false;
        }

        // Split tokens into segments by '|'
        List<List<String>> segments = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if ("|".equals(tokens.get(i))) {
                if (i == start) {
                    throw new IllegalArgumentException("pipeline needs command on both sides");
                }
                segments.add(tokens.subList(start, i));
                start = i + 1;
            }
        }
        if (start >= tokens.size()) {
            throw new IllegalArgumentException("pipeline needs command on both sides");
        }
        segments.add(tokens.subList(start, tokens.size()));

        // Parse segments into Commands
        List<Command> commands = new ArrayList<>();
        for (var seg : segments) {
            commands.add(parseTokens(seg));
        }

        runPipeline(commands);
        return true;
    }

    private static Redirect getRedirect(String[] split) {
        var rediectAt = split.length;
        RedirectType type = null;
        for (int i = 0; i < split.length; i++) {
            var s = split[i];
            // Support attached redirections (e.g. 2>>/tmp/file)
            if (s.startsWith("2>>") && s.length() > 3) {
                rediectAt = i;
                type = RedirectType.stderr_append;
                break;
            }
            if (s.startsWith("2>") && !s.startsWith("2>>") && s.length() > 2) {
                rediectAt = i;
                type = RedirectType.stderr;
                break;
            }
            if (s.startsWith("1>>") && s.length() > 3) {
                rediectAt = i;
                type = RedirectType.stdout_append;
                break;
            }
            if (s.startsWith("1>") && !s.startsWith("1>>") && s.length() > 2) {
                rediectAt = i;
                type = RedirectType.stdout;
                break;
            }
            if (s.equals(">") || s.equals("1>")) {
                rediectAt = i;
                type = RedirectType.stdout;
                break;
            }
            if (s.equals("2>")) {
                rediectAt = i;
                type = RedirectType.stderr;
                break;
            }
            if (s.equals(">>") || s.equals("1>>")) {
                rediectAt = i;
                type = RedirectType.stdout_append;
                break;
            }
            if (s.equals("2>>")) {
                rediectAt = i;
                type = RedirectType.stderr_append;
                break;
            }
        }
        return new Redirect(type, rediectAt);
    }

    private record Redirect(RedirectType redirectType, int redirectAt) {}

    private enum RedirectType {
        stdout,
        stderr,
        stdout_append,
        stderr_append
    }

    private enum QuteMode {
        singleQuote,
        doubleQuote
    }

    private static List<String> splitCommand(String command) {
        var result = new ArrayList<String>();
        var temp = new StringBuilder();
        QuteMode quteMode = null;
        var escape = false;
        var toEscape = Set.of('\"', '\\', '$', '`');

        for (char ch : command.toCharArray()) {
            if (quteMode == QuteMode.singleQuote) {
                if (ch == '\'') {
                    quteMode = null;
                } else {
                    temp.append(ch);
                }
            } else if (quteMode == QuteMode.doubleQuote) {
                if (escape) {
                    if (!toEscape.contains(ch)) {
                        temp.append('\\');
                    }
                    temp.append(ch);
                    escape = false;
                } else {
                    if (ch == '\"') {
                        quteMode = null;
                    } else if (ch == '\\') {
                        escape = true;
                    } else {
                        temp.append(ch);
                    }
                }
            } else {
                if (escape) {
                    temp.append(ch);
                    escape = false;
                } else {
                    if (ch == '\'') {
                        quteMode = QuteMode.singleQuote;
                    } else if (ch == '\"') {
                        quteMode = QuteMode.doubleQuote;
                    } else if (ch == ' ') {
                        addTemp(result, temp);
                    } else if (ch == '\\') {
                        escape = true;
                    } else {
                        temp.append(ch);
                    }
                }
            }
        }

        if (quteMode != null) {
            throw new IllegalArgumentException("Unclosed quote.");
        }

        addTemp(result, temp);

        return result;
    }

    private static void addTemp(List<String> result, StringBuilder temp) {
        if (temp.length() > 0) {
            result.add(temp.toString());
            temp.setLength(0);
        }
    }

    private static void run(Command command) throws IOException, InterruptedException {
        var commandName = CommandName.of(command.command);

        if (Objects.isNull(commandName)) {
            runNotBuiltin(command);
            return;
        }

        switch (commandName) {
            case exit -> {
                int status = 0;
                if (command.args.length != 0) {
                    status = Integer.parseInt(command.args[0]);
                }

                // Append only new history entries to HISTFILE on exit (if provided)
                String histfile = System.getenv("HISTFILE");
                if (histfile != null && !histfile.isBlank()) {
                    runHistoryAppend(histfile);
                }

                System.exit(status);
            }
            case echo -> {
                runEcho(command);
            }
            case type -> {
                runType(command);
            }
            case pwd -> {
                // Print the current working directory as an absolute path
                System.out.println(pwd.toAbsolutePath().normalize());
            }
            case cd -> {
                runCd(command);
            }
            case ls -> {
                runNotBuiltin(command);
            }
            case history -> {
                // Support:
                //  - history
                //  - history <n>
                //  - history -r <path>
                //  - history -w <path>
                if (command.args.length >= 2 && "-r".equals(command.args[0])) {
                    runHistoryRead(command.args[1]);
                    return;
                }

                if (command.args.length >= 2 && "-w".equals(command.args[0])) {
                    runHistoryWrite(command.args[1]);
                    return;
                }

                if (command.args.length >= 2 && "-a".equals(command.args[0])) {
                    runHistoryAppend(command.args[1]);
                    return;
                }

                int n = -1;
                if (command.args.length >= 1) {
                    try {
                        n = Integer.parseInt(command.args[0]);
                    } catch (NumberFormatException ignored) {
                        n = -1;
                    }
                }
                runHistory(n);
            }
        }
    }


    private static void runEcho(Command command) throws IOException {
        var message = String.join(" ", command.args);

        // No redirection: print normally
        if (command.redirectType == null) {
            System.out.println(message);
            return;
        }

        // Resolve redirect target relative to current working directory (pwd)
        Path path = Path.of(command.redirectTo);
        if (!path.isAbsolute()) {
            path = pwd.resolve(path);
        }
        path = path.normalize();

        // Ensure parent directories exist if possible
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
        } catch (IOException ignored) {
        }

        byte[] bytes = (message + "\n").getBytes();

        switch (command.redirectType) {
            case stdout -> {
                Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
            case stdout_append -> {
                Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            case stderr -> {
                // echo writes to stdout only. If stderr is redirected, just create/truncate the stderr target.
                Files.write(path, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                System.out.println(message);
            }
            case stderr_append -> {
                // echo writes to stdout only. If stderr is appended, just ensure the file exists.
                Files.write(path, new byte[0], StandardOpenOption.CREATE);
                System.out.println(message);
            }
        }
    }

    private static void runCd(Command command) {
        if (command.args.length == 0) {
            return;
        }
        var targetPath = command.args[0];
        var separator = System.getProperty("file.separator");
        if (targetPath.equals(HOME) || targetPath.startsWith(HOME + separator)) {
            var homeDir = System.getenv("HOME");
            if (homeDir != null && !homeDir.isBlank()) {
                if (targetPath.equals(HOME)) {
                    targetPath = homeDir;
                } else {
                    targetPath = homeDir + targetPath.substring(1); // replace leading '~'
                }
            }
        }

        Path newPath;
        Path target = Path.of(targetPath);
        if (target.isAbsolute()) {
            newPath = target.normalize();
        } else {
            newPath = pwd.resolve(targetPath).normalize();
        }
        if (!Files.isDirectory(newPath)) {
            var error = String.format("cd: %s: No such file or directory", targetPath);
            System.out.println(error);
        } else {
            // Use the real/normalized filesystem path so that `..` behaves correctly
            try {
                pwd = newPath.toRealPath();
            } catch (IOException ignored) {
                pwd = newPath.toAbsolutePath().normalize();
            }
        }
    }

    private static void runNotBuiltin(Command command) throws IOException, InterruptedException {
        var executable = findExecutable(command.command);
        if (executable != null) {
            var execCommand = new ArrayList<String>();
            execCommand.add("bash");
            execCommand.add("-lc");

            StringBuilder script = new StringBuilder();
            if (command.redirectType != null) {
                // When redirection is requested, run directly so bash handles redirection reliably
                script.append("'").append(executable.replace("'", "'\\''")).append("'");
            } else {
                // Preserve argv[0] for `type` output etc.
                script.append("exec -a ");
                script.append("'").append(command.command.replace("'", "'\\''")).append("'");
                script.append(" ");
                script.append("'").append(executable.replace("'", "'\\''")).append("'");
            }

            for (String arg : command.args) {
                script.append(" ");
                script.append("'").append(arg.replace("'", "'\\''")).append("'");
            }

            // Shell-level redirection for output/error
            if (command.redirectType == RedirectType.stderr_append) {
                script.append(" 2>> ");
                script.append("'").append(Path.of(command.redirectTo).toAbsolutePath().toString().replace("'", "'\\''")).append("'");
            }
            if (command.redirectType == RedirectType.stderr) {
                script.append(" 2> ");
                script.append("'").append(Path.of(command.redirectTo).toAbsolutePath().toString().replace("'", "'\\''")).append("'");
            }
            if (command.redirectType == RedirectType.stdout_append) {
                script.append(" 1>> ");
                script.append("'").append(Path.of(command.redirectTo).toAbsolutePath().toString().replace("'", "'\\''")).append("'");
            }
            if (command.redirectType == RedirectType.stdout) {
                script.append(" 1> ");
                script.append("'").append(Path.of(command.redirectTo).toAbsolutePath().toString().replace("'", "'\\''")).append("'");
            }

            // Ensure redirect target file exists for append modes
            if (command.redirectType == RedirectType.stderr_append || command.redirectType == RedirectType.stdout_append) {
                try {
                    Files.createDirectories(Path.of(command.redirectTo).toAbsolutePath().getParent());
                    Files.write(Path.of(command.redirectTo).toAbsolutePath(), new byte[0], StandardOpenOption.CREATE);
                } catch (IOException ignored) {
                }
            }

            execCommand.add(script.toString());
            var processBuilder = new ProcessBuilder(execCommand);

            // Let bash handle redirection via the script. Keep both streams inherited.
            processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);

            var process = processBuilder.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {}
        } else {
            var error = String.format("%s: command not found", command.command);
            System.out.println(error);
        }
    }


    private static void runType(Command command) {
        if (command.args.length == 0) {
            System.out.println("type: not found");
            return;
        }
        var arg0 = command.args[0];
        var toType = CommandName.of(arg0);
        if (toType == null) {
            var executable = findExecutable(arg0);
            if (executable != null) {
                var message = String.format("%s is %s", arg0, executable);
                System.out.println(message);
            } else {
                var error = String.format("%s: not found", arg0);
                System.out.println(error);
            }
        } else {
            var message = String.format("%s is a shell builtin", toType);
            System.out.println(message);
        }
    }

    private static String findExecutable(String commandName) {
        var pathEnv = System.getenv(PATH);
        var directories = pathEnv.split(System.getProperty("path.separator"));

        for (var dir : directories) {
            var filePath = Paths.get(dir, commandName);
            if (Files.isExecutable(filePath)) {
                return filePath.toAbsolutePath().toString();
            }
        }

        return null;
    }

    private static void runPipeline(List<Command> commands) throws IOException, InterruptedException {
        if (commands == null || commands.size() < 2) {
            throw new IllegalArgumentException("pipeline must have at least 2 commands");
        }

        int n = commands.size();
        Process[] procs = new Process[n];
        // Start external processes from right to left, skipping builtins (except ls)
        for (int i = n - 1; i >= 0; i--) {
            Command c = commands.get(i);
            CommandName name = CommandName.of(c.command);
            // Only external or "ls" builtin (which is executed as external)
            if (name == null || name == CommandName.ls) {
                String exec = findExecutable(c.command);
                if (exec == null) {
                    System.out.println(String.format("%s: command not found", c.command));
                    // Clean up any started processes
                    for (int j = i + 1; j < n; j++) {
                        if (procs[j] != null && procs[j].isAlive()) procs[j].destroy();
                    }
                    return;
                }
                List<String> cmd = new ArrayList<>();
                cmd.add(exec);
                cmd.addAll(List.of(c.args));
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(pwd.toFile());
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                if (i == n - 1) {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                }
                procs[i] = pb.start();
            }
        }

        // Find the next external (or ls) process after a given index
        java.util.function.IntUnaryOperator nextExternal = idx -> {
            for (int i = idx + 1; i < n; i++) {
                CommandName name = CommandName.of(commands.get(i).command);
                if (name == null || name == CommandName.ls) return i;
            }
            return -1;
        };

        // For each command except the last, pump data if possible
        List<Thread> pumps = new ArrayList<>();
        for (int i = 0; i < n - 1; i++) {
            CommandName srcName = CommandName.of(commands.get(i).command);
            int dstIdx = nextExternal.applyAsInt(i);
            if (dstIdx == -1) break;
            if (srcName != null && srcName != CommandName.ls) {
                // Builtin: write to next external's stdin
                final int idx = i;
                final int outIdx = dstIdx;
                Thread t = new Thread(() -> {
                    var originalOut = System.out;
                    try {
                        System.setOut(new java.io.PrintStream(procs[outIdx].getOutputStream(), true));
                        run(commands.get(idx));
                    } catch (Exception ignored) {
                    } finally {
                        System.out.flush();
                        System.setOut(originalOut);
                        try {
                            procs[outIdx].getOutputStream().close();
                        } catch (IOException ignored) {}
                    }
                });
                t.start();
                pumps.add(t);
                break; // Only first builtin in pipeline is supported as producer
            } else {
                // External: pump output to next external's input
                final int srcIdx = i;
                final int dstIdx2 = dstIdx;
                Thread t = new Thread(() -> {
                    try (var in = procs[srcIdx].getInputStream(); var out = procs[dstIdx2].getOutputStream()) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = in.read(buf)) != -1) {
                            try {
                                out.write(buf, 0, len);
                                out.flush();
                            } catch (IOException brokenPipe) {
                                break;
                            }
                        }
                    } catch (IOException ignored) {
                    } finally {
                        try {
                            procs[dstIdx2].getOutputStream().close();
                        } catch (IOException ignored) {}
                    }
                });
                t.start();
                pumps.add(t);
            }
        }

        // If the last command is a builtin (except ls), just run it and ignore pipeline input
        Command last = commands.get(n - 1);
        CommandName lastName = CommandName.of(last.command);
        if (lastName != null && lastName != CommandName.ls) {
            run(last);
        }

        // Wait for pumps to finish
        for (Thread t : pumps) {
            t.join();
        }
        // Wait for last process to finish if it's external
        if (procs[n - 1] != null) {
            procs[n - 1].waitFor();
        }
        // Kill upstream processes if still running
        for (int i = 0; i < n - 1; i++) {
            if (procs[i] != null && procs[i].isAlive()) procs[i].destroy();
        }
        for (int i = 0; i < n - 1; i++) {
            if (procs[i] != null && procs[i].isAlive()) procs[i].destroyForcibly();
        }
        // Wait for all upstream to finish
        for (int i = 0; i < n - 1; i++) {
            if (procs[i] != null) procs[i].waitFor();
        }
        // Manually close all srcIn/dstOut streams
        for (int i = 0; i < n; i++) {
            if (procs[i] != null) {
                try { procs[i].getInputStream().close(); } catch (IOException ignored) {}
                try { procs[i].getOutputStream().close(); } catch (IOException ignored) {}
            }
        }
    }
    private static void runHistoryRead(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) {
            return;
        }

        Path path = Path.of(pathStr);
        if (!path.isAbsolute()) {
            path = pwd.resolve(path);
        }
        path = path.normalize();

        try {
            List<String> lines = Files.readAllLines(path);
            for (String l : lines) {
                if (l == null) continue;
                String s = l.stripTrailing();
                if (s.isBlank()) continue; // ignore empty lines
                history.add(s);
            }
            // Everything we just loaded is already persisted.
            historyPersistedIndex = history.size();
        } catch (IOException ignored) {
            // If file can't be read, do nothing for this stage
        }
    }

    private static void runHistoryWrite(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) {
            return;
        }

        Path path = Path.of(pathStr);
        if (!path.isAbsolute()) {
            path = pwd.resolve(path);
        }
        path = path.normalize();

        // Build file content with trailing newline
        StringBuilder sb = new StringBuilder();
        for (String cmd : history) {
            if (cmd == null) continue;
            sb.append(cmd).append("\n");
        }

        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
        } catch (IOException ignored) {
        }

        try {
            Files.writeString(path, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            // All history entries are now persisted.
            historyPersistedIndex = history.size();
        } catch (IOException ignored) {
            // If file can't be written, do nothing for this stage
        }
    }

    private static void runHistoryAppend(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) {
            return;
        }

        Path path = Path.of(pathStr);
        if (!path.isAbsolute()) {
            path = pwd.resolve(path);
        }
        path = path.normalize();

        // Append only commands that have not yet been written out.
        if (historyPersistedIndex >= history.size()) {
            return; // nothing new to append
        }

        StringBuilder sb = new StringBuilder();
        for (int i = historyPersistedIndex; i < history.size(); i++) {
            String cmd = history.get(i);
            if (cmd == null) continue;
            sb.append(cmd).append("\n");
        }

        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
        } catch (IOException ignored) {
        }

        try {
            Files.writeString(path, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            // Update persisted index to reflect appended entries.
            historyPersistedIndex = history.size();
        } catch (IOException ignored) {
            // If file can't be appended, do nothing for this stage
        }
    }

    private static void runHistory(int n) {
        int total = history.size();

        // If n is invalid or larger than total, show all history.
        int start = 0;
        if (n > 0 && n < total) {
            start = total - n;
        }

        for (int i = start; i < total; i++) {
            // Expected: 4 spaces before 1, then two spaces after the number
            System.out.printf("%5d  %s%n", i + 1, history.get(i));
        }
    }
}