//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ThreadLocalRandom;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.ByteBufferContentSource;
import org.eclipse.jetty.io.content.ChunksContentSource;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.SearchPattern;
import org.eclipse.jetty.util.StaticException;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.SerializedInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * <p>Namespace class for interrelated classes that provide
 * support for parsing and generating multipart bytes.</p>
 * <p>Most applications should make use of {@link MultiPartFormData}
 * or {@link MultiPartByteRanges} as they provide a simpler API.</p>
 * <p>Multipart parsing is provided by {@link Parser}.</p>
 * <p>Multipart generation is provided by {@link AbstractContentSource} and its subclasses.</p>
 * <p>A single part of a multipart content is represented by {@link Part}
 * and its subclasses.</p>
 *
 * @see MultiPartFormData
 * @see MultiPartByteRanges
 */
public class MultiPart
{
    private static final Logger LOG = LoggerFactory.getLogger(MultiPart.class);
    private static final QuotedStringTokenizer CONTENT_DISPOSITION_TOKENIZER = QuotedStringTokenizer.builder()
        .delimiters(";")
        .ignoreOptionalWhiteSpace()
        .allowEmbeddedQuotes()
        .allowEscapeOnlyForQuotes()
        .build();
    private static final int MAX_BOUNDARY_LENGTH = 70;

    private MultiPart()
    {
    }

    /**
     * <p>Extracts the value of the {@code boundary} parameter
     * from the {@code Content-Type} header value, or returns
     * {@code null} if the {@code boundary} parameter is missing.</p>
     *
     * @param contentType the {@code Content-Type} header value
     * @return the value of the {@code boundary} parameter, or
     * {@code null} if the {@code boundary} parameter is missing
     */
    public static String extractBoundary(String contentType)
    {
        Map<String, String> parameters = new HashMap<>();
        HttpField.getValueParameters(contentType, parameters);
        return CONTENT_DISPOSITION_TOKENIZER.unquote(parameters.get("boundary"));
    }

    /**
     * <p>Generates a multipart boundary, made of the given optional
     * prefix string and the given number of random characters.</p>
     * <p>The total length of the boundary will be trimmed to at
     * most 70 characters, as specified in RFC 2046.</p>
     *
     * @param prefix a possibly {@code null} prefix
     * @param randomLength a number of random characters to add after the prefix
     * @return a boundary string
     */
    public static String generateBoundary(String prefix, int randomLength)
    {
        if (prefix == null && randomLength < 1)
            throw new IllegalArgumentException("invalid boundary length");
        StringBuilder builder = new StringBuilder(prefix == null ? "" : prefix);
        int length = builder.length();
        while (builder.length() < length + randomLength)
        {
            long rnd = ThreadLocalRandom.current().nextLong();
            builder.append(Long.toString(rnd < 0 ? -rnd : rnd, 36));
        }
        builder.setLength(Math.min(length + randomLength, MAX_BOUNDARY_LENGTH));
        return builder.toString();
    }

    /**
     * <p>A single part of a multipart content.</p>
     * <p>A part has an optional name, an optional fileName,
     * optional headers and an optional content.</p>
     */
    public abstract static class Part implements Content.Source.Factory, Closeable
    {
        static final Throwable CLOSE_EXCEPTION = new StaticException("Closed");

        private final AutoLock lock = new AutoLock();
        private final ByteBufferPool.Sized bufferPool;
        private final long first;
        private final long length;
        private final String name;
        private final String fileName;
        private final HttpFields fields;
        private Path path;
        private Content.Source contentSource;
        private boolean temporary;

        /**
         * @deprecated use {@link #Part(ByteBufferPool.Sized, String, String, HttpFields)} instead.
         */
        @Deprecated(since = "12.0.20", forRemoval = true)
        public Part(String name, String fileName, HttpFields fields)
        {
            this(null, 0L, -1L, name, fileName, fields, null);
        }

        public Part(ByteBufferPool.Sized bufferPool, String name, String fileName, HttpFields fields)
        {
            this(bufferPool, 0L, -1L, name, fileName, fields, null);
        }

        public Part(ByteBufferPool.Sized bufferPool, long first, long length, String name, String fileName, HttpFields fields)
        {
            this(bufferPool, first, length, name, fileName, fields, null);
        }

        private Part(ByteBufferPool.Sized bufferPool, long first, long length, String name, String fileName, HttpFields fields, Path path)
        {
            this.bufferPool = bufferPool;
            this.first = first;
            this.length = length;
            this.name = name;
            this.fileName = fileName;
            this.fields = fields != null ? fields : HttpFields.EMPTY;
            this.path = path;
            this.temporary = true;
        }

        private Path getPath()
        {
            try (AutoLock ignored = lock.lock())
            {
                return path;
            }
        }

        /**
         * <p>Returns the name of this part, as specified by the
         * {@link HttpHeader#CONTENT_DISPOSITION}'s {@code name} parameter.</p>
         * <p>While the {@code name} parameter is mandatory per RFC 7578,
         * older HTTP clients may not send it.</p>
         *
         * @return the name of this part, or {@code null} if there is no name
         */
        public String getName()
        {
            return name;
        }

        /**
         * <p>Returns the file name of this part, as specified by the
         * {@link HttpHeader#CONTENT_DISPOSITION}'s {@code filename} parameter.</p>
         * <p>While the {@code filename} parameter is mandatory per RFC 7578 when
         * uploading files, older HTTP clients may not send it.</p>
         * <p>The file name may be absent if the part is not a file upload.</p>
         *
         * @return the file name of this part, or {@code null} if there is no file name
         */
        public String getFileName()
        {
            return fileName;
        }

        /**
         * <p>Returns the content of this part as a {@link Content.Source}.</p>
         * <p>Calling this method multiple times will return the same instance, which can only be consumed once.</p>
         * <p>The content type and content encoding are specified in this part's
         * {@link #getHeaders() headers}.</p>
         * <p>The content encoding may be specified by the part named {@code _charset_},
         * as specified in
         * <a href="https://datatracker.ietf.org/doc/html/rfc7578#section-4.6">RFC 7578, section 4.6</a>.</p>
         *
         * @return the content of this part
         * @see #newContentSource()
         */
        public Content.Source getContentSource()
        {
            try (AutoLock ignored = lock.lock())
            {
                if (contentSource == null)
                    contentSource = newContentSource(bufferPool, first, length);
                return contentSource;
            }
        }

