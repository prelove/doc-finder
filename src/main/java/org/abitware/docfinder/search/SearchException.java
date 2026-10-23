package org.abitware.docfinder.search;

/**
 * Typed search failure. Carries a stable {@link Code} that the desktop and
 * HTTP layers map to fixed user-facing text/status; the message is for logs
 * only and must not be shown verbatim to users.
 */
public class SearchException extends RuntimeException {

    public enum Code {
        INVALID_DIRECTORY,
        DIRECTORY_NOT_INDEXED,
        SOURCE_CONFIG_UNAVAILABLE,
        INDEX_REBUILD_REQUIRED,
        SCOPE_CHECK_FAILED,
        SEARCH_FAILED,
        INVALID_QUERY,
        INDEX_UNAVAILABLE
    }

    private final Code code;

    public SearchException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public SearchException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
