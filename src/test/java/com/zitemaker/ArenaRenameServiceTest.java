package com.zitemaker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArenaRenameServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void renameMovesRuntimeKeyFileReferenceAndDirtyState() throws IOException {
        Path oldFile = Files.writeString(tempDir.resolve("oldArena.datc"), "arena-data");
        Path newFile = tempDir.resolve("newArena.datc");
        Map<String, Object> arenas = new HashMap<>();
        Object arena = new Object();
        arenas.put("oldArena", arena);
        Set<String> dirtyArenas = new HashSet<>();
        AtomicReference<Path> storedFile = new AtomicReference<>(oldFile);

        ArenaRenameService.rename(new ArenaRenameService.Request<>(
                "oldArena",
                "newArena",
                arenas,
                dirtyArenas,
                oldFile,
                newFile,
                storedFile::set
        ));

        assertFalse(arenas.containsKey("oldArena"));
        assertEquals(arena, arenas.get("newArena"));
        assertFalse(Files.exists(oldFile));
        assertEquals("arena-data", Files.readString(newFile));
        assertEquals(newFile, storedFile.get());
        assertFalse(dirtyArenas.contains("oldArena"));
        assertTrue(dirtyArenas.contains("newArena"));
    }

    @Test
    void renameRejectsUnsafeTargetFileNames() throws IOException {
        Path oldFile = Files.writeString(tempDir.resolve("arena.datc"), "arena-data");
        Map<String, Object> arenas = new HashMap<>();
        arenas.put("arena", new Object());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                ArenaRenameService.rename(new ArenaRenameService.Request<>(
                        "arena",
                        "../newArena",
                        arenas,
                        new HashSet<>(),
                        oldFile,
                        tempDir.resolve("newArena.datc"),
                        ignored -> {
                        }
                )));

        assertEquals("Arena names may only contain letters, numbers, underscores, and hyphens.", exception.getMessage());
        assertTrue(arenas.containsKey("arena"));
        assertTrue(Files.exists(oldFile));
    }
}
