package org.abitware.docfinder.search;

public enum SearchScope {
    ALL("Name + Content"),
    NAME("Name Only"),
    CONTENT("Content Only"),
    FOLDER("Folders");

    private final String label;

    SearchScope(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
