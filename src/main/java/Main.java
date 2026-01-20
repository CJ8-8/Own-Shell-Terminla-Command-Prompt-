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
            if (command.isEmpty()) {
                continue;
            }

            System.out.println(command + ": command not found");
        }
    }
}
