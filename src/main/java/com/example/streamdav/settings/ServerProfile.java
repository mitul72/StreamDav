package com.example.streamdav.settings;

/** A saved server. {@code password} is empty unless the user chose to remember it. */
public record ServerProfile(String id, String name, String url, String username, String password) {
}
