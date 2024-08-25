package org.example;

import java.nio.file.Path;

public class WordOccurrence {
    private final Path filePath;
    private final int position;

    public WordOccurrence(Path filePath, int position) {
        this.filePath = filePath;
        this.position = position;
    }

    public Path getFilePath() {
        return filePath;
    }

    public int getPosition() {
        return position;
    }
}