        /**
         * <p>Returns the content of this part as a new {@link Content.Source}</p>
         * <p>If the content is reproducible, invoking this method multiple times will return
         * a different independent instance for every invocation.</p>
         * <p>If the content is not reproducible, subsequent calls to this method will return null.</p>
         * <p>The content type and content encoding are specified in this part's {@link #getHeaders() headers}.</p>
         * <p>The content encoding may be specified by the part named {@code _charset_},
         * as specified in
         * <a href="https://datatracker.ietf.org/doc/html/rfc7578#section-4.6">RFC 7578, section 4.6</a>.</p>
         *
         * @return the content of this part as a new {@link Content.Source} or null if the content cannot be consumed multiple times.
         * @see #getContentSource()
         * @deprecated override {@link #newContentSource(ByteBufferPool.Sized, long, long)} instead.
         */
        @Deprecated(since = "12.0.20", forRemoval = true)
        public Content.Source newContentSource()
        {
            return null;
        }

        /**
         * {@inheritDoc}
         *
         * <p>Returns the content of this part as a new {@link Content.Source}</p>
         * <p>If the content is reproducible, invoking this method multiple times will return
         * a different independent instance for every invocation.</p>
         * <p>If the content is not reproducible, subsequent calls to this method will return null.</p>
         * <p>The content type and content encoding are specified in this part's {@link #getHeaders() headers}.</p>
         * <p>The content encoding may be specified by the part named {@code _charset_},
         * as specified in
         * <a href="https://datatracker.ietf.org/doc/html/rfc7578#section-4.6">RFC 7578, section 4.6</a>.</p>
         *
         * @see #getContentSource()
         */
        @Override
        public Content.Source newContentSource(ByteBufferPool.Sized bufferPool, long first, long length)
        {
            return newContentSource();
        }

        public long getLength()
        {
            Content.Source source = getContentSource();
            if (source != null)
                return source.getLength();
            return -1;
        }

        /**
         * <p>Returns the content of this part as a string.</p>
         * <p>The charset used to decode the bytes is:
         * <ul>
         * <li>the {@code charset} parameter of the {@link HttpHeader#CONTENT_TYPE} header, if non-null;</li>
         * <li>otherwise, the given {@code defaultCharset} parameter, if non-null;</li>
         * <li>otherwise, UTF-8.</li>
         * </ul>
         *
         * @param defaultCharset the charset to use to decode the bytes,
         * if the {@code charset} parameter of the {@code Content-Type} header is missing
         * @return the content of this part as a string
         */
        public String getContentAsString(Charset defaultCharset)
        {
            try
            {
                String contentType = getHeaders().get(HttpHeader.CONTENT_TYPE);
                String charsetName = MimeTypes.getCharsetFromContentType(contentType);
                Charset charset = defaultCharset != null ? defaultCharset : UTF_8;
                if (charsetName != null)
                    charset = Charset.forName(charsetName);
                return Content.Source.asString(newContentSource(bufferPool, first, length), charset);
            }
            catch (IOException x)
            {
                throw new UncheckedIOException(x);
            }
        }

        /**
         * @return the headers associated with this part
         */
        public HttpFields getHeaders()
        {
            return fields;
        }

        /**
         * <p>Writes the content of this part to the given path.</p>
         *
         * @param path the Path to write this part's content to
         * @throws IOException if the write fails
         */
        public void writeTo(Path path) throws IOException
        {
            Path currentPath = getPath();
            Path newPath;
            if (currentPath == null)
            {
                try (OutputStream out = Files.newOutputStream(path))
                {
                    IO.copy(Content.Source.asInputStream(newContentSource(bufferPool, first, length)), out);
                }
                newPath = path;
            }
            else
            {
                newPath = Files.move(currentPath, path, StandardCopyOption.REPLACE_EXISTING);
            }

            try (AutoLock ignored = lock.lock())
            {
                this.temporary = false;
                this.path = newPath;
            }
        }

        public void delete() throws IOException
        {
            Path path = getPath();
            if (path != null)
            {
                Files.deleteIfExists(path);
                try (AutoLock ignored = lock.lock())
                {
                    this.path = null;
                }
            }
        }

        @Override
        public void close()
        {
            fail(CLOSE_EXCEPTION);
        }

        public void fail(Throwable t)
        {
            try
            {
                Content.Source source;
                Path path = null;
                try (AutoLock ignored = lock.lock())
                {
                    source = contentSource;
                    if (temporary)
                    {
                        path = this.path;
                        this.path = null;
                    }
                }
                if (source != null)
                    source.fail(t);
                if (path != null)
                    Files.deleteIfExists(path);
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Error closing part {}", this, x);
            }
        }
    }

    /**
     * <p>A {@link Part} that holds its content in memory,
     * in one or more {@code ByteBuffer}s.</p>
     */
    public static class ByteBufferPart extends Part
    {
        private final List<ByteBuffer> content;

        public ByteBufferPart(String name, String fileName, HttpFields fields, ByteBuffer... buffers)
        {
            this(name, fileName, fields, List.of(buffers));
        }

        public ByteBufferPart(String name, String fileName, HttpFields fields, List<ByteBuffer> content)
        {
            super(null, name, fileName, fields);
            this.content = content;
        }

        @Override
        public Content.Source newContentSource(ByteBufferPool.Sized bufferPool, long first, long length)
        {
            return new ByteBufferContentSource(content);
        }

        @Override
        public String toString()
        {
            return "%s@%x[name=%s,fileName=%s,length=%d]".formatted(
                getClass().getSimpleName(),
                hashCode(),
                getName(),
                getFileName(),
                getLength()
            );
        }
    }

    /**
     * <p>A {@link Part} that holds its content in one or more {@link Content.Chunk}s.</p>
     */
    public static class ChunksPart extends Part
    {
        private final AutoLock lock = new AutoLock();
        private final List<Content.Chunk> content = new ArrayList<>();
        private final List<Content.Source> contentSources = new ArrayList<>();
        private boolean closed = false;

        public ChunksPart(String name, String fileName, HttpFields fields, List<Content.Chunk> content)
        {
            super(null, name, fileName, fields);
            this.content.addAll(content);
            content.forEach(Content.Chunk::retain);
        }

        @Override
        public Content.Source newContentSource(ByteBufferPool.Sized bufferPool, long first, long length)
        {
            try (AutoLock ignored = lock.lock())
            {
                if (closed)
                    return null;
                // Slice the chunks as new instances with separate reference counters,
                // because the content sources may not be read, or their chunks could be
                // further retained, so those chunks must not be linked to the original ones.
                List<Content.Chunk> chunks = content.stream()
                    .map(chunk ->
                    {
                        if (Content.Chunk.isFailure(chunk))
                            return chunk;
                        return Content.Chunk.from(chunk.getByteBuffer().slice(), chunk.isLast());
                    })
                    .toList();
                ChunksContentSource newContentSource = new ChunksContentSource(chunks);
                chunks.forEach(Content.Chunk::release);
                contentSources.add(newContentSource);
                return newContentSource;
            }
        }

