package com.example.streamdav.catalog;

import com.example.streamdav.library.MediaKind;
import com.example.streamdav.library.MediaLibrary;
import com.example.streamdav.library.RemoteFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Walks a library source folder and parses every video file under it. */
public final class LibraryScanner {
    private static final Logger log = LogManager.getLogger(LibraryScanner.class);
    /** Folders deeper than this are almost certainly not part of a media library. */
    private static final int MAX_DEPTH = 8;
    /** Folder listings in flight at once; servers such as Real-Debrid's rate-limit bursts of PROPFINDs. */
    private static final int CONCURRENT_LISTINGS = 4;

    /**
     * @param folders the folders between the source and the file, outermost first
     */
    public record ScannedFile(RemoteFile file, List<String> folders, ParsedRelease release) {
        public ScannedFile {
            folders = List.copyOf(folders);
        }
    }

    /**
     * @param failedFolders folders that couldn't be listed; the rest of the scan carried on without them
     */
    public record ScanResult(List<ScannedFile> files, List<URI> failedFolders) {
    }

    private record Folder(URI uri, List<String> path) {
    }

    private record Listing(Folder folder, List<RemoteFile> entries) {
    }

    private final MediaLibrary library;

    public LibraryScanner(MediaLibrary library) {
        this.library = library;
    }

    /** Scans {@code source} and everything below it. Blocks; interrupting the thread cancels the scan. */
    public ScanResult scan(URI source) throws InterruptedException {
        List<ScannedFile> files = new ArrayList<>();
        List<URI> failed = new ArrayList<>();
        Set<URI> seen = new HashSet<>();
        List<Folder> level = List.of(new Folder(source, List.of()));
        seen.add(source);
        try (ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_LISTINGS,
                Thread.ofVirtual().name("library-scan-", 0).factory())) {
            for (int depth = 0; depth <= MAX_DEPTH && !level.isEmpty(); depth++) {
                List<Callable<Listing>> listings = level.stream()
                        .<Callable<Listing>>map(folder -> () -> new Listing(folder, library.list(folder.uri())))
                        .toList();
                List<Folder> next = new ArrayList<>();
                List<Future<Listing>> results = executor.invokeAll(listings);
                for (int i = 0; i < results.size(); i++) {
                    Listing listing;
                    try {
                        listing = results.get(i).get();
                    } catch (ExecutionException e) {
                        URI folder = level.get(i).uri();
                        log.warn("Could not list {}: {}", folder, e.getCause() instanceof IOException io ? io.getMessage() : e.getCause());
                        failed.add(folder);
                        continue;
                    }
                    for (RemoteFile entry : listing.entries()) {
                        if (entry.name().startsWith(".")) {
                            continue;
                        }
                        if (entry.directory()) {
                            if (!ReleaseParser.isExtrasFolder(entry.name()) && seen.add(entry.uri())) {
                                next.add(new Folder(entry.uri(), append(listing.folder().path(), entry.name())));
                            }
                        } else if (isLibraryVideo(entry, listing.folder().path())) {
                            List<String> path = listing.folder().path();
                            files.add(new ScannedFile(entry, path, ReleaseParser.parse(entry.name(), path)));
                        }
                    }
                }
                level = next;
            }
            if (!level.isEmpty()) {
                log.info("Stopped scanning {} at {} levels deep", source, MAX_DEPTH);
            }
        }
        return new ScanResult(files, failed);
    }

    private static boolean isLibraryVideo(RemoteFile file, List<String> folders) {
        return MediaKind.of(file.name()) == MediaKind.VIDEO && !ReleaseParser.isExtra(file.name(), folders);
    }

    private static List<String> append(List<String> path, String name) {
        List<String> extended = new ArrayList<>(path);
        extended.add(name);
        return extended;
    }
}
