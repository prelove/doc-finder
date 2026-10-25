package org.abitware.docfinder.search;
import org.abitware.docfinder.index.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.*;
import org.apache.lucene.search.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;
import java.security.Permission;
public class ClassificationRaceProbe {
 static void write(Path p)throws Exception{Files.createDirectories(p.getParent());Files.write(p,"body".getBytes("UTF-8"));}
 static void hold(CountDownLatch l){while(l.getCount()>0){try{l.await();}catch(InterruptedException ignored){}}}
 static void await(CountDownLatch l)throws Exception{if(!l.await(5,TimeUnit.SECONDS))throw new AssertionError("latch timeout");}
 public static void main(String[] args)throws Exception{
  Path base=Paths.get(args[0]).toAbsolutePath(),src=base.resolve("source"),file=src.resolve("overlap.txt"),idx=base.resolve("index");write(file);try(LuceneIndexer i=new LuceneIndexer(idx,new IndexSettings())){i.indexFolder(src);}Files.delete(file);
  LuceneSearchService s=new LuceneSearchService(idx,new IndexSettings(),()->Collections.singletonList(src));Method purge=LuceneSearchService.class.getDeclaredMethod("purgeGhostBatch",List.class);purge.setAccessible(true);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);SecurityManager old=System.getSecurityManager();
  SecurityManager gate=new SecurityManager(){public void checkPermission(Permission p){}public void checkRead(String path){if(Thread.currentThread().getName().equals("qa-classifying")&&Paths.get(path).toAbsolutePath().normalize().equals(src)){entered.countDown();hold(release);}}};
  ExecutorService ex=Executors.newSingleThreadExecutor(r->new Thread(r,"qa-classifying"));
  try{System.setSecurityManager(gate);Future<?> f=ex.submit(()->{try{purge.invoke(s,Collections.singletonList(file.toString()));}catch(Exception e){throw new RuntimeException(e);}});await(entered);
   write(file);try(LuceneIndexer newer=new LuceneIndexer(idx,new IndexSettings())){newer.upsertFile(file);newer.commit();}
   int before;try(Directory d=FSDirectory.open(idx);DirectoryReader r=DirectoryReader.open(d)){before=new IndexSearcher(r).count(new TermQuery(new Term("name_raw","overlap.txt")));}if(before!=1)throw new AssertionError("newer document not indexed");
   release.countDown();f.get(10,TimeUnit.SECONDS);try(Directory d=FSDirectory.open(idx);DirectoryReader r=DirectoryReader.open(d)){int after=new IndexSearcher(r).count(new TermQuery(new Term("name_raw","overlap.txt")));System.out.println("PROBE classification_overlap restoredFileExists="+Files.exists(file)+" indexedBeforePurge="+before+" indexedAfterPurge="+after);}
  }finally{release.countDown();System.setSecurityManager(old);ex.shutdownNow();s.close();}
 }
}
