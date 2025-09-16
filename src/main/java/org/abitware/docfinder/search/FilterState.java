package org.abitware.docfinder.search;

import java.util.*;
import java.util.stream.Collectors;

public class FilterState {
    public Set<String> exts = new LinkedHashSet<>(); // e.g. ["pdf","docx"]
    public Long fromEpochMs = null;                  // mtime >= from
    public Long toEpochMs = null;                    // mtime <= to

    public static Set<String> parseExts(String s) {
        if (s == null) return new LinkedHashSet<>();
        return Arrays.stream(s.split(","))
                .map(String::trim).map(String::toLowerCase)
                .filter(x -> !x.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
