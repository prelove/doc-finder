import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.net.ServerSocket;
import org.abitware.docfinder.index.*;
import org.abitware.docfinder.search.*;
import org.abitware.docfinder.web.WebServer;

public class QaWebFixture {
  public static void main(String[] args) throws Exception {
    Path root=Paths.get(args[0]).toAbsolutePath().normalize();
    if(!Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize().equals(root.resolve("work")))throw new IllegalStateException("isolated cwd required");
    Path source=root.resolve("source");
    Path a=Files.createDirectories(source.resolve("ProjectA"));
    Path deep=Files.createDirectories(a.resolve("sub/deep"));
    Path ab=Files.createDirectories(source.resolve("ProjectAB"));
    Path b=Files.createDirectories(source.resolve("ProjectB"));
    Path special=Files.createDirectories(source.resolve("资料 空格&#+[草稿]'单引号"));
    Path outside=Files.createDirectories(root.resolve("Outside"));
    Files.write(a.resolve("alpha-A.txt"),"alpha contract A".getBytes(StandardCharsets.UTF_8));
    Files.write(deep.resolve("alpha-nested.txt"),"alpha contract nested".getBytes(StandardCharsets.UTF_8));
    Files.write(ab.resolve("alpha-sibling.txt"),"alpha contract sibling".getBytes(StandardCharsets.UTF_8));
    Files.write(b.resolve("alpha-B.txt"),"alpha contract B".getBytes(StandardCharsets.UTF_8));
    Files.write(special.resolve("alpha'&#.txt"),"alpha contract special".getBytes(StandardCharsets.UTF_8));
    Files.write(outside.resolve("alpha-removed.txt"),"alpha contract removed source".getBytes(StandardCharsets.UTF_8));
    SourceManager sm=new SourceManager();sm.save(Arrays.asList(source));
    IndexSettings settings=new IndexSettings();
    try(LuceneIndexer ix=new LuceneIndexer(sm.getIndexDir(),settings)){ix.indexFolders(Arrays.asList(source,outside),false);}
    LuceneSearchService service=new LuceneSearchService(sm.getIndexDir(),settings);
    int port;try(ServerSocket s=new ServerSocket(0)){port=s.getLocalPort();}
    WebServer web=new WebServer(port,"127.0.0.1");web.setSearchService(service);web.start();
    Files.write(root.resolve("port.txt"),String.valueOf(port).getBytes(StandardCharsets.UTF_8));
    Files.write(root.resolve("fixture-paths.txt"),Arrays.asList(source.toString(),a.toString(),special.toString(),outside.toString()),StandardCharsets.UTF_8);
    System.out.println("QA_READY http://127.0.0.1:"+port+"/");
    try{while(!Files.exists(root.resolve("STOP"))){Thread.sleep(200);}}
    finally{web.stop();service.close();}
  }
}
