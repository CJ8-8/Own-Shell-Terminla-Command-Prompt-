    public Result autocomplete(Shell shell, StringBuilder line, boolean bellRang) {
        final var beginning = line.toString();

        // Only autocomplete the first token
        if (beginning.isBlank() || beginning.contains(" ")) {
            return Result.FOUND;
        }

        List<String> builtins = List.of("echo", "exit");

        List<String> matches = builtins.stream()
                .filter(b -> b.startsWith(beginning))
                .sorted(SHORTEST_FIRST)
                .toList();

        if (matches.isEmpty()) {
            return Result.NONE;
        }

        // Single match => complete + trailing space
        if (matches.size() == 1) {
            String full = matches.get(0);
            String suffix = full.substring(beginning.length());
            writeCandidate(line, suffix, false);
            return Result.FOUND;
        }

        // Multiple matches => add common prefix of the remaining suffixes
        final var suffixCandidates = matches.stream()
                .map(m -> m.substring(beginning.length()))
                .collect(Collectors.toCollection(() -> new TreeSet<>(SHORTEST_FIRST)));

        final var prefix = findSharedPrefix(suffixCandidates);
        if (!prefix.isEmpty()) {
            writeCandidate(line, prefix, true);
            return Result.MORE;
        }

        // No shared prefix
        if (bellRang) {
            System.out.print(matches.stream().collect(Collectors.joining("  ", "\n", "\n")));
            System.out.print("$ ");
            System.out.print(beginning);
            System.out.flush();
        }

        return Result.MORE;
    }