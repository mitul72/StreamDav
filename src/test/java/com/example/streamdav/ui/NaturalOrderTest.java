package com.example.streamdav.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NaturalOrderTest {

    @Test
    void sortsNumbersByValue() {
        List<String> names = new ArrayList<>(List.of("Episode 10.mkv", "Episode 2.mkv", "Episode 1.mkv", "Episode 100.mkv"));
        names.sort(NaturalOrder.INSTANCE);
        assertEquals(List.of("Episode 1.mkv", "Episode 2.mkv", "Episode 10.mkv", "Episode 100.mkv"), names);
    }

    @Test
    void ignoresCase() {
        assertTrue(NaturalOrder.INSTANCE.compare("apple", "Banana") < 0);
        assertTrue(NaturalOrder.INSTANCE.compare("Apple", "banana") < 0);
    }

    @Test
    void handlesSeasonEpisodeNames() {
        List<String> names = new ArrayList<>(List.of("S01E10", "S02E01", "S01E09", "S01E1"));
        names.sort(NaturalOrder.INSTANCE);
        assertEquals(List.of("S01E1", "S01E09", "S01E10", "S02E01"), names);
    }

    @Test
    void shorterPrefixComesFirst() {
        assertTrue(NaturalOrder.INSTANCE.compare("Movie", "Movie 2") < 0);
    }

    @Test
    void isConsistentForEquivalentNames() {
        // Leading zeros and case only break ties, so the order stays total and symmetric.
        int forward = NaturalOrder.INSTANCE.compare("file01", "file1");
        int backward = NaturalOrder.INSTANCE.compare("file1", "file01");
        assertTrue(forward != 0);
        assertEquals(-Integer.signum(forward), Integer.signum(backward));
        assertEquals(0, NaturalOrder.INSTANCE.compare("same", "same"));
    }

    @Test
    void handlesNumbersLongerThanALong() {
        assertTrue(NaturalOrder.INSTANCE.compare("x99999999999999999999", "x100000000000000000000") < 0);
    }
}
