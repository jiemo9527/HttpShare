package io.github.jiemo9527.httpshare.server;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class Streams {
    private Streams() {
    }

    /** 复制 length 字节；length < 0 表示读到结束 */
    static long copy(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        while (length < 0 || total < length) {
            int want = length < 0 ? buf.length : (int) Math.min(buf.length, length - total);
            int n = in.read(buf, 0, want);
            if (n < 0) {
                if (length >= 0) {
                    throw new EOFException("数据不完整");
                }
                break;
            }
            out.write(buf, 0, n);
            total += n;
        }
        return total;
    }

    /** 跳过剩余请求体，保证连接可复用/正常关闭 */
    static void drain(InputStream in, long length) {
        try {
            copy(in, OutputStream.nullOutputStream(), length);
        } catch (IOException ignored) {
        }
    }
}
