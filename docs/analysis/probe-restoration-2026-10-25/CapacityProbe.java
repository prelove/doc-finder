import org.abitware.docfinder.index.LuceneIndexer;
import java.lang.reflect.*;
import java.util.*;
public class CapacityProbe {
 public static void main(String[] args)throws Exception{
  Method record=LuceneIndexer.class.getDeclaredMethod("recordUpsert",String.class);record.setAccessible(true);
  Field field=LuceneIndexer.class.getDeclaredField("RECENT_UPSERTS");field.setAccessible(true);Map<?,?> values=(Map<?,?>)field.get(null);values.clear();
  Field capField=LuceneIndexer.class.getDeclaredField("RECENT_UPSERT_CAP");capField.setAccessible(true);int cap=capField.getInt(null);
  long t=System.nanoTime();for(int i=0;i<cap;i++)record.invoke(null,"qa-only-"+i);long a=System.nanoTime();for(int i=cap;i<cap*2;i++)record.invoke(null,"qa-only-"+i);long b=System.nanoTime();
  System.out.println("PROBE recent_upsert_capacity configuredCap="+cap+" actualSize="+values.size()+" firstHalfMs="+((a-t)/1000000)+" secondHalfMs="+((b-a)/1000000));
 }
}
