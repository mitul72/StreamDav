package com.example.streamdav.metadata;

import java.time.LocalDate;

/**
 * One episode of a show, as the provider numbers it.
 *
 * @param season   0 for specials
 * @param stillUrl a frame from the episode, or null
 * @param airDate  null when unknown
 */
public record EpisodeInfo(int season, int episode, String title, String overview, String stillUrl, LocalDate airDate) {
}
