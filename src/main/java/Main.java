import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.io.File;
import java.util.Scanner;

public class Main {
    private static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c == '\\' && !inSingleQuotes && !inDoubleQuotes) {
                if (i + 1 < input.length()) {
                    current.append(input.charAt(i + 1));
                    i++; // skip next character
                } else {
                    current.append('\\');
                }
                continue;
            }

            if (c == '\\' && inDoubleQuotes && !inSingleQuotes) {
                if (i + 1 < input.length()) {
                    char next = input.charAt(i + 1);
                    if (next == '"' || next == '\\') {
                        current.append(next);
                        i++; // skip escaped char
                        continue;
                    }
                }
                // backslash is literal for other chars in double quotes
                current.append('\\');
                continue;
            }

            if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
                continue; // don't include the quote char
            }

            if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
                continue; // don't include the quote char
            }

            if (!inSingleQuotes && !inDoubleQuotes && Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue; // collapse whitespace outside quotes
            }

            // inside quotes: keep everything literal (including whitespace)
            current.append(c);
        }

        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");

            if (!sc.hasNextLine()) {
                break; // EOF
            }

            String command = sc.nextLine();

            if (command == null) {
                break;
            }

            command = command.trim();
            if (command.equals("exit") || command.equals("exit 0")) {
                System.exit(0);
            }

            List<String> tokens = tokenize(command);
            if (tokens.isEmpty()) {
                continue;
            }
            String program = tokens.get(0);

            if (program.equals("echo")) {
                if (tokens.size() == 1) {
                    System.out.println();
                } else {
                    System.out.println(String.join(" ", tokens.subList(1, tokens.size())));
                }
                continue;
            }
            if (program.equals("type")) {
                if (tokens.size() < 2) {
                    System.out.println("type: not found");
                    continue;
                }
                String target = tokens.get(1);

                // 1) Builtins
                if (target.equals("echo") || target.equals("exit") || target.equals("type")) {
                    System.out.println(target + " is a shell builtin");
                    continue;
                }

                // 2) Search PATH for executables
                boolean found = false;
                String pathEnv = System.getenv("PATH");
                if (pathEnv != null && !pathEnv.isBlank()) {
                    String[] dirs = pathEnv.split(":");
                    for (String dir : dirs) {
                        if (dir == null || dir.isBlank()) continue;

                        File candidate = new File(dir, target);
                        if (candidate.exists() && candidate.isFile() && candidate.canExecute()) {
                            System.out.println(target + " is " + candidate.getAbsolutePath());
                            found = true;
                            break;
                        }
                    }
                }

                if (!found) {
                    System.out.println(target + ": not found");
                }
                continue;
            }

            List<String> argsList = new ArrayList<>(tokens);

            // Find executable in PATH
            String pathEnv = System.getenv("PATH");
            String resolvedPath = null;
            if (pathEnv != null && !pathEnv.isBlank()) {
                String[] dirs = pathEnv.split(":");
                for (String dir : dirs) {
                    if (dir == null || dir.isBlank()) continue;
                    File candidate = new File(dir, program);
                    if (candidate.exists() && candidate.isFile() && candidate.canExecute()) {
                        resolvedPath = candidate.getAbsolutePath();
                        break;
                    }
                }
            }

            if (resolvedPath == null) {
                System.out.println(program + ": command not found");
                continue;
            }

            // Replace argv[0] with resolved absolute path
            argsList.set(0, resolvedPath);

            File execFile = new File(resolvedPath);
            File execDir = execFile.getParentFile();

            ProcessBuilder pb = new ProcessBuilder(argsList);
            pb.inheritIO();
            pb.directory(execDir);

            try {
                Process p = pb.start();
                p.waitFor();
            } catch (IOException | InterruptedException e) {
                System.out.println(program + ": command not found");
            }
        }
    }
}