        @Override
        public void fail(Throwable t)
        {
            List<Content.Source> contentSourcesToFail;
            try (AutoLock ignored = lock.lock())
            {
                closed = true;
                content.forEach(Content.Chunk::release);
                content.clear();
                contentSourcesToFail = List.copyOf(contentSources);
                contentSources.clear();
            }
            contentSourcesToFail.forEach(c -> c.fail(t));
            super.fail(t);
        }

        @Override
        public String toString()
        {
            return "%s@%x[name=%s,fileName=%s,length=%d]".formatted(
                getClass().getSimpleName(),
                hashCode(),
                getName(),
                getFileName(),
                getLength()
            );
        }
    }

    /**
     * <p>A {@link Part} whose content is in a file.</p>
     */
    public static class PathPart extends Part
    {
        /**
         * @deprecated use {@link #PathPart(ByteBufferPool.Sized, String, String, HttpFields, Path)} instead.
         */
        @Deprecated(since = "12.0.20", forRemoval = true)
        public PathPart(String name, String fileName, HttpFields fields, Path path)
        {
            super(null, 0L, -1L, name, fileName, fields, path);
        }

        public PathPart(ByteBufferPool.Sized bufferPool, String name, String fileName, HttpFields fields, Path path)
        {
            super(bufferPool, 0L, -1L, name, fileName, fields, path);
        }

        public Path getPath()
        {
            return super.getPath();
        }

        @Override
        public Content.Source newContentSource(ByteBufferPool.Sized bufferPool, long first, long length)
        {
            return Content.Source.from(bufferPool, getPath(), first, length);
        }

        @Override
        public String toString()
        {
            return "%s@%x[name=%s,fileName=%s,path=%s]".formatted(
                getClass().getSimpleName(),
                hashCode(),
                getName(),
                getFileName(),
                getPath()
            );
        }
    }

    /**
     * <p>A {@link Part} whose content is a {@link Content.Source}.</p>
     */
    public static class ContentSourcePart extends Part
    {
        private Content.Source content;

        public ContentSourcePart(String name, String fileName, HttpFields fields, Content.Source content)
        {
            super(null, name, fileName, fields);
            this.content = Objects.requireNonNull(content);
        }

        @Override
        public Content.Source newContentSource(ByteBufferPool.Sized bufferPool, long first, long length)
        {
            Content.Source c = content;
            content = null;
            return c;
        }

        @Override
        public String toString()
        {
            return "%s@%x[name=%s,fileName=%s,length=%d]".formatted(
                getClass().getSimpleName(),
                hashCode(),
                getName(),
                getFileName(),
                getLength()
            );
        }
    }

    /**
     * <p>An asynchronous {@link Content.Source} where {@link Part}s can
     * be added to it to form a multipart content.</p>
     * <p>When this {@link Content.Source} is read, it will produce the
     * bytes (including boundary separators) in the multipart format.</p>
     * <p>Subclasses should override {@link #customizePartHeaders(Part)}
     * to produce the right part headers depending on the specific
     * multipart subtype (for example, {@code multipart/form-data} or
     * {@code multipart/byteranges}, etc.).</p>
     * <p>Typical asynchronous usage is the following:</p>
     * <pre>{@code
     * // Create a ContentSource subclass.
     * ContentSource source = ...;
     *
     * // Add parts to the ContentSource.
     * source.addPart(new ByteBufferPart());
     * source.addPart(new PathPart());
     *
     * // Close the ContentSource to signal
     * // that no more parts will be added.
     * source.close();
     *
     * // The Sink where this ContentSource is written to.
     * Content.Sink sink = ...;
     *
     * // Copy this ContentSource to the Sink.
     * Content.copy(source, sink, Callback.from(...));
     * }</pre>
     * <p>Reading from {@code ContentSource} may be performed at any time,
     * even if not all the parts have been added yet.</p>
     * <p>Adding parts and calling {@link #close()} may be done asynchronously
     * from other threads.</p>
     * <p>Eventually, reading from {@code ContentSource} will produce a last
     * chunk when all the parts have been added and this {@code ContentSource}
     * has been closed.</p>
     */
    public abstract static class AbstractContentSource implements Content.Source, Closeable
    {
        private final AutoLock lock = new AutoLock();
        private final SerializedInvoker invoker = new SerializedInvoker(AbstractContentSource.class);
        private final Queue<Part> parts = new ArrayDeque<>();
        private final String boundary;
        private final ByteBuffer firstBoundary;
        private final ByteBuffer middleBoundary;
        private final ByteBuffer onlyBoundary;
        private final ByteBuffer lastBoundary;
        private int partHeadersMaxLength = -1;
        private State state = State.FIRST;
        private boolean closed;
        private Runnable demand;
        private Content.Chunk errorChunk;
        private Part part;

        public AbstractContentSource(String boundary)
        {
            if (boundary.isBlank() || boundary.length() > MAX_BOUNDARY_LENGTH)
                throw new IllegalArgumentException("Invalid boundary: must consists of 1 to 70 characters");
            // RFC 2046 requires the boundary to not end with a space.
            boundary = boundary.stripTrailing();
            this.boundary = boundary;
            String firstBoundaryLine = "--" + boundary + "\r\n";
            this.firstBoundary = ByteBuffer.wrap(firstBoundaryLine.getBytes(US_ASCII));
            String middleBoundaryLine = "\r\n" + firstBoundaryLine;
            this.middleBoundary = ByteBuffer.wrap(middleBoundaryLine.getBytes(US_ASCII));
            String onlyBoundaryLine = "--" + boundary + "--\r\n";
            this.onlyBoundary = ByteBuffer.wrap(onlyBoundaryLine.getBytes(US_ASCII));
            String lastBoundaryLine = "\r\n" + onlyBoundaryLine;
            this.lastBoundary = ByteBuffer.wrap(lastBoundaryLine.getBytes(US_ASCII));
        }

        /**
         * @return the boundary string
         */
        public String getBoundary()
        {
            return boundary;
        }

        /**
         * @return the max length of a {@link Part} headers, in bytes, or -1 for unlimited length
         */
        public int getPartHeadersMaxLength()
        {
            return partHeadersMaxLength;
        }

