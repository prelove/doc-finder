package org.abitware.docfinder.ui.workers;

import java.util.List;
import javax.swing.SwingWorker;
import org.abitware.docfinder.search.FilterState;
import org.abitware.docfinder.search.MatchMode;
import org.abitware.docfinder.search.SearchRequest;
import org.abitware.docfinder.search.SearchResult;
import org.abitware.docfinder.search.SearchScope;
import org.abitware.docfinder.search.SearchService;

/**
 * 搜索工作器，在后台执行搜索任务
 */
public class SearchWorker extends SwingWorker<List<SearchResult>, Void> {
    private final long token;
    private final String query;
    private final FilterState filter;
    private final SearchScope scope;
    private final MatchMode matchMode;
    private final SearchService searchService;
    private final long startedAt = System.currentTimeMillis();
    private SearchWorkerListener listener;
    
    public interface SearchWorkerListener {
        void onSearchCompleted(long token, String query, List<SearchResult> results, long elapsedMs);
        void onSearchFailed(long token, String query, String errorMessage);
    }
    
    public SearchWorker(long token, String query, FilterState filter, SearchScope scope, 
                       MatchMode matchMode, SearchService searchService) {
        this.token = token;
        this.query = query;
        this.filter = filter;
        this.scope = (scope == null) ? SearchScope.ALL : scope;
        this.matchMode = (matchMode == null) ? MatchMode.FUZZY : matchMode;
        this.searchService = searchService;
    }
    
    public void setSearchWorkerListener(SearchWorkerListener listener) {
        this.listener = listener;
    }

    @Override
    protected List<SearchResult> doInBackground() {
        if (isCancelled() || searchService == null) {
            return java.util.Collections.emptyList();
        }
        
        SearchRequest request = new SearchRequest(query, 100, filter, scope, matchMode);
        return searchService.search(request);
    }

    @Override
    protected void done() {
        if (listener == null) return;
        
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
            listener.onSearchFailed(token, query, 
                cause != null ? cause.getMessage() : ex.getMessage());
            return;
        } catch (Exception ex) {
            listener.onSearchFailed(token, query, ex.getMessage());
            return;
        }
        
        long elapsed = Math.max(0L, System.currentTimeMillis() - startedAt);
        listener.onSearchCompleted(token, query, results, elapsed);
    }
}