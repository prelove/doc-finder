package org.abitware.docfinder.index.content;

import org.apache.lucene.document.Document;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ContentExtractorTest {
    @TempDir Path directory;

    /** Minimal controllable parser double for the package-private injection seam. */
    private interface ParseBehavior {
        void parse(org.apache.tika.io.TikaInputStream stream, ContentHandler handler) throws Exception;
    }

    private static Parser parser(ParseBehavior behavior) {
        return new Parser() {
            @Override
            public Set<MediaType> getSupportedTypes(ParseContext context) {
                return Collections.singleton(MediaType.TEXT_PLAIN);
            }

            @Override
            public void parse(org.apache.tika.io.TikaInputStream stream, ContentHandler handler,
                              Metadata metadata, ParseContext context)
                    throws IOException, SAXException, TikaException {
                try {
                    behavior.parse(stream, handler);
                } catch (IOException | SAXException | TikaException e) {
                    throw e;
                } catch (Exception e) {
                    throw new TikaException("behavior failed", e);
                }
            }
        };
    }

    private Path source() throws IOException {
        return Files.write(directory.resolve("input.txt"), "sample".getBytes());
    }

    @Test
    void shouldRunTimeoutTaskOnVirtualThread() throws Exception {
        AtomicBoolean virtual = new AtomicBoolean();
        AtomicReference<String> name = new AtomicReference<>();
        ContentExtractor extractor = new ContentExtractor(10, 10, parser((stream, handler) -> {
            virtual.set(Thread.currentThread().isVirtual());
            name.set(Thread.currentThread().getName());
        }));
        extractor.extractText(source());
        assertTrue(virtual.get(), "extraction must run on a virtual thread");
        assertEquals("tika-extract", name.get());
    }

    @Test
    void shouldReturnTextOnSuccess() throws Exception {
        ContentExtractor extractor = new ContentExtractor(10, 10, parser((stream, handler) -> {
            // BodyContentHandler 只收集 <body> 内的字符事件，须发出完整 SAX 序列。
            char[] text = "extracted body text".toCharArray();
            handler.startDocument();
            handler.startElement("http://www.w3.org/1999/xhtml", "html", "html",
                    new org.xml.sax.helpers.AttributesImpl());
            handler.startElement("http://www.w3.org/1999/xhtml", "body", "body",
                    new org.xml.sax.helpers.AttributesImpl());
            handler.characters(text, 0, text.length);
            handler.endElement("http://www.w3.org/1999/xhtml", "body", "body");
            handler.endElement("http://www.w3.org/1999/xhtml", "html", "html");
            handler.endDocument();
        }));
        assertEquals("extracted body text", extractor.extractText(source()));
        assertTrue(extractor.extractAndAddToDocument(source(), new Document(), "input.txt"));
    }

    @Test
    void shouldReturnEmptyTextOnParserFailure() throws Exception {
        ContentExtractor extractor = new ContentExtractor(10, 10, parser((stream, handler) -> {
            throw new TikaException("boom");
        }));
        assertEquals("", extractor.extractText(source()));
        assertFalse(extractor.extractAndAddToDocument(source(), new Document(), "input.txt"));
    }

    @Test
    void shouldCloseTikaOwnedResourcesAfterSuccess() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        ContentExtractor extractor = new ContentExtractor(10, 10, parser((stream, handler) -> {
            stream.addCloseableResource(() -> closed.set(true));
        }));
        extractor.extractText(source());
        assertTrue(closed.get(), "Tika resources must close with the parsing stream");
    }

    @Test
    void shouldCloseTikaOwnedResourcesAfterParserFailure() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        ContentExtractor extractor = new ContentExtractor(10, 10, parser((stream, handler) -> {
            stream.addCloseableResource(() -> closed.set(true));
            throw new TikaException("parser failed after allocating resources");
        }));
        assertEquals("", extractor.extractText(source()));
        assertTrue(closed.get(), "failure must still close resources allocated by Tika");
    }

    @Test
    void shouldCancelTimedOutExtractionWithoutWaitingForTermination() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch parserDone = new CountDownLatch(1);
        ContentExtractor extractor = new ContentExtractor(1, 10, parser((stream, handler) -> {
            started.countDown();
            try {
                // 忽略中断模拟不响应取消的解析；测试 finally 放行，不留挂死任务。
                while (release.getCount() > 0) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) { }
                }
            } finally {
                parserDone.countDown();
            }
        }));
        try {
            long begin = System.nanoTime();
            // 直接在测试线程调用：包装须在 1s 超时处返回，不等仍阻塞的 parser。
            assertEquals("", extractor.extractText(source()));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
            assertTrue(started.await(1, TimeUnit.SECONDS), "injected parser never ran");
            assertTrue(elapsedMs < 5000,
                    "wrapper must return at the timeout without waiting for the parser: " + elapsedMs);
            assertEquals(1, parserDone.getCount(), "parser must still be running when the wrapper returns");
        } finally {
            release.countDown();
        }
        assertTrue(parserDone.await(10, TimeUnit.SECONDS), "released parser must finish");
    }

    @Test
    void shouldKeepInterruptedWaitBehavior() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ContentExtractor extractor = new ContentExtractor(30, 10, parser((stream, handler) -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        Path file = source();
        AtomicReference<String> result = new AtomicReference<>();
        AtomicBoolean interruptedAfterReturn = new AtomicBoolean(true);
        Thread caller = new Thread(() -> {
            result.set(extractor.extractText(file));
            // 本测试有意固化现状：包装的 catch(Exception) 消耗
            // InterruptedException 的中断位，返回后标志为已清除。
            interruptedAfterReturn.set(Thread.currentThread().isInterrupted());
        }, "extract-caller");
        try {
            caller.start();
            assertTrue(started.await(10, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(10000);
            assertFalse(caller.isAlive(), "interrupted wait must return promptly");
            assertEquals("", result.get());
            assertFalse(interruptedAfterReturn.get(),
                    "the wrapper's catch(Exception) consumes the interrupt bit (pinned existing behaviour)");
        } finally {
            release.countDown();
            caller.join(10000);
        }
    }
}
