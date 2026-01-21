import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class Main {
    private static final String HOME = "~";
    private static final String PATH = "PATH";
    private static Path pwd = Paths.get(System.getProperty("user.dir"));

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

    private static class RawMode implements AutoCloseable {
        private final String original;

        private RawMode(String original) {
            this.original = original;
        }

        static RawMode enable() throws IOException, InterruptedException {
            Process p = new ProcessBuilder("sh", "-lc", "stty -g < /dev/tty").redirectErrorStream(true).start();
            String orig = new String(p.getInputStream().readAllBytes());
            p.waitFor();

            new ProcessBuilder("sh", "-lc", "stty raw -echo < /dev/tty").inheritIO().start().waitFor();
            return new RawMode(orig.trim());
        }

        @Override
        public void close() throws IOException, InterruptedException {
            if (original != null && !original.isBlank()) {
                new ProcessBuilder("sh", "-lc", "stty " + original + " < /dev/tty").inheritIO().start().waitFor();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        final String prompt = "$ ";
        StringBuilder buf = new StringBuilder();

        try (RawMode raw = RawMode.enable()) {
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
                    String completed = builtinCompletion(before);
                    if (completed != null && !completed.equals(before)) {
                        // Print only the suffix to avoid relying on terminal control sequences.
                        String suffix = completed.substring(before.length());
                        System.out.print(suffix);
                        System.out.flush();
                        buf.append(suffix);
                    }
                    continue;
                }

                // ENTER: run command
                if (ch == '\n' || ch == '\r') {
                    System.out.print("\n");
                    System.out.flush();

                    String line = buf.toString();
                    buf.setLength(0);

                    if (line != null && !line.isBlank()) {
                        try {
                            var command = parse(line);
                            run(command);
                        } catch (IllegalArgumentException ignored) {
                            // ignore invalid/empty commands
                        }
                    }

                    System.out.print(prompt);
                    System.out.flush();
                    continue;
                }

                // Normal character: append to buffer and echo it.
                buf.append((char) ch);
                System.out.print((char) ch);
                System.out.flush();
            }
        }
    }

    enum CommandName {
        exit,
        echo,
        type,
        pwd,
        cd;

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
        if (split.isEmpty()) {
            throw new IllegalArgumentException("command cannot be empty");
        }

        String[] splitArray = split.toArray(new String[0]);

        if (splitArray.length == 1) {
            // no args
            return new Command(split.get(0), new String[0], splitArray, null, "");
        }

        var rediect = getRedirect(splitArray);
        var rediectAt = rediect.redirectAt;
        String[] args = Arrays.copyOfRange(splitArray, 1, rediectAt);
        var commandWithArgs = Arrays.copyOf(splitArray, rediectAt);
        var redirectTo = rediect.redirectType != null ? splitArray[rediectAt + 1] : "";

        return new Command(split.get(0), args, commandWithArgs, rediect.redirectType, redirectTo);
    }

    private static Redirect getRedirect(String[] split) {
        var rediectAt = split.length;
        RedirectType type = null;
        for (int i = 0; i < split.length; i++) {
            var s = split[i];
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
                System.exit(status);
            }
            case echo -> {
                runEcho(command);
            }
            case type -> {
                runType(command);
            }
            case pwd -> {
                System.out.println(pwd);
            }
            case cd -> {
                runCd(command);
            }
        }
    }

    private static void runEcho(Command command) throws IOException {
        var message = String.join(" ", command.args);
        if (command.redirectType != null) {
            var path = Path.of(command.redirectTo);
            switch (command.redirectType) {
                case stdout -> {
                    var bytes = String.format("%s\n", message).getBytes();
                    Files.write(
                            path,
                            bytes,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);
                }
                case stderr -> {
                    Files.write(
                            path,
                            "".getBytes(),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);
                    System.out.println(message);
                }
                case stdout_append -> {
                    var bytes = String.format("%s\n", message).getBytes();
                    Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
                case stderr_append -> {
                    Files.write(
                            path,
                            "".getBytes(),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND);
                    System.out.println(message);
                }
            }
        } else {
            System.out.println(message);
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
            targetPath = targetPath.replaceFirst(HOME, homeDir);
        }

        var newPath = pwd.resolve(targetPath).normalize();
        if (!Files.isDirectory(newPath)) {
            var error = String.format("cd: %s: No such file or directory", newPath);
            System.out.println(error);
        } else {
            pwd = newPath;
        }
    }

    private static void runNotBuiltin(Command command) throws IOException, InterruptedException {
        var executable = findExecutable(command.command);
        if (executable != null) {
            var processBuilder = new ProcessBuilder(command.commandWithArgs);
            var redirectType = command.redirectType;
            if (Objects.nonNull(redirectType)) {
                var file = Path.of(command.redirectTo).toFile();
                switch (redirectType) {
                    case stdout -> {
                        processBuilder.redirectOutput(file);
                        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }
                    case stderr -> {
                        processBuilder.redirectError(file);
                        processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    }
                    case stdout_append -> {
                        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(file));
                        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }
                    case stderr_append -> {
                        processBuilder.redirectError(ProcessBuilder.Redirect.appendTo(file));
                        processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    }
                }
            } else {
                processBuilder.inheritIO();
            }
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
            System.out.println("type command requires an argument");
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
}