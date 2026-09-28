package io.github.jiemo9527.httpshare.server;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** 文件访问抽象：普通模式直接用 java.io.File，root 模式通过 su 执行 toybox 命令。 */
public interface FileBackend {

    final class Entry {
        public final String name;
        public final boolean dir;
        public final long size;
        /** 毫秒 */
        public final long mtime;

        public Entry(String name, boolean dir, long size, long mtime) {
            this.name = name;
            this.dir = dir;
            this.size = size;
            this.mtime = mtime;
        }
    }

    /** 不存在返回 null */
    Entry stat(String path) throws IOException;

    List<Entry> list(String dir) throws IOException;

    /** 从 offset 开始读取 */
    InputStream open(String path, long offset) throws IOException;

    /** 写入恰好 length 字节 */
    void write(String path, InputStream in, long length) throws IOException;

    void mkdir(String path) throws IOException;

    void delete(String path) throws IOException;

    void rename(String from, String to) throws IOException;
}
