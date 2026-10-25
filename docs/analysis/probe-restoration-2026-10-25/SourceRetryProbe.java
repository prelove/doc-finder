import org.abitware.docfinder.search.*;
import org.abitware.docfinder.index.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.*;
import org.apache.lucene.search.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.*;
public class SourceRetryProbe {
 static int count(Path idx)throws Exception{try(Directory d=FSDirectory.open(idx);DirectoryReader r=DirectoryReader.open(d)){return new IndexSearcher(r).count(new TermQuery(new Term("name_raw","retry-back.txt")));}}
 static SearchOutcome query(LuceneSearchService s,String q){return s.searchDetailed(new SearchRequest(q,10,null,SearchScope.NAME,MatchMode.FUZZY));}
 public static void main(String[] args)throws Exception{
  Path root=Paths.get(args[0]).toAbsolutePath(),src=root.resolve("source"),idx=root.resolve("index"),file=src.resolve("retry-back.txt"),park=root.resolve("parking/retry-back.txt");Files.createDirectories(src);Files.createDirectories(park.getParent());Files.write(src.resolve("seed.txt"),"body".getBytes("UTF-8"));Files.write(file,"body".getBytes("UTF-8"));try(LuceneIndexer i=new LuceneIndexer(idx,new IndexSettings())){i.indexFolder(src);}
  AtomicBoolean fail=new AtomicBoolean();AtomicInteger loads=new AtomicInteger();SourceRootProvider provider=()->{loads.incrementAndGet();if(fail.get())throw new IOException("controlled temporary source-config failure");return Collections.singletonList(src);};LuceneSearchService s=new LuceneSearchService(idx,new IndexSettings(),provider);
  try{Files.move(file,park);query(s,"retry-back");long end=System.currentTimeMillis()+8000;while(count(idx)!=0&&System.currentTimeMillis()<end)Thread.sleep(40);if(count(idx)!=0)throw new AssertionError("purge not complete");Thread.sleep(1800);fail.set(true);query(s,"seed");fail.set(false);int before=loads.get();Files.move(park,file);Thread.sleep(4000);System.out.println("PROBE source_config_retry fileExists="+Files.exists(file)+" indexedAfterRecovery="+count(idx)+" loadsBeforeIdle="+before+" loadsAfterIdle="+loads.get());}finally{s.close();}
 }
}
