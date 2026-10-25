package org.abitware.docfinder.search;
import org.abitware.docfinder.index.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.*;
import org.apache.lucene.search.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.lang.reflect.Method;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.document.Field;
import org.abitware.docfinder.util.PathScope;
public class EvictedGuardProbe {
 static void write(Path p)throws Exception{Files.createDirectories(p.getParent());Files.write(p,"body".getBytes("UTF-8"));}
 static SearchOutcome search(LuceneSearchService s,String q,int limit){return s.searchDetailed(new SearchRequest(q,limit,null,SearchScope.NAME,MatchMode.FUZZY));}
 static void index(Path idx,Path... paths)throws Exception{Files.createDirectories(idx);try(Directory d=FSDirectory.open(idx);StandardAnalyzer a=new StandardAnalyzer();IndexWriter w=new IndexWriter(d,new IndexWriterConfig(a))){for(Path p:paths){Document doc=new Document();String n=p.getFileName().toString();doc.add(new StringField("path",p.toString(),Field.Store.YES));doc.add(new StringField("path_scope",PathScope.normalizeIndexedPath(p),Field.Store.NO));doc.add(new TextField("name",n,Field.Store.YES));doc.add(new StringField("name_raw",n.toLowerCase(Locale.ROOT),Field.Store.NO));doc.add(new StringField("kind","file",Field.Store.YES));w.addDocument(doc);}w.commit();}}
 static int count(Path idx,Path file)throws Exception{try(Directory d=FSDirectory.open(idx);DirectoryReader r=DirectoryReader.open(d)){return new IndexSearcher(r).count(new TermQuery(new Term("name_raw",file.getFileName().toString())));}}
 public static void main(String[] args)throws Exception{
  Path root=Paths.get(args[0]).toAbsolutePath().normalize(),src=root.resolve("source"),other=root.resolve("other-source"),idx=root.resolve("index");Files.createDirectories(src);Files.createDirectories(other);try(LuceneIndexer warm=new LuceneIndexer(root.resolve("warm"),new IndexSettings())){warm.commit();}
  Path seed=src.resolve("seed.txt");write(seed);List<Path> docs=new ArrayList<>();docs.add(seed);List<String> ghosts=new ArrayList<>();for(int i=0;i<65;i++){Path p=src.resolve(String.format("gone-%02d.txt",i));docs.add(p);ghosts.add(p.toString());}index(idx,docs.toArray(new Path[0]));Path target=docs.get(docs.size()-1);
  LuceneSearchService service=new LuceneSearchService(idx,new IndexSettings(),()->Arrays.asList(src,other));search(service,"seed",10);
  // Isolate deletion correctness; autonomous repair must not hide a wrong delete.
  java.lang.reflect.Field tf=LuceneSearchService.class.getDeclaredField("resurrectTimer");tf.setAccessible(true);((ScheduledExecutorService)tf.get(service)).shutdownNow();
  Method purge=LuceneSearchService.class.getDeclaredMethod("purgeGhostBatch",List.class);purge.setAccessible(true);java.lang.reflect.Field lf=IndexWriteCoordinator.class.getDeclaredField("WRITE_LOCK");lf.setAccessible(true);ReentrantLock lock=(ReentrantLock)lf.get(null);ExecutorService ex=Executors.newSingleThreadExecutor();AtomicReference<Future<?>> task=new AtomicReference<>();AtomicLong guard=new AtomicLong();
  try{IndexWriteCoordinator.run(()->{try{task.set(ex.submit(()->{try{purge.invoke(service,ghosts);}catch(Exception e){throw new RuntimeException(e);}}));long end=System.currentTimeMillis()+5000;while(!lock.hasQueuedThreads()&&System.currentTimeMillis()<end)Thread.sleep(10);if(!lock.hasQueuedThreads())throw new AssertionError("purge not waiting for lock");write(target);
    try(LuceneIndexer writer=new LuceneIndexer(idx,new IndexSettings())){writer.upsertFile(target);for(int i=0;i<4096;i++){Path d=other.resolve("dir-"+i);Files.createDirectory(d);writer.upsertFile(d);}writer.commit();}
    if(count(idx,target)!=1)throw new AssertionError("restored target missing before purge");guard.set(LuceneIndexer.upsertedAt(target.toString()));Thread.sleep(400);
   }catch(Exception e){throw new RuntimeException(e);}});task.get().get(15,TimeUnit.SECONDS);System.out.println("PROBE evicted_active_guard otherRealDirectoryUpserts=4096 fileExists="+Files.exists(target)+" guardAfterUpserts="+guard.get()+" indexedBeforeOldPurge=1 indexedAfterOldPurge="+count(idx,target));if(count(idx,target)!=1)throw new AssertionError("old cleanup deleted a newer document after guard eviction");}
  finally{ex.shutdownNow();service.close();}
 }
}
