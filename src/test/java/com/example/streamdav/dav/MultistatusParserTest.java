package com.example.streamdav.dav;

import com.example.streamdav.library.RemoteFile;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultistatusParserTest {

    private static List<RemoteFile> parse(String xml, String requestUri) throws IOException {
        return MultistatusParser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), URI.create(requestUri));
    }

    private static RemoteFile named(List<RemoteFile> files, String name) {
        return files.stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void parsesApacheModDavListing() throws IOException {
        // mod_dav binds live properties to a second prefix (lp1) for the same DAV: namespace.
        String xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <D:multistatus xmlns:D="DAV:" xmlns:ns0="DAV:">
                <D:response xmlns:lp1="DAV:" xmlns:lp2="http://apache.org/dav/props/">
                <D:href>/dav/Movies/</D:href>
                <D:propstat><D:prop>
                <lp1:resourcetype><D:collection/></lp1:resourcetype>
                <lp1:getlastmodified>Wed, 30 Sep 2026 18:20:00 GMT</lp1:getlastmodified>
                </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                <D:propstat><D:prop><D:getcontentlength/></D:prop><D:status>HTTP/1.1 404 Not Found</D:status></D:propstat>
                </D:response>
                <D:response xmlns:lp1="DAV:" xmlns:lp2="http://apache.org/dav/props/">
                <D:href>/dav/Movies/Big%20Buck%20Bunny.mp4</D:href>
                <D:propstat><D:prop>
                <lp1:resourcetype/>
                <lp1:getcontentlength>276134947</lp1:getcontentlength>
                <lp1:getlastmodified>Wed, 30 Sep 2026 18:20:00 GMT</lp1:getlastmodified>
                </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                </D:response>
                <D:response xmlns:lp1="DAV:">
                <D:href>/dav/Movies/Extras/</D:href>
                <D:propstat><D:prop><lp1:resourcetype><D:collection/></lp1:resourcetype></D:prop>
                <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                </D:response>
                </D:multistatus>
                """;

        List<RemoteFile> files = parse(xml, "https://nas.local/dav/Movies/");

        assertEquals(2, files.size(), "the listed folder itself is not a member");
        RemoteFile movie = named(files, "Big Buck Bunny.mp4");
        assertEquals(URI.create("https://nas.local/dav/Movies/Big%20Buck%20Bunny.mp4"), movie.uri());
        assertFalse(movie.directory());
        assertEquals(276134947L, movie.size());
        assertEquals(Instant.parse("2026-09-30T18:20:00Z"), movie.lastModified());

        RemoteFile extras = named(files, "Extras");
        assertTrue(extras.directory());
        assertEquals(-1, extras.size());
        assertEquals(URI.create("https://nas.local/dav/Movies/Extras/"), extras.uri());
    }

    @Test
    void parsesNextcloudListingAndDecodesNames() throws IOException {
        String xml = """
                <?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:oc="http://owncloud.org/ns">
                 <d:response>
                  <d:href>/remote.php/dav/files/mitul/Music/</d:href>
                  <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                   <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                 </d:response>
                 <d:response>
                  <d:href>/remote.php/dav/files/mitul/Music/01%20Intro.mp3</d:href>
                  <d:propstat><d:prop>
                   <d:resourcetype/><d:getcontentlength>4100000</d:getcontentlength>
                   <oc:fileid>123</oc:fileid>
                  </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                  <d:propstat><d:prop><d:getlastmodified/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
                 </d:response>
                 <d:response>
                  <d:href>/remote.php/dav/files/mitul/Music/Caf%C3%A9%20Live/</d:href>
                  <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                   <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                 </d:response>
                </d:multistatus>
                """;

        // Requested without the trailing slash the server uses for the folder's own entry.
        List<RemoteFile> files = parse(xml, "https://cloud.example.com/remote.php/dav/files/mitul/Music");

        assertEquals(List.of("01 Intro.mp3", "Café Live"), files.stream().map(RemoteFile::name).toList());
        RemoteFile song = named(files, "01 Intro.mp3");
        assertEquals(4100000L, song.size());
        assertNull(song.lastModified(), "properties reported as 404 are absent");
        assertTrue(named(files, "Café Live").directory());
    }

    @Test
    void rebasesAbsoluteHrefsOntoTheRequestedServer() throws IOException {
        // A server behind a reverse proxy may report its internal address, and some send raw spaces.
        String xml = """
                <multistatus xmlns="DAV:">
                  <response><href>http://internal:8080/media/</href></response>
                  <response>
                    <href>http://internal:8080/media/My Movie.mkv</href>
                    <propstat><prop><resourcetype/><getcontentlength>12</getcontentlength></prop>
                    <status>HTTP/1.1 200 OK</status></propstat>
                  </response>
                </multistatus>
                """;

        List<RemoteFile> files = parse(xml, "https://nas.example.com/media/");

        assertEquals(1, files.size());
        assertEquals(URI.create("https://nas.example.com/media/My%20Movie.mkv"), files.getFirst().uri());
        assertEquals("My Movie.mkv", files.getFirst().name());
    }

    @Test
    void fallsBackToTrailingSlashWithoutResourceType() throws IOException {
        String xml = """
                <d:multistatus xmlns:d="DAV:">
                  <d:response><d:href>/share/Shows/</d:href></d:response>
                  <d:response><d:href>/share/readme.txt</d:href></d:response>
                </d:multistatus>
                """;

        List<RemoteFile> files = parse(xml, "http://nas/share/");

        assertTrue(named(files, "Shows").directory());
        assertFalse(named(files, "readme.txt").directory());
    }

    @Test
    void toleratesMalformedProperties() throws IOException {
        String xml = """
                <d:multistatus xmlns:d="DAV:">
                  <d:response>
                    <d:href>/share/clip.mp4</d:href>
                    <d:propstat><d:prop>
                      <d:getcontentlength>lots</d:getcontentlength>
                      <d:getlastmodified>yesterday</d:getlastmodified>
                    </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                  </d:response>
                  <d:response><d:href></d:href></d:response>
                </d:multistatus>
                """;

        List<RemoteFile> files = parse(xml, "http://nas/share/");

        assertEquals(1, files.size());
        assertEquals(-1, files.getFirst().size());
        assertNull(files.getFirst().lastModified());
    }

    @Test
    void acceptsIsoDates() throws IOException {
        String xml = """
                <d:multistatus xmlns:d="DAV:"><d:response><d:href>/a.mp4</d:href>
                <d:propstat><d:prop><d:getlastmodified>2026-09-30T18:20:00Z</d:getlastmodified></d:prop>
                <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>
                """;

        assertEquals(Instant.parse("2026-09-30T18:20:00Z"), parse(xml, "http://nas/").getFirst().lastModified());
    }

    @Test
    void rejectsDoctypesToPreventEntityExpansion() {
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE d:multistatus [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <d:multistatus xmlns:d="DAV:"><d:response><d:href>/&xxe;</d:href></d:response></d:multistatus>
                """;

        assertThrows(IOException.class, () -> parse(xml, "http://nas/"));
    }

    @Test
    void rejectsNonXml() {
        assertThrows(IOException.class, () -> parse("<html><body>Login", "http://nas/"));
    }
}
