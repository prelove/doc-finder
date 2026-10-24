package org.abitware.docfinder.ui.components;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class CodexScopeDisplayProbeTest {
  @Test void codexQaShouldKeepAppliedScopeVisibleWhenDraftChanges() throws Exception {
    String a=Paths.get(System.getProperty("java.io.tmpdir"),"qa-A").toString();
    String b=Paths.get(System.getProperty("java.io.tmpdir"),"qa-B").toString();
    AtomicReference<String> labels=new AtomicReference<>();
    SwingUtilities.invokeAndWait(()->{
      DirectoryScopePanel panel=new DirectoryScopePanel(()->{});
      panel.setSourceDirectories(Arrays.asList(a,b));
      panel.getSourceCombo().setSelectedItem(a);
      panel.getPathField().setText(b);
      assertEquals(a,panel.getDirectoryPath());
      StringBuilder out=new StringBuilder();collect(panel,out);labels.set(out.toString());
    });
    assertTrue(labels.get().toLowerCase(Locale.ROOT).contains(a.toLowerCase(Locale.ROOT)),
      "Applied A must remain visibly indicated while editing B; actual labels: "+labels.get());
  }
  private static void collect(Component c,StringBuilder out){
    if(c instanceof JLabel && c.isVisible())out.append(((JLabel)c).getText()).append("\n");
    if(c instanceof Container)for(Component child:((Container)c).getComponents())collect(child,out);
  }
  @Test void codexQaBrowseConfirmAndCancelShouldSubmitExactlyOnce() throws Exception {
    if(GraphicsEnvironment.isHeadless())org.junit.jupiter.api.Assumptions.assumeTrue(false,"display required");
    Path chosen=Files.createTempDirectory("docfinder-qa-browse-");
    AtomicInteger calls=new AtomicInteger();
    AtomicReference<JFrame> frame=new AtomicReference<>();
    AtomicReference<DirectoryScopePanel> panelRef=new AtomicReference<>();
    AtomicBoolean approved=new AtomicBoolean(),cancelled=new AtomicBoolean();
    try {
      SwingUtilities.invokeAndWait(()->{
        DirectoryScopePanel panel=new DirectoryScopePanel(calls::incrementAndGet);panelRef.set(panel);
        panel.getSourceCombo().setSelectedItem("Custom");
        JFrame f=new JFrame("Codex isolated directory chooser QA");frame.set(f);f.add(panel);f.pack();f.setVisible(true);
        try {
          Field field=DirectoryScopePanel.class.getDeclaredField("browseButton");field.setAccessible(true);
          JButton browse=(JButton)field.get(panel);
          javax.swing.Timer approve=new javax.swing.Timer(150,e->{JFileChooser chooser=findChooser();if(chooser!=null){((javax.swing.Timer)e.getSource()).stop();chooser.setSelectedFile(chosen.toFile());approved.set(true);chooser.approveSelection();}});
          approve.start();browse.doClick();approve.stop();
          assertTrue(approved.get(),"real chooser must open");assertEquals(1,calls.get());assertEquals(chosen.toString(),panel.getDirectoryPath());
          javax.swing.Timer cancel=new javax.swing.Timer(150,e->{JFileChooser chooser=findChooser();if(chooser!=null){((javax.swing.Timer)e.getSource()).stop();cancelled.set(true);chooser.cancelSelection();}});
          cancel.start();browse.doClick();cancel.stop();
          assertTrue(cancelled.get(),"cancel chooser must open");assertEquals(1,calls.get(),"cancel must not submit");assertEquals(chosen.toString(),panel.getDirectoryPath());
        }catch(Exception e){throw new RuntimeException(e);}
      });
    }finally{SwingUtilities.invokeAndWait(()->{if(frame.get()!=null){for(Window w:frame.get().getOwnedWindows())w.dispose();frame.get().dispose();}});Files.deleteIfExists(chosen);}
  }
  private static JFileChooser findChooser(){
    for(Window w:Window.getWindows())if(w.isShowing()){JFileChooser c=find(w);if(c!=null)return c;}return null;
  }
  private static JFileChooser find(Component c){
    if(c instanceof JFileChooser)return (JFileChooser)c;
    if(c instanceof Container)for(Component child:((Container)c).getComponents()){JFileChooser f=find(child);if(f!=null)return f;}return null;
  }
}
