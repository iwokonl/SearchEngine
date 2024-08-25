package org.example;

import morfologik.stemming.IStemmer;
import morfologik.stemming.WordData;
import morfologik.stemming.polish.PolishStemmer;

import java.io.IOException;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class Main {


    private static Map<String, Set<Path>> wordIndex = new ConcurrentHashMap<>(800_000,300_000/0.85f);
    private static ExecutorService executorService = Executors.newFixedThreadPool(32, Thread.ofVirtual().factory());

    private static ReferenceQueue<Map<String, Set<Path>>> referenceQueue = new ReferenceQueue<>();
    private static PhantomReference<Map<String, Set<Path>>> phantomReference = new PhantomReference<>(wordIndex, referenceQueue);
    private static ScheduledExecutorService gcScheduler = Executors.newScheduledThreadPool(1);
    private static AtomicInteger counter = new AtomicInteger(0);

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

    private static void reinitializeExecutorService() {
        shutdownExecutorService();
        executorService = Executors.newFixedThreadPool(20);
    }

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
        scheduleGarbageCollection();
        Scanner scanner = new Scanner(System.in);

        System.out.println("Wskaż katalog: ");
        String directory =scanner.nextLine();
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
            System.out.println("Shutting down executor service...");
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(60, TimeUnit.MINUTES)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
        });
        System.out.println("Calculing wordIndex.size");
        System.out.println("Size of wordIndex: " + String.format("%,d", wordIndex.size()).replace(',', '_'));

        System.out.println("Calculing getTotalValuesCount.size");
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

    private static void indexFile(Path file) {
        IStemmer stemmer = new PolishStemmer();

        CompletableFuture.runAsync(() -> {
            try {
                String content = new String(Files.readAllBytes(file));
                Arrays.stream(content.split("[^\\p{L}+]"))
                        .filter(s -> !s.isEmpty())
                        .forEach(word -> {
                            List<WordData> stems = stemmer.lookup(word.toLowerCase());
                            List<String> stemStrings = stems.stream()
                                    .map(stem -> stem.getStem().toString())
                                    .collect(Collectors.toList());
                            stemStrings.add(word.toLowerCase());

                            stemStrings.forEach(stem ->
                                    wordIndex.computeIfAbsent(stem.toLowerCase(), k -> Collections.synchronizedSet(new HashSet<>())).add(file)
                            );
                        });
                System.out.println(counter.incrementAndGet());
                System.out.println(counter.get());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }, executorService);
    }

    private static void searchIndex(String[] searchWords, String mode) {
        Set<Path> resultFiles = ConcurrentHashMap.newKeySet();
        Set<Path> checkedFiles = ConcurrentHashMap.newKeySet();
        IStemmer stemmer = new PolishStemmer();
        long startTime = System.currentTimeMillis();
        switch (mode) {
            case "single":
                Arrays.stream(searchWords).parallel().forEach(word -> {
                    List<WordData> stems = new ArrayList<>();
                    synchronized (stemmer) {
                        stems = stemmer.lookup(word);
                    }
                    List<String> stemStrings = stems.stream()
                            .map(stem -> stem.getStem().toString())
                            .collect(Collectors.toList());
                    stemStrings.add(word);

                    for (String stem : stemStrings) {
                        Set<Path> files = wordIndex.get(stem.toLowerCase());
                        if (files != null) {
                            resultFiles.addAll(files);
                        }
                    }
                });
                break;
            case "consecutive":
                Set<Path> paths = ConcurrentHashMap.newKeySet();
                List<String[]> gen= new ArrayList<>();
                if((searchWords.length <= 3)) {
                    gen = generateCombinationsWithStems(searchWords, stemmer);
                }
                Arrays.stream(searchWords).parallel().forEach(word -> {
                    Set<Path> files = wordIndex.get(word.toLowerCase());
                    if (files != null) {
                        if (paths.isEmpty()) {
                            paths.addAll(files);
                        } else {
                            paths.retainAll(files);
                        }
                    } else {
                        paths.clear();
                    }
                });
                if (searchWords.length>3){
                    paths.parallelStream().forEach(file -> {
                        if (containsConsecutiveWordsHashMap(file, searchWords)) {
                            resultFiles.add(file);
                        }
                    });
                }else {
                    for (String[] combination : gen) {
                        paths.parallelStream().forEach(file -> {
                            if (!resultFiles.contains(file)) {
                                if (containsConsecutiveWords(file, combination)) {
                                    resultFiles.add(file);
                                }
                            }
                        });

                    }
                }
                break;
            case "anywhere":
                Arrays.stream(searchWords).parallel().forEach(word -> {
                    List<WordData> stems = new ArrayList<>();
                    synchronized (stemmer) {
                        stems = stemmer.lookup(word);
                    }
                    ConcurrentLinkedQueue<String> stemStrings = new ConcurrentLinkedQueue<>();
                    stems.parallelStream()
                            .map(stem -> stem.getStem().toString())
                            .forEach(stemStrings::add);
                    stemStrings.add(word);
                    Set<Path> commonFiles = new HashSet<>();

                    for (String wordd : stemStrings) {
                        Set<Path> files = wordIndex.get(wordd.toLowerCase());
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
                });
                break;
            default:
                System.out.println("Invalid mode. Use 'single', 'consecutive', or 'anywhere'.");
                long endTime = System.currentTimeMillis();
                System.out.println("Time taken to search single phrase : " + (endTime - startTime) + " milliseconds");
                return;
        }

        if (!resultFiles.isEmpty()) {
            resultFiles.forEach(file -> System.out.println("Found in file: " + file.toString()));
            long endTime = System.currentTimeMillis();
            System.out.println("Time taken to search single phrase or anywhere : " + (endTime - startTime) + " milliseconds");
        } else {
            System.out.println("No files contain the terms: " + Arrays.toString(searchWords));
        }
    }

    private static List<String[]> generateCombinationsWithStems(String[] searchWords, IStemmer stemmer) {
        List<String[]> combinations = new ArrayList<>();
        generateCombinationsWithStemsHelper(searchWords, stemmer, 0, new ArrayList<>(), combinations);
        return combinations;
    }

    private static void generateCombinationsWithStemsHelper(String[] searchWords, IStemmer stemmer, int index, List<String> currentCombination, List<String[]> combinations) {
        if (index == searchWords.length) {
            combinations.add(currentCombination.toArray(new String[0]));
            return;
        }

        String word = searchWords[index];
        List<WordData> stems = stemmer.lookup(word);
        Set<String> stemStrings = stems.stream()
                .map(stem -> stem.getStem().toString())
                .collect(Collectors.toSet());
        stemStrings.add(word);

        for (String stem : stemStrings) {
            List<String> newCombination = new ArrayList<>(currentCombination);
            newCombination.add(stem);
            generateCombinationsWithStemsHelper(searchWords, stemmer, index + 1, newCombination, combinations);
        }
    }

    public static void addFile(Path file) {
        indexFile(file);
    }

    private static void addEntry(String word, IStemmer stemmer, Map<String, Set<String>> multiKeyMap) {
        List<WordData> stems = stemmer.lookup(word);
        if (!multiKeyMap.containsKey(word)) {
            multiKeyMap.put(word, new HashSet<>());
        }
        for (WordData stem : stems) {
            multiKeyMap.get(word).add(stem.getStem().toString());
        }
        multiKeyMap.get(word).add(word);
    }

    private static Set<String> getBaseWord(String key, Map<String, Set<String>> multiKeyMap) {
        if (multiKeyMap.containsKey(key)) {
            return multiKeyMap.get(key);
        } else {
            HashSet<String> set = new HashSet<>();
            set.add(key);
            return set;
        }
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
                    if (!words[i + j].equals(searchWordsFilteredArray[j].toLowerCase())) {
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
        System.out.println("Time taken to search file asd" + file + ": " + (endTime - startTime) + " milliseconds");
        return false;
    }
    private static boolean containsConsecutiveWordsHashMap(Path file, String[] searchWords) {
        long startTime = System.currentTimeMillis();
        try {
            String content = new String(Files.readAllBytes(file)).toLowerCase();
            String[] words = content.split("[^\\p{L}+]");
            String[] filteredArray = Arrays.stream(words)
                    .filter(s -> !s.isEmpty())
                    .toList()
                    .toArray(new String[0]);
            IStemmer stemmer = new PolishStemmer();
            Map<String, Set<String>> multiKeyMap = new HashMap<>();
            for (String string : filteredArray) {
                addEntry(string.toLowerCase(), stemmer, multiKeyMap);
            }

            int searchLength = searchWords.length;
            for (int i = 0; i <= filteredArray.length - searchLength; i++) {
                boolean consecutive = true;
                for (int j = 0; j < searchLength; j++) {
                    if (!getBaseWord(filteredArray[j + i], multiKeyMap).contains(searchWords[j])) {

                        consecutive = false;
                        break;
                    }
                }
                if (consecutive) {
                    long endTime = System.currentTimeMillis();
                    System.out.println("Time taken to search file ads" + file + ": " + (endTime - startTime) + " milliseconds");
                    return true;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        long endTime = System.currentTimeMillis();
        System.out.println("Time taken to search file asd" + file + ": " + (endTime - startTime) + " milliseconds");
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
        System.out.println("Map load time: " + String.format("%,d", elapsedTime).replace(',', '_') + " milliseconds");
    }
}