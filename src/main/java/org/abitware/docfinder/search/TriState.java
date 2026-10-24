package org.abitware.docfinder.search;

/**
 * Three-state truncation verdict (D5/B-05): FALSE means "known no more",
 * UNKNOWN means the implementation cannot tell and MUST NOT be presented
 * or serialized as FALSE/complete.
 */
public enum TriState {
    TRUE,
    FALSE,
    UNKNOWN
}
