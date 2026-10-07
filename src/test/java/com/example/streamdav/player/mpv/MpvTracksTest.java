package com.example.streamdav.player.mpv;

import com.example.streamdav.player.Track;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MpvTracksTest {

    @Test
    void namesLanguagesFromTwoAndThreeLetterCodes() {
        assertEquals("English", Languages.displayName("eng", Locale.ENGLISH));
        assertEquals("English", Languages.displayName("en", Locale.ENGLISH));
        assertEquals("French", Languages.displayName("fre", Locale.ENGLISH), "bibliographic code");
        assertEquals("French", Languages.displayName("fra", Locale.ENGLISH), "terminology code");
        assertEquals("German", Languages.displayName("GER", Locale.ENGLISH));
        assertEquals("Japanese", Languages.displayName("jpn", Locale.ENGLISH));
        assertEquals("qaa", Languages.displayName("qaa", Locale.ENGLISH), "unknown codes are shown as is");
        assertNull(Languages.displayName("und", Locale.ENGLISH));
        assertNull(Languages.displayName("", Locale.ENGLISH));
    }

    @Test
    void labelsCombineTitleLanguageAndCodec() {
        assertEquals("Commentary · English · AC3", MpvTracks.label("Commentary", "eng", "ac3", false, 2, Locale.ENGLISH));
        assertEquals("English · SUBRIP", MpvTracks.label(null, "eng", "subrip", false, 1, Locale.ENGLISH));
        assertEquals("English SDH · SUBRIP", MpvTracks.label("English SDH", "eng", "subrip", false, 1, Locale.ENGLISH),
                "the language isn't repeated when the title already names it");
        assertEquals("French · Forced · ASS", MpvTracks.label("", "fre", "ass", true, 3, Locale.ENGLISH));
        assertEquals("Track 4", MpvTracks.label(null, null, null, false, 4, Locale.ENGLISH));
    }

    @Test
    void readsAudioAndSubtitleTracksFromTheTrackList() {
        Map<String, String> properties = Map.ofEntries(
                entry("track-list/count", "4"),
                entry("track-list/0/type", "video"), entry("track-list/0/id", "1"),
                entry("track-list/1/type", "audio"), entry("track-list/1/id", "1"), entry("track-list/1/lang", "eng"),
                entry("track-list/1/title", "Surround 5.1"), entry("track-list/1/codec", "ac3"),
                entry("track-list/1/selected", "yes"),
                entry("track-list/2/type", "audio"), entry("track-list/2/id", "2"), entry("track-list/2/lang", "eng"),
                entry("track-list/2/title", "Commentary"), entry("track-list/2/codec", "ac3"),
                entry("track-list/2/selected", "no"),
                entry("track-list/3/type", "sub"), entry("track-list/3/id", "1"), entry("track-list/3/lang", "ger"),
                entry("track-list/3/codec", "subrip"), entry("track-list/3/forced", "yes"),
                entry("track-list/3/selected", "no"));

        List<Track> tracks = MpvTracks.read(properties::get, Locale.ENGLISH);

        assertEquals(List.of(
                new Track(Track.Kind.AUDIO, "1", "Surround 5.1 · English · AC3", true),
                new Track(Track.Kind.AUDIO, "2", "Commentary · English · AC3", false),
                new Track(Track.Kind.SUBTITLE, "1", "German · Forced · SUBRIP", false)), tracks);
    }

    @Test
    void anEmptyOrMissingTrackListHasNoTracks() {
        assertEquals(List.of(), MpvTracks.read(name -> null, Locale.ENGLISH));
    }
}
