package org.abitware.docfinder.util;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;

import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

/** Private stdio helper. Never loads UI, indexes, application configuration or application logging. */
public final class FileAccessWorker {
    private FileAccessWorker() { }

    private static final class ParserHolder {
        static final AutoDetectParser PARSER = new AutoDetectParser();
    }

    public static void main(String[] args) throws IOException {
        DataOutputStream output = new DataOutputStream(new FileOutputStream(FileDescriptor.out));
        // Third-party parsers sometimes print directly. Keep wire stdout exclusive from startup onward.
        System.setOut(System.err);
        DataInputStream input = new DataInputStream(System.in);
        while (true) {
            int magic;
            try { magic = input.readInt(); } catch (EOFException e) { return; }
            if (magic != IsolatedFileAccess.MAGIC) return;
            byte operation = input.readByte();
            String path = IsolatedFileAccess.readString(input, IsolatedFileAccess.MAX_PATH_BYTES);
            String root = IsolatedFileAccess.readString(input, IsolatedFileAccess.MAX_PATH_BYTES);
            int maxChars = input.readInt();
            IsolatedFileAccess.Response response;
            try {
                response = execute(operation, path, root, maxChars);
            } catch (Exception e) {
                output.writeInt(IsolatedFileAccess.MAGIC);
                output.writeByte(1);
                // Avoid leaking arbitrary parser messages or allowing an unbounded exception payload.
                IsolatedFileAccess.writeString(output, e.getClass().getSimpleName(), 4096);
                output.flush();
                continue;
            }
            output.writeInt(IsolatedFileAccess.MAGIC);
            output.writeByte(0);
            output.writeByte(operation);
            if (operation == IsolatedFileAccess.CLASSIFY) {
                output.writeByte(response.classification.ordinal());
            } else if (operation == IsolatedFileAccess.ATTRIBUTES) {
                IsolatedFileAccess.Attributes value = response.attributes;
                output.writeLong(value.getSize());
                output.writeLong(value.getLastModifiedTime());
                output.writeLong(value.getCreationTime());
                output.writeLong(value.getLastAccessTime());
                output.writeBoolean(value.isDirectory());
                output.writeBoolean(value.isRegularFile());
            } else {
                IsolatedFileAccess.Extraction value = response.extraction;
                IsolatedFileAccess.writeString(output, value.getText(), maxChars * 4);
                IsolatedFileAccess.writeString(output, value.getMimeType(), 4096);
                output.writeBoolean(value.isTruncated());
                output.writeBoolean(value.isSuccessful());
            }
            output.flush();
        }
    }

    private static IsolatedFileAccess.Response execute(byte operation, String rawPath, String root, int maxChars)
            throws IOException {
        Path path = Paths.get(rawPath);
        IsolatedFileAccess.Response response = new IsolatedFileAccess.Response();
        if (operation == IsolatedFileAccess.CLASSIFY) {
            response.classification = classify(path, Paths.get(root));
        } else if (operation == IsolatedFileAccess.ATTRIBUTES) {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            response.attributes = new IsolatedFileAccess.Attributes(attrs.size(), attrs.lastModifiedTime().toMillis(),
                    attrs.creationTime().toMillis(), attrs.lastAccessTime().toMillis(), attrs.isDirectory(), attrs.isRegularFile());
        } else if (operation == IsolatedFileAccess.EXTRACT && maxChars > 0
                && maxChars <= IsolatedFileAccess.MAX_TEXT_CHARS) {
            response.extraction = extract(path, maxChars);
        } else {
            throw new IOException("Unsupported operation");
        }
        return response;
    }

    private static IsolatedFileAccess.Classification classify(Path path, Path root) {
        if (!reachableRoot(root)) return IsolatedFileAccess.Classification.ROOT_UNREACHABLE;
        try {
            Files.readAttributes(path, BasicFileAttributes.class);
            return IsolatedFileAccess.Classification.ALIVE;
        } catch (NoSuchFileException e) {
            return reachableRoot(root) ? IsolatedFileAccess.Classification.GHOST
                    : IsolatedFileAccess.Classification.ROOT_UNREACHABLE;
        } catch (IOException | SecurityException e) {
            return IsolatedFileAccess.Classification.UNCERTAIN;
        }
    }

    private static boolean reachableRoot(Path root) {
        try {
            return Files.readAttributes(root, BasicFileAttributes.class).isDirectory();
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    private static IsolatedFileAccess.Extraction extract(Path path, int maxChars) throws IOException {
        BodyContentHandler handler = new BodyContentHandler(maxChars);
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, path.getFileName().toString());
        boolean successful = true;
        boolean truncated = false;
        try (InputStream stream = new BufferedInputStream(Files.newInputStream(path), 16384)) {
            try {
                ParserHolder.PARSER.parse(stream, handler, metadata, new ParseContext());
            } catch (Exception e) {
                truncated = WriteLimitReachedException.isWriteLimitReached(e);
                successful = truncated;
            }
        }
        String text = handler.toString();
        if (text.length() > maxChars) { text = text.substring(0, maxChars); truncated = true; }
        String mime = metadata.get(HttpHeaders.CONTENT_TYPE);
        if (mime == null) mime = "";
        if (mime.length() > 1000) mime = mime.substring(0, 1000);
        return new IsolatedFileAccess.Extraction(text, mime, truncated, successful);
    }
}
