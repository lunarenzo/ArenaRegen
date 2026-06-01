package com.zitemaker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class ArenaRenameService {
    private static final String VALID_ARENA_NAME_PATTERN = "[A-Za-z0-9_-]+";
    private static final String INVALID_ARENA_NAME_MESSAGE =
            "Arena names may only contain letters, numbers, underscores, and hyphens.";

    private ArenaRenameService() {
    }

    public record Request<T>(
            String oldName,
            String newName,
            Map<String, T> arenas,
            Set<String> dirtyArenas,
            Path oldFile,
            Path newFile,
            Consumer<Path> updateStoredFile
    ) {
    }

    public static <T> void rename(Request<T> request) throws IOException {
        validateArenaName(request.oldName());
        validateArenaName(request.newName());

        T arena = request.arenas().get(request.oldName());
        if (arena == null) {
            throw new IllegalArgumentException("Arena '" + request.oldName() + "' does not exist.");
        }
        if (request.arenas().containsKey(request.newName())) {
            throw new IllegalArgumentException("Arena '" + request.newName() + "' already exists.");
        }
        if (!Files.exists(request.oldFile())) {
            throw new IOException("Arena data file '" + request.oldFile().getFileName() + "' does not exist.");
        }
        if (Files.exists(request.newFile())) {
            throw new IOException("Arena data file '" + request.newFile().getFileName() + "' already exists.");
        }

        Files.move(request.oldFile(), request.newFile());
        request.arenas().remove(request.oldName());
        request.arenas().put(request.newName(), arena);
        request.updateStoredFile().accept(request.newFile());
        request.dirtyArenas().remove(request.oldName());
        request.dirtyArenas().add(request.newName());
    }

    public static void validateArenaName(String arenaName) {
        if (arenaName == null || !arenaName.matches(VALID_ARENA_NAME_PATTERN)) {
            throw new IllegalArgumentException(INVALID_ARENA_NAME_MESSAGE);
        }
    }
}
