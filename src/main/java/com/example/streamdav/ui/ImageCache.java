package com.example.streamdav.ui;

import javafx.application.Platform;
import javafx.scene.image.Image;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/**
 * Artwork from the metadata providers, downloaded once to the cache folder and decoded at the size it's shown at.
 * Recently shown images stay in memory so scrolling back doesn't decode them again.
 */
final class ImageCache implements AutoCloseable {
    private static final Logger log = LogManager.getLogger(ImageCache.class);
    private static final int MEMORY_ENTRIES = 400;
    private static final int CONCURRENT_DOWNLOADS = 6;

    private final Path dir;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore downloads = new Semaphore(CONCURRENT_DOWNLOADS);
    /** Downloads in flight, so a poster shown twice at once is fetched once. */
    private final Map<String, Object> inFlight = new ConcurrentHashMap<>();
    private final Map<String, Image> memory = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
            return size() > MEMORY_ENTRIES;
        }
    };

    ImageCache(Path dir) {
        this.dir = dir;
    }

    /**
     * Calls {@code onLoaded} on the JavaFX thread with the image, decoded to fit {@code width} by {@code height};
     * immediately when it's in memory. Nothing is called if it can't be fetched, so the placeholder stays.
     */
    void load(String url, double width, double height, Consumer<Image> onLoaded) {
        if (url == null) {
            return;
        }
        String key = url + "@" + (int) width + "x" + (int) height;
        Image cached = memory.get(key);
        if (cached != null) {
            onLoaded.accept(cached);
            return;
        }
        executor.execute(() -> {
            Path file = dir.resolve(fileName(url));
            try {
                if (!Files.exists(file)) {
                    download(url, file);
                }
            } catch (IOException e) {
                log.debug("Could not fetch artwork {}: {}", url, e.getMessage());
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Image image = new Image(file.toUri().toString(), width, height, true, true, false);
            if (image.isError()) {
                log.debug("Could not decode artwork {}", url);
                return;
            }
            Platform.runLater(() -> {
                memory.put(key, image);
                onLoaded.accept(image);
            });
        });
    }

    private void download(String url, Path file) throws IOException, InterruptedException {
        Object lock = inFlight.computeIfAbsent(url, ignored -> new Object());
        synchronized (lock) {
            try {
                if (Files.exists(file)) {
                    return;
                }
                downloads.acquire();
                try {
                    HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofInputStream());
                    try (InputStream body = response.body()) {
                        if (response.statusCode() != 200) {
                            throw new IOException("HTTP " + response.statusCode());
                        }
                        Files.createDirectories(dir);
                        Path partial = Files.createTempFile(dir, "download-", ".part");
                        try {
                            Files.copy(body, partial, StandardCopyOption.REPLACE_EXISTING);
                            Files.move(partial, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        } finally {
                            Files.deleteIfExists(partial);
                        }
                    }
                } finally {
                    downloads.release();
                }
            } finally {
                inFlight.remove(url);
            }
        }
    }

    private static String fileName(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            String path = URI.create(url).getPath();
            String extension = path != null && path.matches(".*\\.(jpe?g|png|webp)$")
                    ? path.substring(path.lastIndexOf('.')) : ".img";
            return HexFormat.of().formatHex(digest, 0, 16) + extension;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
