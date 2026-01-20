import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.io.File;
import java.util.Scanner;
import java.io.FileOutputStream;
import java.io.PrintStream;

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

            // Handle stdout redirection: > file  OR  1> file
            String redirectOutFile = null;
            for (int i = 0; i < tokens.size(); i++) {
                String t = tokens.get(i);
                if (t.equals(">") || t.equals("1>")) {
                    if (i + 1 < tokens.size()) {
                        redirectOutFile = tokens.get(i + 1);
                        // remove operator and filename
                        tokens.remove(i + 1);
                        tokens.remove(i);
                    }
                    break;
                }
            }

            // re-read program after potential token removal
            if (tokens.isEmpty()) {
                continue;
            }
            program = tokens.get(0);

            if (program.equals("echo")) {
                PrintStream originalOut = System.out;
                PrintStream fileOut = null;
                try {
                    if (redirectOutFile != null) {
                        fileOut = new PrintStream(new FileOutputStream(redirectOutFile, false));
                        System.setOut(fileOut);
                    }

                    if (tokens.size() == 1) {
                        System.out.println();
                    } else {
                        System.out.println(String.join(" ", tokens.subList(1, tokens.size())));
                    }
                } catch (IOException e) {
                    // if redirection fails, behave like normal output
                    originalOut.println("redirect: failed");
                } finally {
                    if (fileOut != null) fileOut.close();
                    System.setOut(originalOut);
                }
                continue;
            }
            if (program.equals("type")) {
                PrintStream originalOut = System.out;
                PrintStream fileOut = null;
                try {
                    if (redirectOutFile != null) {
                        fileOut = new PrintStream(new FileOutputStream(redirectOutFile, false));
                        System.setOut(fileOut);
                    }

                    do {
                        if (tokens.size() < 2) {
                            System.out.println("type: not found");
                            break;
                        }
                        String target = tokens.get(1);

                        // 1) Builtins
                        if (target.equals("echo") || target.equals("exit") || target.equals("type")) {
                            System.out.println(target + " is a shell builtin");
                            break;
                        }

                        // 2) Search PATH for executables
                        boolean found = false;
                        String pathEnv2 = System.getenv("PATH");
                        if (pathEnv2 != null && !pathEnv2.isBlank()) {
                            String[] dirs = pathEnv2.split(":");
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
                    } while (false);
                } catch (IOException e) {
                    originalOut.println("redirect: failed");
                } finally {
                    if (fileOut != null) fileOut.close();
                    System.setOut(originalOut);
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

            // Execute resolvedPath but force argv[0] to be the original program name
            List<String> execCommand = new ArrayList<>();
            execCommand.add("bash");
            execCommand.add("-lc");

            StringBuilder script = new StringBuilder();
            script.append("exec -a ");
            script.append("'").append(program.replace("'", "'\\''")).append("'");
            script.append(" ");
            script.append("'").append(resolvedPath.replace("'", "'\\''")).append("'");

            for (int i = 1; i < argsList.size(); i++) {
                script.append(" ");
                script.append("'").append(argsList.get(i).replace("'", "'\\''")).append("'");
            }

            execCommand.add(script.toString());

            File execFile = new File(resolvedPath);
            File execDir = execFile.getParentFile();

            ProcessBuilder pb = new ProcessBuilder(execCommand);
            pb.inheritIO();
            pb.directory(execDir);
            if (redirectOutFile != null) {
                pb.redirectOutput(new File(redirectOutFile));
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            }

            try {
                Process p = pb.start();
                p.waitFor();
            } catch (IOException | InterruptedException e) {
                System.out.println(program + ": command not found");
            }
        }
    }
}
