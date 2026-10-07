package com.example.streamdav.catalog;

import com.example.streamdav.catalog.LibraryScanner.ScanResult;
import com.example.streamdav.catalog.LibraryScanner.ScannedFile;
import com.example.streamdav.library.MediaLibrary;
import com.example.streamdav.library.RemoteFile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryScannerTest {
    private static final URI ROOT = URI.create("https://dav.example.com/");

    /** An in-memory server; folder paths end with "/", and a listing of "broken/" fails. */
    private static final class FakeLibrary implements MediaLibrary {
        final Map<URI, List<RemoteFile>> folders = new HashMap<>();
        final List<URI> listed = new ArrayList<>();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();

        FakeLibrary add(String... paths) {
            for (String path : paths) {
                String parent = "";
                String[] parts = path.split("/");
                for (int i = 0; i < parts.length; i++) {
                    boolean directory = i < parts.length - 1 || path.endsWith("/");
                    String child = parent + encode(parts[i]) + (directory ? "/" : "");
                    RemoteFile entry = new RemoteFile(ROOT.resolve(child), parts[i], directory, directory ? -1 : 1000, null);
                    List<RemoteFile> entries = folders.computeIfAbsent(ROOT.resolve(parent), uri -> new ArrayList<>());
                    if (!entries.contains(entry)) {
                        entries.add(entry);
                    }
                    parent = child;
                }
            }
            return this;
        }

        private static String encode(String name) {
            return name.replace(" ", "%20").replace("[", "%5B").replace("]", "%5D");
        }

        @Override
        public URI root() {
            return ROOT;
        }

        @Override
        public List<RemoteFile> list(URI folder) throws IOException, InterruptedException {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(5);
                synchronized (listed) {
                    listed.add(folder);
                }
                if (folder.getPath().endsWith("/broken/")) {
                    throw new IOException("HTTP 500");
                }
                return folders.getOrDefault(folder, List.of());
            } finally {
                inFlight.decrementAndGet();
            }
        }

        @Override
        public URI streamUrl(RemoteFile file) {
            return file.uri();
        }
    }

    private static List<String> names(ScanResult result) {
        return result.files().stream().map(file -> file.file().name()).sorted().toList();
    }

    @Test
    void findsVideosAtEveryDepthWithTheirFolders() throws Exception {
        FakeLibrary library = new FakeLibrary().add(
                "Movies/Arrival (2016)/Arrival.2016.1080p.mkv",
                "TV/The Expanse/Season 01/The.Expanse.S01E01.mkv",
                "TV/The Expanse/Season 01/The.Expanse.S01E02.mkv",
                "Anime/[SubsPlease] Frieren - 01 (1080p).mkv");

        ScanResult result = new LibraryScanner(library).scan(ROOT);

        assertEquals(List.of("Arrival.2016.1080p.mkv", "The.Expanse.S01E01.mkv", "The.Expanse.S01E02.mkv",
                "[SubsPlease] Frieren - 01 (1080p).mkv"), names(result));
        ScannedFile episode = result.files().stream()
                .filter(file -> file.file().name().equals("The.Expanse.S01E02.mkv")).findFirst().orElseThrow();
        assertEquals(List.of("TV", "The Expanse", "Season 01"), episode.folders());
        assertEquals(List.of(2), episode.release().episodes());
        assertTrue(result.failedFolders().isEmpty());
    }

    @Test
    void skipsSamplesExtrasHiddenFilesAndNonVideo() throws Exception {
        FakeLibrary library = new FakeLibrary().add(
                "The.Matrix.1999.1080p/The.Matrix.1999.1080p.mkv",
                "The.Matrix.1999.1080p/sample.mkv",
                "The.Matrix.1999.1080p/The.Matrix.1999.nfo",
                "The.Matrix.1999.1080p/Subs/English.srt",
                "The.Matrix.1999.1080p/Extras/Making Of.mkv",
                ".trash/old.mkv",
                "music/song.mp3");

        ScanResult result = new LibraryScanner(library).scan(ROOT);

        assertEquals(List.of("The.Matrix.1999.1080p.mkv"), names(result));
        assertFalse(library.listed.contains(ROOT.resolve("The.Matrix.1999.1080p/Extras/")),
                "extras folders aren't even opened");
        assertFalse(library.listed.contains(ROOT.resolve(".trash/")), "nor hidden ones");
    }

    @Test
    void carriesOnPastFoldersThatFailToList() throws Exception {
        FakeLibrary library = new FakeLibrary().add("broken/Movie.2020.mkv", "fine/Other.Movie.2021.mkv");

        ScanResult result = new LibraryScanner(library).scan(ROOT);

        assertEquals(List.of("Other.Movie.2021.mkv"), names(result));
        assertEquals(List.of(ROOT.resolve("broken/")), result.failedFolders());
    }

    @Test
    void scansOnlyBelowTheChosenSource() throws Exception {
        FakeLibrary library = new FakeLibrary().add("Movies/Heat (1995)/Heat.mkv", "TV/Show/Show.S01E01.mkv");

        ScanResult result = new LibraryScanner(library).scan(ROOT.resolve("Movies/"));

        assertEquals(List.of("Heat.mkv"), names(result));
        assertEquals(List.of("Heat (1995)"), result.files().getFirst().folders(), "paths are relative to the source");
        assertEquals("Heat", result.files().getFirst().release().title());
    }

    @Test
    void limitsConcurrentListings() throws Exception {
        FakeLibrary library = new FakeLibrary();
        for (int i = 0; i < 30; i++) {
            library.add("Show " + i + "/Show.S01E01.mkv");
        }

        ScanResult result = new LibraryScanner(library).scan(ROOT);

        assertEquals(30, result.files().size());
        assertTrue(library.maxInFlight.get() <= 4, "at most 4 listings at once, saw " + library.maxInFlight.get());
        assertTrue(library.maxInFlight.get() > 1, "but they do run in parallel");
    }
}
