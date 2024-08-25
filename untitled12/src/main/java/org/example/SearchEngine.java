package org.example;

import java.io.File;
import java.io.IOException;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class SearchEngine {

    private static Map<String, Set<Path>> wordIndex = new ConcurrentHashMap<>();
    private static ExecutorService executorService = Executors.newFixedThreadPool(32, Thread.ofVirtual().factory());
    private static AtomicInteger counter = new AtomicInteger(0);

    private static ReferenceQueue<Map<String, Set<Path>>> referenceQueue = new ReferenceQueue<>();
    private static PhantomReference<Map<String, Set<Path>>> phantomReference = new PhantomReference<>(wordIndex, referenceQueue);
    private static ScheduledExecutorService gcScheduler = Executors.newScheduledThreadPool(1);
    private static void scheduleGarbageCollection() {
        gcScheduler.scheduleAtFixedRate(() -> {
            System.out.println("Triggering garbage collection...");
            System.gc();
        }, 0, 15, TimeUnit.SECONDS);

        gcScheduler.scheduleAtFixedRate(() -> {
            if (referenceQueue.poll() != null) {
                System.out.println("wordIndex is about to be collected!");
                // Perform cleanup or reinitialize the object
            }
        }, 0, 1, TimeUnit.SECONDS);
    }
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        scheduleGarbageCollection();

        String directory = "C:/test";
        measureExecutionTime(() -> {
            try {
                Path startPath = Paths.get(directory);
                Files.walkFileTree(startPath, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        indexFile(file);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                e.printStackTrace();
            }

            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(60, TimeUnit.MINUTES)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
        });
        System.out.println("Size of wordIndex: " + String.format("%,d", wordIndex.size()).replace(',', '_'));


        Map.Entry<String, Integer> mostFrequentKeyWithCount = getMostFrequentKeyWithCount();
        if (mostFrequentKeyWithCount != null) {
            System.out.println("Most frequent key: " + mostFrequentKeyWithCount.getKey() + " with count: " + String.format("%,d", mostFrequentKeyWithCount.getValue()).replace(',', '_'));
        } else {
            System.out.println("No keys found in the wordIndex.");
        }

        int totalValuesCount = getTotalValuesCount();
        System.out.println("Total number of values in the map: " + String.format("%,d", totalValuesCount).replace(',', '_'));

        while (true) {
            System.out.println("Enter your search terms separated by spaces: ");
            String searchTerms = scanner.nextLine();

            System.out.println("Enter mode (single, consecutive, anywhere): ");
            String mode = scanner.nextLine();

            String[] searchWords = searchTerms.split("[^\\p{L}+]");
            searchWords = Arrays.stream(searchWords)
                    .filter(s -> !s.isEmpty())
                    .map(String::toLowerCase)
                    .toArray(String[]::new);

            searchIndex(searchWords, mode);
        }
    }

    private static int getTotalValuesCount() {
        return wordIndex.values().stream()
                .mapToInt(Set::size)
                .sum();
    }
    private static void reinitializeExecutorService() {
        shutdownExecutorService();
        executorService = Executors.newFixedThreadPool(20);
    }
    private static void shutdownExecutorService() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.MINUTES)) {
                executorService.shutdownNow();
                if (!executorService.awaitTermination(60, TimeUnit.MINUTES)) {
                    System.err.println("ExecutorService did not terminate");
                }
            }
        } catch (InterruptedException ie) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    private static void indexFile(Path file) {
        CompletableFuture.runAsync(() -> {
            try {
                String content = new String(Files.readAllBytes(file));
                Arrays.stream(content.split("[^\\p{L}+]"))
                        .filter(s -> !s.isEmpty())
                        .forEach(word ->
                                wordIndex.computeIfAbsent(word.toLowerCase(), k -> Collections.synchronizedSet(new HashSet<>())).add(file)
                        );


            } catch (IOException e) {
                e.printStackTrace();
            }
        }, executorService);
    }

    public static void addFile(Path file) {
        indexFile(file);
    }

    private static Map.Entry<String, Integer> getMostFrequentKeyWithCount() {
        return wordIndex.entrySet().stream()
                .max(Comparator.comparingInt(entry -> entry.getValue().size()))
                .map(entry -> new AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue().size()))
                .orElse(null);
    }

    public static void processFile(String filePath) {
        Path path = Paths.get(filePath);
        if (Files.exists(path)) {
            addFile(path);
        } else {
            System.out.println("File does not exist: " + path);
        }
    }

    public static void processDirectory(String directoryPath) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(directoryPath))) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    processDirectory(entry.toString());
                } else {
                    processFile(entry.toString());
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void searchIndex(String[] searchWords, String mode) {
        Set<Path> resultFiles = ConcurrentHashMap.newKeySet();
        switch (mode) {
            case "single":
                Arrays.stream(searchWords).parallel().forEach(word -> {
                    Set<Path> files = wordIndex.get(word.toLowerCase());
                    if (files != null) {
                        resultFiles.addAll(files);
                    }
                });
                break;
            case "consecutive":
                Set<Path> uniqueFiles = ConcurrentHashMap.newKeySet();
                Arrays.stream(searchWords).parallel().forEach(word -> {
                    Set<Path> files = wordIndex.get(word.toLowerCase());
                    if (files != null) {
                        uniqueFiles.addAll(files);
                    }
                });
                uniqueFiles.parallelStream().forEach(file -> {
                    if (!resultFiles.contains(file)) {
                        if (containsConsecutiveWords(file, searchWords)) {
                            resultFiles.add(file);
                        }
                    }
                });
                break;
            case "anywhere":
                Set<Path> commonFiles = new HashSet<>();
                for (String word : searchWords) {
                    Set<Path> files = wordIndex.get(word.toLowerCase());
                    if (files != null) {
                        if (commonFiles.isEmpty()) {
                            commonFiles.addAll(files);
                        } else {
                            commonFiles.retainAll(files);
                        }
                    } else {
                        commonFiles.clear();
                        break;
                    }
                }
                resultFiles.addAll(commonFiles);
                break;
            default:
                System.out.println("Invalid mode. Use 'single', 'consecutive', or 'anywhere'.");
                return;
        }

        if (!resultFiles.isEmpty()) {
            resultFiles.forEach(file -> System.out.println("Found in file: " + file.toString()));
        } else {
            System.out.println("No files contain the terms: " + Arrays.toString(searchWords));
        }
    }

    private static boolean containsConsecutiveWords(Path file, String[] searchWords) {
        long startTime = System.currentTimeMillis();
        try {
            String content = new String(Files.readAllBytes(file)).toLowerCase();
            String[] words = content.split("[^\\p{L}+]");
            String[] filteredArray = Arrays.stream(words)
                    .filter(s -> !s.isEmpty())
                    .toList()
                    .toArray(new String[0]);
            String[] searchWordsFilteredArray = Arrays.stream(searchWords)
                    .filter(s -> !s.isEmpty())
                    .toList()
                    .toArray(new String[0]);
            int searchLength = searchWordsFilteredArray.length;
            for (int i = 0; i <= words.length - searchLength; i++) {
                boolean consecutive = true;
                for (int j = 0; j < searchLength; j++) {
                    if (!filteredArray[i + j].equals(searchWordsFilteredArray[j].toLowerCase())) {
                        consecutive = false;
                        break;
                    }
                }
                if (consecutive) {
                    long endTime = System.currentTimeMillis();
                    System.out.println("Time taken to search file " + file + ": " + (endTime - startTime) + " milliseconds");
                    return true;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        long endTime = System.currentTimeMillis();
        System.out.println("Time taken to search file " + file + ": " + (endTime - startTime) + " milliseconds");
        return false;
    }
    private static boolean containsAnyWords(Path file, String[] searchWords) {
        try {
            String content = new String(Files.readAllBytes(file)).toLowerCase();
            for (String word : searchWords) {
                if (content.contains(word.toLowerCase())) {
                    return true;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    private static void measureExecutionTime(Runnable task) {
        long startTime = System.currentTimeMillis();
        task.run();
        long endTime = System.currentTimeMillis();
        long elapsedTime = endTime - startTime;
        System.out.println("Execution time: " + elapsedTime + " milliseconds");
    }
}