        /**
         * @param partHeadersMaxLength the max length of a {@link Part} headers, in bytes, or -1 for unlimited length
         */
        public void setPartHeadersMaxLength(int partHeadersMaxLength)
        {
            this.partHeadersMaxLength = partHeadersMaxLength;
        }

        /**
         * <p>Adds, if possible, the given {@link Part} to this {@code ContentSource}.</p>
         * <p>{@code Part}s may be added until this {@code ContentSource} is
         * {@link #close() closed}.</p>
         * <p>This method returns {@code true} if the part was added, {@code false}
         * if the part cannot be added because this {@code ContentSource} is
         * already closed, or because it has been {@link #fail(Throwable) failed}.</p>
         *
         * @param part the {@link Part} to add
         * @return whether the part has been added
         * @see #close()
         */
        public boolean addPart(Part part)
        {
            boolean wasEmpty;
            try (AutoLock ignored = lock.lock())
            {
                if (closed || errorChunk != null)
                    return false;
                wasEmpty = parts.isEmpty();
                parts.offer(part);
            }
            if (wasEmpty)
                invoker.run(this::invokeDemandCallback);
            return true;
        }

        /**
         * <p>Closes this {@code ContentSource} so that no more parts may be added.</p>
         * <p>Once this method is called, reading from this {@code ContentSource}
         * will eventually produce a terminal multipart/form-data boundary,
         * when all the part bytes have been read.</p>
         */
        @Override
        public void close()
        {
            boolean wasEmpty;
            try (AutoLock ignored = lock.lock())
            {
                closed = true;
                wasEmpty = parts.isEmpty();
            }
            if (wasEmpty)
                invoker.run(this::invokeDemandCallback);
        }

        @Override
        public long getLength()
        {
            // TODO: #10307 it is difficult to calculate the length because
            //  we need to allow for customization of the headers from
            //  subclasses, and then serialize all the headers to get
            //  their length (handling UTF-8 values) and we don't want
            //  to do it twice (here and in read()).
            return -1;
        }

        @Override
        public Content.Chunk read()
        {
            try (AutoLock ignored = lock.lock())
            {
                if (errorChunk != null)
                    return errorChunk;
            }

            return switch (state)
            {
                case FIRST ->
                {
                    try (AutoLock ignored = lock.lock())
                    {
                        if (parts.isEmpty())
                        {
                            if (closed)
                            {
                                state = State.COMPLETE;
                                yield Content.Chunk.from(onlyBoundary.slice(), true);
                            }
                            else
                            {
                                yield null;
                            }
                        }
                        else
                        {
                            part = parts.poll();
                            state = State.HEADERS;
                            yield Content.Chunk.from(firstBoundary.slice(), false);
                        }
                    }
                }
                case MIDDLE ->
                {
                    part = null;
                    try (AutoLock ignored = lock.lock())
                    {
                        if (parts.isEmpty())
                        {
                            if (closed)
                            {
                                state = State.COMPLETE;
                                yield Content.Chunk.from(lastBoundary.slice(), true);
                            }
                            else
                            {
                                yield null;
                            }
                        }
                        else
                        {
                            part = parts.poll();
                            state = State.HEADERS;
                            yield Content.Chunk.from(middleBoundary.slice(), false);
                        }
                    }
                }
                case HEADERS ->
                {
                    HttpFields headers = customizePartHeaders(part);
                    Utf8StringBuilder builder = new Utf8StringBuilder(4096);
                    headers.forEach(field ->
                    {
                        HttpHeader header = field.getHeader();
                        if (header != null)
                        {
                            builder.append(header.getBytesColonSpace());
                        }
                        else
                        {
                            builder.append(field.getName());
                            builder.append(": ");
                        }
                        checkPartHeadersLength(builder);
                        String value = field.getValue();
                        if (value != null)
                        {
                            builder.append(value);
                            checkPartHeadersLength(builder);
                        }
                        builder.append("\r\n");
                    });
                    builder.append("\r\n");

                    // TODO: use a ByteBuffer pool and direct ByteBuffers?
                    ByteBuffer byteBuffer = ByteBuffer.wrap(builder.toCompleteString().getBytes(UTF_8));
                    state = State.CONTENT;
                    yield Content.Chunk.from(byteBuffer, false);
                }
                case CONTENT ->
                {
                    Content.Chunk chunk = part.getContentSource().read();
                    if (chunk == null)
                        yield null;
                    if (Content.Chunk.isFailure(chunk, true))
                    {
                        try (AutoLock ignored = lock.lock())
                        {
                            errorChunk = chunk;
                        }
                        yield chunk;
                    }
                    if (!chunk.isLast())
                        yield chunk;
                    state = State.MIDDLE;
                    if (chunk.hasRemaining())
                        yield Content.Chunk.asChunk(chunk.getByteBuffer(), false, chunk);
                    chunk.release();
                    yield Content.Chunk.EMPTY;
                }
                case COMPLETE -> Content.Chunk.EOF;
            };
        }

        protected HttpFields customizePartHeaders(Part part)
        {
            return part.getHeaders();
        }

        private void checkPartHeadersLength(Utf8StringBuilder builder)
        {
            int max = getPartHeadersMaxLength();
            if (max > 0 && builder.length() > max)
                throw new IllegalStateException("headers max length exceeded: %d".formatted(max));
        }

        @Override
        public void demand(Runnable demandCallback)
        {
            Part part = null;
            boolean invoke = false;
            try (AutoLock ignored = lock.lock())
            {
                if (this.demand != null)
                    throw new IllegalStateException("demand pending");
                this.demand = Objects.requireNonNull(demandCallback);

                if (state == State.CONTENT)
                    part = this.part;
                else
                    invoke = !parts.isEmpty() || closed || errorChunk != null;
            }
            if (part != null)
            {
                // Inner class used instead of lambda for clarity in stack traces.
                part.getContentSource().demand(new DemandTask(demandCallback));
            }
            else if (invoke)
            {
                invoker.run(this::invokeDemandCallback);
            }
        }

        @Override
        public void fail(Throwable failure)
        {
            Part part;
            List<Part> drained;
            try (AutoLock ignored = lock.lock())
            {
                if (errorChunk != null)
                    return;
                errorChunk = Content.Chunk.from(failure);
                drained = List.copyOf(parts);
                parts.clear();
                part = this.part;
                this.part = null;
            }
            if (part != null)
                part.fail(failure);
            drained.forEach(p -> p.fail(failure));
            invoker.run(this::invokeDemandCallback);
        }

        private void invokeDemandCallback()
        {
            Runnable callback;
            try (AutoLock ignored = lock.lock())
            {
                callback = this.demand;
                this.demand = null;
            }
            if (callback != null)
                runDemandCallback(callback);
        }

