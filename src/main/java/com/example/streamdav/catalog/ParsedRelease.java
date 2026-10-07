package com.example.streamdav.catalog;

import java.util.List;

/**
 * What a file name says about the video in it.
 *
 * @param title    cleaned-up movie or show title, as it would be searched for
 * @param year     release year, when the name has one
 * @param season   season number for episodes; null when episodes are numbered absolutely (common for anime)
 * @param episodes episode numbers, in order (more than one for multi-episode files); empty for movies
 * @param anime    the name looks like an anime release ("[Group] Title - 12"); confirmed later by metadata
 */
public record ParsedRelease(Kind kind, String title, Integer year, Integer season, List<Integer> episodes, boolean anime) {

    public enum Kind { MOVIE, EPISODE }

    public ParsedRelease {
        episodes = List.copyOf(episodes);
    }

    static ParsedRelease movie(String title, Integer year) {
        return new ParsedRelease(Kind.MOVIE, title, year, null, List.of(), false);
    }

    static ParsedRelease episode(String title, Integer year, Integer season, List<Integer> episodes, boolean anime) {
        return new ParsedRelease(Kind.EPISODE, title, year, season, episodes, anime);
    }

    /** True for episodes numbered across the whole show rather than within a season. */
    public boolean absolute() {
        return kind == Kind.EPISODE && season == null;
    }
}
