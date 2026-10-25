package org.abitware.docfinder.search;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Supplies the configured source roots for directory-scope validation and
 * for the ghost filter's source-bounded reachability. At most once per
 * request: scoped requests share their validation snapshot with the ghost
 * filter; unscoped requests invoke it lazily on the first existence check;
 * blank-query requests must not invoke it.
 */
@FunctionalInterface
public interface SourceRootProvider {
    List<Path> loadRoots() throws IOException;
}