        private void runDemandCallback(Runnable callback)
        {
            try
            {
                callback.run();
            }
            catch (Throwable x)
            {
                fail(x);
            }
        }

        private enum State
        {
            FIRST, MIDDLE, HEADERS, CONTENT, COMPLETE
        }

        private class DemandTask extends Invocable.Task.Abstract
        {
            private final Runnable demandCallback;

            private DemandTask(Runnable demandCallback)
            {
                super(Invocable.getInvocationType(demandCallback));
                this.demandCallback = demandCallback;
            }

            @Override
            public void run()
            {
                try (AutoLock ignoredAgain = lock.lock())
                {
                    AbstractContentSource.this.demand = null;
                }
                demandCallback.run();
            }
        }
    }

    /**
     * <p>A {@code multipart/form-data} parser that follows
     * <a href="https://datatracker.ietf.org/doc/html/rfc7578">RFC 7578</a>.</p>
     * <p>RFC 7578 mandates that end-of-lines are CRLF, but this parser is
     * more lenient and it is able to parse multipart content that only
     * uses LF as end-of-line.</p>
     * <p>The parser emits events specified by {@link Listener}, that can be
     * implemented to support specific logic (for example, the max content
     * length of a part, etc.</p>
     *
     * @see #parse(Content.Chunk)
     */
    public static class Parser
    {
        private static final Logger LOG = LoggerFactory.getLogger(Parser.class);
        private static final ByteBuffer CR = US_ASCII.encode("\r");

        private final Utf8StringBuilder text = new Utf8StringBuilder();
        private final String boundary;
        private final SearchPattern boundaryFinder;
        private final MultiPartCompliance compliance;
        private final Listener listener;
        private int partHeadersLength;
        private int partHeadersMaxLength = -1;
        private State state;
        private int partialBoundaryMatch;
        private boolean crFlag;
        private boolean crContent;
        private int trailingWhiteSpaces;
        private String fieldName;
        private String fieldValue;
        private long maxParts = 1000;
        private int numParts;
        private EnumSet<MultiPartCompliance.Violation> eols;

        public Parser(String boundary, Listener listener)
        {
            this(boundary, MultiPartCompliance.RFC7578, listener);
        }

        public Parser(String boundary, MultiPartCompliance compliance, Listener listener)
        {
            this.boundary = boundary;
            // While the spec mandates CRLF before the boundary, be more lenient and only require LF.
            this.boundaryFinder = SearchPattern.compile("\n--" + boundary);
            this.compliance = compliance;
            this.listener = listener;

            if (LOG.isDebugEnabled())
            {
                List.of(
                    MultiPartCompliance.Violation.CR_LINE_TERMINATION,
                    MultiPartCompliance.Violation.BASE64_TRANSFER_ENCODING,
                    MultiPartCompliance.Violation.WHITESPACE_BEFORE_BOUNDARY
                ).forEach(violation ->
                {
                    if (compliance.allows(violation))
                        LOG.debug("{} ignoring violation {}: unable to allow it", getClass().getName(), violation.name());
                });
            }
            reset();
        }

        public String getBoundary()
        {
            return boundary;
        }

        /**
         * @return the max length of a {@link Part} headers, in bytes, or -1 for unlimited length
         */
        public int getPartHeadersMaxLength()
        {
            return partHeadersMaxLength;
        }

        /**
         * @param partHeadersMaxLength the max length of a {@link Part} headers, in bytes, or -1 for unlimited length
         */
        public void setPartHeadersMaxLength(int partHeadersMaxLength)
        {
            this.partHeadersMaxLength = partHeadersMaxLength;
        }

        /**
         * @return the maximum number of parts that can be parsed from the multipart content (0 for no parts allowed, -1 for unlimited parts).
         */
        public long getMaxParts()
        {
            return maxParts;
        }

        /**
         * @param maxParts the maximum number of parts that can be parsed from the multipart content (0 for no parts allowed, -1 for unlimited parts).
         */
        public void setMaxParts(long maxParts)
        {
            this.maxParts = maxParts;
        }

        /**
         * <p>Resets this parser to make it ready to parse again a multipart/form-data content.</p>
         */
        public void reset()
        {
            text.reset();
            partHeadersLength = 0;
            state = State.PREAMBLE;
            // Skip initial LF for the first boundary.
            partialBoundaryMatch = 1;
            crFlag = false;
            crContent = false;
            trailingWhiteSpaces = 0;
            fieldName = null;
            fieldValue = null;
        }

        /**
         * <p>Parses the multipart/form-data bytes contained in the given {@link Content.Chunk}.</p>
         * <p>Parsing the bytes will emit events to a {@link Listener}.</p>
         * <p>The multipart/form-data content may be split into multiple chunks; each chunk should
         * be passed to this method when it is available, with the last chunk signaling that the
         * whole multipart/form-data content has been given to this parser, which will eventually
         * emit the {@link Listener#onComplete() complete} event.</p>
         * <p>In case of parsing errors, the {@link Listener#onFailure(Throwable) failure} event
         * will be emitted.</p>
         *
         * @param chunk the {@link Content.Chunk} to parse
         * @see Listener
         */
        public void parse(Content.Chunk chunk)
        {
            ByteBuffer buffer = chunk.getByteBuffer();
            boolean last = chunk.isLast();
            try
            {
                while (buffer.hasRemaining())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("parse {} {}", state, BufferUtil.toDetailString(buffer));

                    switch (state)
                    {
                        case PREAMBLE ->
                        {
                            if (parsePreamble(buffer))
                                state = State.BOUNDARY;
                        }
                        case BOUNDARY ->
                        {
                            HttpTokens.Token token = next(buffer);
                            HttpTokens.Type type = token.getType();
                            if (type == HttpTokens.Type.CR)
                            {
                                // Ignore CR and loop around.
                            }
                            else if (type == HttpTokens.Type.LF)
                            {
                                numParts++;
                                if (maxParts >= 0 && numParts > maxParts)
                                    throw new IllegalStateException(String.format("Form with too many keys [%d > %d]", numParts, maxParts));

                                notifyPartBegin();
                                state = State.HEADER_START;
                                trailingWhiteSpaces = 0;
                                text.reset();
                                partHeadersLength = 0;
                            }
                            else if (token.getByte() == '-')
                            {
                                state = State.BOUNDARY_CLOSE;
                            }
                            // SPEC: ignore linear whitespace after boundary.
                            else if (type != HttpTokens.Type.SPACE && type != HttpTokens.Type.HTAB)
                            {
                                throw new BadMessageException("bad last boundary");
                            }
                        }
                        case BOUNDARY_CLOSE ->
                        {
                            HttpTokens.Token token = next(buffer);
                            if (token.getByte() != '-')
                                throw new BadMessageException("bad last boundary");
                            notifyEndOfLineViolations();
                            state = State.EPILOGUE;
                        }
                        case HEADER_START ->
                        {
                            state = parseHeaderStart(buffer);
                        }
                        case HEADER_NAME ->
                        {
                            if (parseHeaderName(buffer))
                                state = State.HEADER_VALUE;
                        }
                        case HEADER_VALUE ->
                        {
                            if (parseHeaderValue(buffer))
                                state = State.HEADER_START;
                        }
                        case CONTENT_START ->
                        {
                            if (parseContent(chunk))
                                state = State.BOUNDARY;
                            else
                                state = State.CONTENT;
                        }
                        case CONTENT ->
                        {
                            if (parseContent(chunk))
                                state = State.BOUNDARY;
                        }
                        case EPILOGUE ->
                        {
                            // Just discard the epilogue.
                            buffer.position(buffer.limit());
                        }
                    }
                }

                if (last)
                {
                    if (state == State.EPILOGUE)
                        notifyComplete();
                    else
                        throw new EOFException("unexpected EOF");
                }
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("parse failure {} {}", state, BufferUtil.toDetailString(buffer), x);
                buffer.position(buffer.limit());
                notifyEndOfLineViolations();
                notifyFailure(x);
            }
        }

