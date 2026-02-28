package org.abitware.docfinder.search;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SearchServiceNotifyTest {

    /** Default notifyIndexCommit() does not throw (default no-op implementation) */
    @Test
    void shouldNotifyWithDefaultNoOp() {
        SearchService svc = new SearchService() {
            @Override
            public List<SearchResult> search(SearchRequest request) {
                return java.util.Collections.emptyList();
            }
        };
        // Should not throw
        assertDoesNotThrow(() -> svc.notifyIndexCommit());
    }

    /** Custom implementations can override notifyIndexCommit() */
    @Test
    void shouldAllowOverridingNotifyIndexCommit() {
        AtomicInteger callCount = new AtomicInteger(0);

        SearchService svc = new SearchService() {
            @Override
            public List<SearchResult> search(SearchRequest request) {
                return java.util.Collections.emptyList();
            }

            @Override
            public void notifyIndexCommit() {
                callCount.incrementAndGet();
            }
        };

        svc.notifyIndexCommit();
        svc.notifyIndexCommit();
        assertEquals(2, callCount.get());
    }
}
