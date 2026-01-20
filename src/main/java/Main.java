import java.io.File;
import java.util.Scanner;

public class Main {
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
            if (command.equals("echo")) {
                System.out.println();
                continue;
            }
            if (command.startsWith("echo ")) {
                System.out.println(command.substring(5));
                continue;
            }
            if (command.equals("type") || command.startsWith("type ")) {
                String[] parts = command.split("\\s+", 2);
                if (parts.length < 2 || parts[1].isBlank()) {
                    System.out.println("type: not found");
                    continue;
                }
                String target = parts[1].trim();

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
            if (command.isEmpty()) {
                continue;
            }

            System.out.println(command + ": command not found");
        }
    }
}