        /**
         * <p>Returns the token corresponding to the next byte in the given {@code buffer}.</p>
         * <p>Token {@link HttpTokens.Type#CR} should be ignored when looking for EOL, as
         * {@link HttpTokens.Type#LF} marks the actual EOL.</p>
         *
         * @param buffer the {@code ByteBuffer} containing bytes to parse
         * @return a token corresponding to the next byte in the buffer
         */
        private HttpTokens.Token next(ByteBuffer buffer)
        {
            byte b = buffer.get();
            HttpTokens.Token t = HttpTokens.TOKENS[b & 0xFF];
            switch (t.getType())
            {
                case CNTL ->
                {
                    throw new BadMessageException("invalid byte " + Integer.toHexString(t.getChar()));
                }
                case LF ->
                {
                    if (!crFlag)
                    {
                        MultiPartCompliance.Violation violation = MultiPartCompliance.Violation.LF_LINE_TERMINATION;
                        addEndOfLineViolation(violation);
                        if (!compliance.allows(violation))
                            throw new BadMessageException("invalid LF-only EOL");
                    }
                    crFlag = false;
                }
                case CR ->
                {
                    if (crFlag)
                    {
                        MultiPartCompliance.Violation violation = MultiPartCompliance.Violation.CR_LINE_TERMINATION;
                        addEndOfLineViolation(violation);
                        if (!compliance.allows(violation))
                            throw new BadMessageException("invalid CR-only EOL");
                    }
                    crFlag = true;
                }
                default ->
                {
                    if (crFlag)
                    {
                        MultiPartCompliance.Violation violation = MultiPartCompliance.Violation.CR_LINE_TERMINATION;
                        addEndOfLineViolation(violation);
                        if (!compliance.allows(violation))
                            throw new BadMessageException("invalid CR-only EOL");
                    }
                }
            }
            return t;
        }

        private boolean parsePreamble(ByteBuffer buffer)
        {
            if (partialBoundaryMatch > 0)
            {
                int boundaryMatch = boundaryFinder.startsWith(buffer, partialBoundaryMatch);
                if (boundaryMatch > 0)
                {
                    if (boundaryMatch == boundaryFinder.getLength())
                    {
                        // The boundary was fully matched.
                        buffer.position(buffer.position() + boundaryMatch - partialBoundaryMatch);
                        partialBoundaryMatch = 0;
                        return true;
                    }
                    else
                    {
                        // The boundary was partially matched.
                        buffer.position(buffer.limit());
                        partialBoundaryMatch = boundaryMatch;
                        return false;
                    }
                }
                else
                {
                    // There was a partial boundary match, but a mismatch was found.
                    partialBoundaryMatch = 0;
                }
            }

            // Search for a full boundary.
            int boundaryOffset = boundaryFinder.match(buffer);
            if (boundaryOffset >= 0)
            {
                // Found a full boundary.
                buffer.position(buffer.position() + boundaryOffset + boundaryFinder.getLength());
                return true;
            }

            // Search for a partial boundary at the end of the buffer.
            partialBoundaryMatch = boundaryFinder.endsWith(buffer);
            buffer.position(buffer.limit());
            return false;
        }

        private State parseHeaderStart(ByteBuffer buffer)
        {
            while (buffer.hasRemaining())
            {
                HttpTokens.Token token = next(buffer);
                switch (token.getType())
                {
                    case CR ->
                    {
                        // Ignore CR and loop around;
                    }
                    case LF ->
                    {
                        // End of fields.
                        notifyPartHeaders();
                        // A part may have an empty content.
                        partialBoundaryMatch = 1;
                        return State.CONTENT_START;
                    }
                    case COLON ->
                    {
                        throw new BadMessageException("invalid empty header name");
                    }
                    default ->
                    {
                        if (Character.isWhitespace(token.getByte()))
                        {
                            if (text.length() == 0)
                                throw new BadMessageException("invalid leading whitespace before header");
                        }
                        else
                        {
                            // Beginning of a field name.
                            incrementAndCheckPartHeadersLength();
                            text.append(token.getByte());
                            return State.HEADER_NAME;
                        }
                    }
                }
            }
            return State.HEADER_START;
        }

        private boolean parseHeaderName(ByteBuffer buffer)
        {
            while (buffer.hasRemaining())
            {
                HttpTokens.Token token = next(buffer);
                switch (token.getType())
                {
                    case COLON ->
                    {
                        // End of field name.
                        incrementAndCheckPartHeadersLength();
                        fieldName = text.takeCompleteString(null);
                        trailingWhiteSpaces = 0;
                        return true;
                    }
                    case ALPHA, DIGIT, TCHAR ->
                    {
                        byte current = token.getByte();
                        if (trailingWhiteSpaces > 0)
                            throw new BadMessageException("invalid header name");
                        incrementAndCheckPartHeadersLength();
                        text.append(current);
                    }
                    default ->
                    {
                        byte current = token.getByte();
                        if (Character.isWhitespace(current))
                        {
                            incrementAndCheckPartHeadersLength();
                            ++trailingWhiteSpaces;
                        }
                        else
                        {
                            throw new BadMessageException("invalid header name");
                        }
                    }
                }
            }
            return false;
        }

