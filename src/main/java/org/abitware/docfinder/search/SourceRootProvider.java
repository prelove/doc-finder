package org.abitware.docfinder.search;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Supplies the configured source roots for directory-scope validation.
 * Called at most once per scoped request (snapshot semantics); unscoped and
 * blank-query requests must not invoke it.
 */
@FunctionalInterface
public interface SourceRootProvider {
    List<Path> loadRoots() throws IOException;
}
