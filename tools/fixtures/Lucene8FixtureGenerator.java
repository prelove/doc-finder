import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;

/** Compile and run with JDK 8 and lucene-core 8.11.2, never the app classpath. */
public final class Lucene8FixtureGenerator {
    public static void main(String[] args) throws Exception {
        Path base = Paths.get(args[0]);
        for (String name : new String[] {"index", "deleted-paths-index"}) {
            Files.createDirectories(base.resolve(name));
            try (FSDirectory directory = FSDirectory.open(base.resolve(name));
                    IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
                Document document = new Document();
                document.add(new StringField("path", "/legacy/hello.txt", Field.Store.YES));
                document.add(new TextField("content", "legacyfixture", Field.Store.YES));
                writer.addDocument(document);
                writer.commit();
            }
        }
    }
}