        private boolean parseHeaderValue(ByteBuffer buffer)
        {
            while (buffer.hasRemaining())
            {
                HttpTokens.Token token = next(buffer);
                switch (token.getType())
                {
                    case CR ->
                    {
                        // Ignore CR and loop around;
                    }
                    case LF ->
                    {
                        // End of header value.
                        // Ignore trailing whitespace.
                        fieldValue = text.toCompleteString().stripTrailing();
                        text.reset();
                        notifyPartHeader(fieldName, fieldValue);
                        fieldName = null;
                        fieldValue = null;
                        return true;
                    }
                    default ->
                    {
                        byte current = token.getByte();
                        incrementAndCheckPartHeadersLength();
                        if (Character.isWhitespace(current))
                        {
                            // Ignore leading whitespace.
                            if (text.length() > 0)
                                text.append(" ");
                        }
                        else
                        {
                            text.append(current);
                        }
                    }
                }
            }
            return false;
        }

        private void incrementAndCheckPartHeadersLength()
        {
            ++partHeadersLength;
            int max = getPartHeadersMaxLength();
            if (max > 0 && partHeadersLength > max)
                throw new IllegalStateException("headers max length exceeded: %d".formatted(max));
        }

        private boolean parseContent(Content.Chunk chunk)
        {
            ByteBuffer buffer = chunk.getByteBuffer();

            if (partialBoundaryMatch > 0)
            {
                int boundaryMatch = boundaryFinder.startsWith(buffer, partialBoundaryMatch);
                if (boundaryMatch > 0)
                {
                    if (boundaryMatch == boundaryFinder.getLength())
                    {
                        // The boundary was fully matched, so the part is complete.
                        buffer.position(buffer.position() + boundaryMatch - partialBoundaryMatch);
                        if (!crContent)
                        {
                            MultiPartCompliance.Violation violation = MultiPartCompliance.Violation.LF_LINE_TERMINATION;
                            addEndOfLineViolation(violation);
                            if (!compliance.allows(violation))
                                throw new BadMessageException("invalid LF-only EOL");
                        }
                        partialBoundaryMatch = 0;
                        crContent = false;
                        notifyPartContent(Content.Chunk.EOF);
                        notifyPartEnd();
                        return true;
                    }
                    else
                    {
                        // The boundary was partially matched, but it
                        // is not clear yet if it is content or boundary.
                        buffer.position(buffer.limit());
                        partialBoundaryMatch = boundaryMatch;
                        return false;
                    }
                }
                else
                {
                    // There was a partial boundary match in the previous chunk, but now a
                    // mismatch was found. Handle the special case of parts with no content.
                    if (state == State.CONTENT_START)
                    {
                        // There is some content, reset the boundary match.
                        partialBoundaryMatch = 0;
                        return false;
                    }

                    // Output as content the previous partial match.
                    notifyCRContent();
                    ByteBuffer content = ByteBuffer.wrap(boundaryFinder.getPattern(), 0, partialBoundaryMatch);
                    partialBoundaryMatch = 0;
                    Content.Chunk partContentChunk = Content.Chunk.from(content, false);
                    notifyPartContent(partContentChunk);
                    partContentChunk.release();
                    return false;
                }
            }

            // Search for a full boundary.
            int boundaryOffset = boundaryFinder.match(buffer);
            if (boundaryOffset >= 0)
            {
                if (boundaryOffset == 0)
                {
                    if (!crContent)
                    {
                        MultiPartCompliance.Violation violation = MultiPartCompliance.Violation.LF_LINE_TERMINATION;
                        addEndOfLineViolation(violation);
                        if (!compliance.allows(violation))
                            throw new BadMessageException("invalid LF-only EOL");
                    }
                    crContent = false;
                }

                // Emit as content the last CR byte of the previous chunk, if any.
                notifyCRContent();

                // Emit as content the bytes of this chunk that are before the boundary.
                int position = buffer.position();
                int length = boundaryOffset;
                // BoundaryFinder is configured to search for '\n--Boundary';
                // if '\r\n--Boundary' is found, then the '\r' is not content.
                if (length > 0 && buffer.get(position + length - 1) == '\r')
                {
                    --length;
                }
                else
                {
                    MultiPartCompliance.Violation violation = MultiPartCompliance.Violation.LF_LINE_TERMINATION;
                    addEndOfLineViolation(violation);
                    if (!compliance.allows(violation))
                        throw new BadMessageException("invalid LF-only EOL");
                }
                Content.Chunk content = asSlice(chunk, position, length, true);
                buffer.position(position + boundaryOffset + boundaryFinder.getLength());
                notifyPartContent(content);
                notifyPartEnd();
                return true;
            }

            // Search for a partial boundary at the end of the buffer.
            partialBoundaryMatch = boundaryFinder.endsWith(buffer);
            if (partialBoundaryMatch > 0 && partialBoundaryMatch == buffer.remaining())
            {
                // The boundary was partially matched, but it
                // is not clear yet if it is content or boundary.
                buffer.position(buffer.limit());
                return false;
            }

            // Here, either the boundary was not found, or it was partially found at the end.

            // Emit as content the last CR byte of the previous chunk, if any.
            notifyCRContent();

            // Emit as content the bytes of this chunk, until the partial boundary match (if any).
            int limit = buffer.limit();
            int sliceLimit = limit - partialBoundaryMatch;
            // BoundaryFinder is configured to search for '\n--Boundary';
            // if '\r\n--Bo' is found, then the '\r' may not be content,
            // but remember it in case there is a boundary mismatch.
            if (buffer.get(sliceLimit - 1) == '\r')
            {
                crContent = true;
                --sliceLimit;
            }
            int position = buffer.position();
            Content.Chunk content = asSlice(chunk, position, sliceLimit - position, false);
            buffer.position(limit);
            if (content.hasRemaining())
                notifyPartContent(content);
            return false;
        }

        private void notifyCRContent()
        {
            if (!crContent)
                return;
            crContent = false;
            Content.Chunk partContentChunk = Content.Chunk.from(CR.slice(), false);
            notifyPartContent(partContentChunk);
            partContentChunk.release();
        }

        private Content.Chunk asSlice(Content.Chunk chunk, int position, int length, boolean last)
        {
            if (chunk.isLast() && !chunk.hasRemaining())
                return chunk;
            if (length == 0)
                return last ? Content.Chunk.EOF : Content.Chunk.EMPTY;
            return Content.Chunk.asChunk(chunk.getByteBuffer().slice(position, length), last, chunk);
        }

