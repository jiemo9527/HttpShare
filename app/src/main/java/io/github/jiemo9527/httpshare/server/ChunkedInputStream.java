package io.github.jiemo9527.httpshare.server;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** 解码 Transfer-Encoding: chunked 请求体（macOS Finder、部分 WebDAV 客户端上传时使用）。 */
final class ChunkedInputStream extends FilterInputStream {

    private long left;
    private boolean eof;

    ChunkedInputStream(InputStream in) {
        super(in);
    }

    private String line() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(16);
        int c;
        while ((c = in.read()) >= 0 && c != '\n') {
            if (c != '\r') {
                b.write(c);
            }
            if (b.size() > 4096) {
                throw new IOException("chunk header too long");
            }
        }
        if (c < 0) {
            throw new EOFException("chunked 数据不完整");
        }
        return b.toString("US-ASCII");
    }

    private boolean next() throws IOException {
        if (eof) {
            return false;
        }
        if (left == 0) {
            String l = line();
            if (l.isEmpty()) {
                l = line(); // 上一块数据后的 CRLF
            }
            int semi = l.indexOf(';');
            try {
                left = Long.parseLong((semi >= 0 ? l.substring(0, semi) : l).trim(), 16);
            } catch (NumberFormatException e) {
                throw new IOException("chunk 长度无效");
            }
            if (left < 0) {
                throw new IOException("chunk 长度无效");
            }
            if (left == 0) {
                // 跳过 trailer 直到空行
                while (!line().isEmpty()) {
                    // ignore
                }
                eof = true;
                return false;
            }
        }
        return true;
    }

    @Override
    public int read() throws IOException {
        byte[] b = new byte[1];
        int n = read(b, 0, 1);
        return n < 0 ? -1 : b[0] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (!next()) {
            return -1;
        }
        int n = in.read(b, off, (int) Math.min(len, left));
        if (n < 0) {
            throw new EOFException("chunked 数据不完整");
        }
        left -= n;
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        byte[] buf = new byte[8192];
        long total = 0;
        while (total < n) {
            int r = read(buf, 0, (int) Math.min(buf.length, n - total));
            if (r < 0) {
                break;
            }
            total += r;
        }
        return total;
    }

    @Override
    public int available() {
        return 0;
    }

    @Override
    public void close() {
        // 不关闭底层 socket 流
    }
}
