package org.abitware.docfinder.ui.workers;

import java.util.Collections;
import java.util.List;
import javax.swing.SwingWorker;
import org.abitware.docfinder.search.*;

/**
 * Manages search execution with cancellation support.
 */
public class SearchExecutor {

    private SearchWorker activeWorker;
    private long searchSequence = 0L;
    private SearchService searchService;

    public SearchExecutor(SearchService searchService) {
        this.searchService = searchService;
    }

    public void setSearchService(SearchService service) {
        this.searchService = service;
    }

    /**
     * Execute a search request asynchronously.
     */
    public void executeSearch(String query, FilterState filter, SearchScope scope,
                             MatchMode matchMode, SearchCallback callback) {
        // Cancel any active search
        if (activeWorker != null) {
            activeWorker.cancel(true);
        }

        if (query == null || query.trim().isEmpty()) {
            callback.onEmpty();
            return;
        }

        if (searchService == null) {
            callback.onError(new Exception("Search service unavailable"));
            return;
        }

        long token = ++searchSequence;
        SearchWorker worker = new SearchWorker(token, query.trim(), filter, scope, matchMode, callback);
        activeWorker = worker;
        worker.execute();
    }

    /**
     * Worker for background search execution.
     */
    private class SearchWorker extends SwingWorker<List<SearchResult>, Void> {
        private final long token;
        private final String query;
        private final FilterState filter;
        private final SearchScope scope;
        private final MatchMode matchMode;
        private final SearchCallback callback;
        private final long startedAt = System.currentTimeMillis();

        SearchWorker(long token, String query, FilterState filter, SearchScope scope,
                    MatchMode matchMode, SearchCallback callback) {
            this.token = token;
            this.query = query;
            this.filter = filter;
            this.scope = (scope == null) ? SearchScope.ALL : scope;
            this.matchMode = (matchMode == null) ? MatchMode.FUZZY : matchMode;
            this.callback = callback;
        }

        @Override
        protected List<SearchResult> doInBackground() {
            if (isCancelled() || searchService == null) {
                return Collections.emptyList();
            }
            SearchRequest request = new SearchRequest(query, 100, filter, scope, matchMode);
            return searchService.search(request);
        }

        @Override
        protected void done() {
            // Check if this is still the current search
            if (token != searchSequence) {
                return;
            }

            if (activeWorker == this) {
                activeWorker = null;
            }

            if (isCancelled()) {
                return;
            }

            List<SearchResult> results;
            try {
                results = get();
            } catch (java.util.concurrent.CancellationException ex) {
                return;
            } catch (InterruptedException ex) {
                return;
            } catch (java.util.concurrent.ExecutionException ex) {
                Throwable cause = ex.getCause();
                callback.onError(cause != null ? (Exception) cause : ex);
                return;
            } catch (Exception ex) {
                callback.onError(ex);
                return;
            }

            // Check again in case sequence changed during execution
            if (token != searchSequence) {
                return;
            }

            long elapsed = Math.max(0L, System.currentTimeMillis() - startedAt);
            callback.onResults(query, results, elapsed);
        }
    }

    /**
     * Callback interface for search results.
     */
    public interface SearchCallback {
        void onResults(String query, List<SearchResult> results, long elapsedMs);
        void onError(Exception ex);
        void onEmpty();
    }
}