        private void notifyPartBegin()
        {
            try
            {
                listener.onPartBegin();
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("failure while notifying listener {}", listener, x);
            }
        }

        private void notifyPartHeader(String name, String value)
        {
            try
            {
                listener.onPartHeader(name, value);
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("failure while notifying listener {}", listener, x);
            }
        }

        private void notifyPartHeaders()
        {
            try
            {
                listener.onPartHeaders();
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("failure while notifying listener {}", listener, x);
            }
        }

        private void notifyPartContent(Content.Chunk chunk)
        {
            try
            {
                listener.onPartContent(chunk);
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("failure while notifying listener {}", listener, x);
            }
        }

        private void notifyPartEnd()
        {
            try
            {
                listener.onPartEnd();
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("failure while notifying listener {}", listener, x);
            }
        }

        private void notifyComplete()
        {
            try
            {
                listener.onComplete();
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("failure while notifying listener {}", listener, x);
            }
        }

        private void notifyFailure(Throwable failure)
        {
            try
            {
                listener.onFailure(failure);
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("failure while notifying listener {}", listener, x);
            }
        }

        private void notifyEndOfLineViolations()
        {
            if (eols != null)
            {
                for (MultiPartCompliance.Violation violation: eols)
                {
                    notifyViolation(violation);
                }
                eols = null;
            }
        }

        private void addEndOfLineViolation(MultiPartCompliance.Violation violation)
        {
            if (eols == null)
                eols = EnumSet.of(violation);
            else
                eols.add(violation);
        }

        private void notifyViolation(MultiPartCompliance.Violation violation)
        {
            try
            {
                listener.onViolation(violation);
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("failure while notifying listener {}", listener, x);
            }
        }

        /**
         * <p>A listener for events emitted by a {@link Parser}.</p>
         */
        public interface Listener
        {
            /**
             * <p>Callback method invoked when the begin of a new part is parsed.</p>
             */
            default void onPartBegin()
            {
            }

            /**
             * <p>Callback method invoked when a part header is parsed.</p>
             *
             * @param name the header name
             * @param value the header value
             */
            default void onPartHeader(String name, String value)
            {
            }

            /**
             * <p>Callback method invoked when all the part headers have been parsed.</p>
             */
            default void onPartHeaders()
            {
            }

            /**
             * <p>Callback method invoked when a part content {@code Chunk} has been parsed.</p>
             * <p>The {@code Chunk} must be {@link Content.Chunk#retain()} retained} if it
             * not consumed by this method (for example, stored away for later use).</p>
             *
             * @param chunk the part content chunk
             */
            default void onPartContent(Content.Chunk chunk)
            {
            }

            /**
             * <p>Callback method invoked when the end of a part is parsed.</p>
             */
            default void onPartEnd()
            {
            }

            /**
             * <p>Callback method invoked when the whole multipart content has been parsed.</p>
             */
            default void onComplete()
            {
            }

            /**
             * <p>Callback method invoked when the parser cannot parse the multipart content.</p>
             *
             * @param failure the failure cause
             */
            default void onFailure(Throwable failure)
            {
            }

            /**
             * <p>Callback method invoked when the low level parser encounters
             * a fundamental multipart violation</p>>
             *
             * @param violation the violation detected
             */
            default void onViolation(MultiPartCompliance.Violation violation)
            {
            }
        }

        private enum State
        {
            PREAMBLE, BOUNDARY, BOUNDARY_CLOSE, HEADER_START, HEADER_NAME, HEADER_VALUE, CONTENT_START, CONTENT, EPILOGUE
        }
    }

    /**
     * <p>A {@link Parser.Listener} that emits {@link Part} objects.</p>
     * <p>Subclasses implement {@link #onPartContent(Content.Chunk)}
     * and {@link #onPart(String, String, HttpFields)} to create the
     * parts of the multipart content.</p>
     */
    public abstract static class AbstractPartsListener implements Parser.Listener
    {
        private static final Logger LOG = LoggerFactory.getLogger(AbstractPartsListener.class);

        private final HttpFields.Mutable fields = HttpFields.build();
        private String name;
        private String fileName;

        public String getName()
        {
            return name;
        }

        public String getFileName()
        {
            return fileName;
        }

        @Override
        public void onPartHeader(String headerName, String headerValue)
        {
            if (HttpHeader.CONTENT_DISPOSITION.is(headerName))
            {
                String namePrefix = "name=";
                String fileNamePrefix = "filename=";
                for (Iterator<String> tokens = CONTENT_DISPOSITION_TOKENIZER.tokenize(headerValue); tokens.hasNext();)
                {
                    String token = tokens.next();
                    String lowerToken = StringUtil.asciiToLowerCase(token);
                    if (lowerToken.startsWith(namePrefix))
                    {
                        int index = lowerToken.indexOf(namePrefix);
                        String value = token.substring(index + namePrefix.length()).trim();
                        name = CONTENT_DISPOSITION_TOKENIZER.unquote(value); // TODO should the tokenizer be returnQuotes == false ?
                    }
                    else if (lowerToken.startsWith(fileNamePrefix))
                    {
                        fileName = fileNameValue(token);
                    }
                }
            }
            fields.add(new HttpField(headerName, headerValue));
        }

        private String fileNameValue(String token)
        {
            int idx = token.indexOf('=');
            String value = token.substring(idx + 1).trim();

            if (value.matches(".??[a-zA-Z]:\\\\[^\\\\].*"))
            {
                // Matched incorrectly escaped IE filenames that have the whole
                // path, as in C:\foo, just strip any leading & trailing quotes.
                char first = value.charAt(0);
                if (first == '"' || first == '\'')
                    value = value.substring(1);
                char last = value.charAt(value.length() - 1);
                if (last == '"' || last == '\'')
                    value = value.substring(0, value.length() - 1);
                return value;
            }
            else
            {
                return CONTENT_DISPOSITION_TOKENIZER.unquote(value);
            }
        }

        @Override
        public void onPartEnd()
        {
            String name = getName();
            this.name = null;
            String fileName = getFileName();
            this.fileName = null;
            HttpFields headers = fields.asImmutable();
            fields.clear();
            notifyPart(name, fileName, headers);
        }

        /**
         * <p>Callback method invoked when a {@link Part} has been parsed.</p>
         *
         * @param name the part name
         * @param fileName the part fileName
         * @param headers the part headers
         */
        public abstract void onPart(String name, String fileName, HttpFields headers);

        private void notifyPart(String name, String fileName, HttpFields headers)
        {
            try
            {
                onPart(name, fileName, headers);
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("failure while notifying part {}", name, x);
            }
        }
    }
}
