package org.abitware.docfinder.index;

import java.util.Arrays;
import java.util.List;

//IndexSettings.java
public class IndexSettings {
 public long maxFileMB = 50;
 public int parseTimeoutSec = 15;

 // 文档类（用 Tika 深度解析）：PDF/Office/HTML…
 public java.util.List<String> includeExt = java.util.Arrays.asList(
     "pdf","doc","docx","ppt","pptx","xls","xlsx","xlsm","txt","md","rtf","html","htm"
 );

 // ✅ 文本/源码/配置：作为“文本类”解析
 public boolean parseTextLike = true;
 public long textMaxBytes = 1024 * 1024; // 1MB（防止超大日志占用过多）
 public java.util.List<String> textExts = java.util.Arrays.asList(
     // 配置/数据
     "txt","log","csv","tsv","json","yaml","yml","xml","ini","conf",
     "properties","toml","md","html","htm",
     // 源码/脚本
     "java","kt","kts","scala","groovy","go","rs","py","rb","php",
     "js","mjs","ts","tsx","jsx","css","scss","less",
     "c","cc","cpp","cxx","h","hpp","cs",
     "sh","bash","zsh","ps1","bat","cmd","sql","r","lua","pl","vb","gradle"
 );

 public java.util.List<String> excludeGlob = java.util.Arrays.asList(
     "**/node_modules/**","**/.git/**"
 );
}
