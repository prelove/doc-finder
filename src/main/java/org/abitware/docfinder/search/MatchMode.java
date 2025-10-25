package org.abitware.docfinder.search;

public enum MatchMode {
    FUZZY("Fuzzy"),
    EXACT("Exact");

    private final String label;

    MatchMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}