package io.github.jiemo9527.httpshare.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * 边读边写的 ZIP 打包（不生成临时文件，下载立即开始）。
 * 每个条目用「数据描述符」在写完后补 CRC/大小；超过 4 GB 自动使用 ZIP64。
 * 已压缩格式（图片/视频/压缩包/APK）用 Deflate 0 级（仅存储，几乎不占 CPU），其余用 1 级快速压缩。
 */
final class Zip {

    private static final int SIG_LOCAL = 0x04034b50;
    private static final int SIG_DESC = 0x08074b50;
    private static final int SIG_CENTRAL = 0x02014b50;
    private static final int SIG_END = 0x06054b50;
    private static final int SIG_END64 = 0x06064b50;
    private static final int SIG_LOC64 = 0x07064b50;
    private static final long MAX32 = 0xFFFFFFFFL;
    /** bit3 数据描述符 + bit11 UTF-8 文件名 */
    private static final int FLAGS = 0x0008 | 0x0800;

    private static final class Item {
        byte[] name;
        long crc;
        long csize;
        long size;
        long offset;
        int dosTime;
        boolean dir;
        /** 本地头已声明 ZIP64（数据描述符用 64 位大小） */
        boolean z64;
    }

    /** 已知大小超过此值的文件预先按 ZIP64 写（留出存储模式 Deflate 块头的膨胀余量） */
    private static final long Z64_THRESHOLD = 0xF0000000L;

    private final CountingOut out;
    private final List<Item> items = new ArrayList<>();
    private final byte[] buf = new byte[64 * 1024];

    Zip(OutputStream raw) {
        this.out = new CountingOut(raw);
    }

    long written() {
        return out.count;
    }

    void dir(String name, long mtime) throws IOException {
        Item it = header(name.endsWith("/") ? name : name + "/", mtime, true, false);
        // 目录条目没有数据
        descriptor(it);
    }

    /** 写入一个文件；in 读到结束为止。expected 为 stat 得到的大小，用于决定是否用 ZIP64 */
    long file(String name, long mtime, long expected, InputStream in) throws IOException {
        Item it = header(name, mtime, false, expected >= Z64_THRESHOLD);
        CRC32 crc = new CRC32();
        Deflater def = new Deflater(stored(name) ? Deflater.NO_COMPRESSION : Deflater.BEST_SPEED, true);
        long before = out.count;
        long total = 0;
        DeflaterOutputStream dos = new DeflaterOutputStream(new NonClosing(out), def, 64 * 1024);
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                crc.update(buf, 0, n);
                dos.write(buf, 0, n);
                total += n;
            }
            dos.finish();
        } finally {
            def.end();
        }
        it.crc = crc.getValue();
        it.size = total;
        it.csize = out.count - before;
        descriptor(it);
        return total;
    }

    private static boolean stored(String name) {
        int d = name.lastIndexOf('.');
        String x = d < 0 ? "" : name.substring(d + 1).toLowerCase(Locale.ROOT);
        return x.matches("jpe?g|png|gif|webp|heic|heif|avif|mp4|mkv|webm|mov|3gp|avi|m4v|ts|"
                + "mp3|m4a|aac|ogg|opus|flac|amr|zip|rar|7z|gz|xz|bz2|zst|apk|apks|xapk|jar|br|lz4|obb|pdf|docx|xlsx|pptx");
    }

    private Item header(String name, long mtime, boolean dir, boolean z64) throws IOException {
        Item it = new Item();
        it.z64 = z64;
        it.name = name.getBytes(StandardCharsets.UTF_8);
        it.offset = out.count;
        it.dosTime = dosTime(mtime);
        it.dir = dir;
        items.add(it);
        le32(SIG_LOCAL);
        le16(45);                 // 需要的版本（ZIP64）
        le16(FLAGS);
        le16(dir ? 0 : 8);        // 方法：目录 0，文件 Deflate
        le32(it.dosTime);
        le32(0);                  // CRC、大小写在数据描述符里
        le32(z64 ? (int) MAX32 : 0);
        le32(z64 ? (int) MAX32 : 0);
        le16(it.name.length);
        le16(z64 ? 20 : 0);
        out.write(it.name);
        if (z64) {
            le16(0x0001);         // ZIP64 扩展：大小此时未知，填 0，实际值在数据描述符里
            le16(16);
            le64(0);
            le64(0);
        }
        return it;
    }

    private void descriptor(Item it) throws IOException {
        le32(SIG_DESC);
        le32((int) it.crc);
        if (zip64(it)) {
            le64(it.csize);
            le64(it.size);
        } else {
            le32((int) it.csize);
            le32((int) it.size);
        }
    }

    /** 数据描述符是否用 64 位：本地头声明过 ZIP64，或文件实际比 stat 时变大越过 4 GB（兜底，与 JDK 行为一致） */
    private static boolean zip64(Item it) {
        return it.z64 || it.size >= MAX32 || it.csize >= MAX32;
    }

    void finish() throws IOException {
        long cdStart = out.count;
        for (Item it : items) {
            boolean big = zip64(it);
            boolean farOffset = it.offset >= MAX32;
            int extraLen = (big ? 16 : 0) + (farOffset ? 8 : 0);
            le32(SIG_CENTRAL);
            le16(0x0300 | 45);    // 创建系统 Unix，版本 4.5
            le16(45);
            le16(FLAGS);
            le16(it.dir ? 0 : 8);
            le32(it.dosTime);
            le32((int) it.crc);
            le32(big ? (int) MAX32 : (int) it.csize);
            le32(big ? (int) MAX32 : (int) it.size);
            le16(it.name.length);
            le16(extraLen > 0 ? extraLen + 4 : 0);
            le16(0);              // 注释
            le16(0);              // 磁盘号
            le16(0);              // 内部属性
            le32(it.dir ? (040755 << 16) | 0x10 : (0100644 << 16)); // Unix 权限 + DOS 目录位
            le32(farOffset ? (int) MAX32 : (int) it.offset);
            out.write(it.name);
            if (extraLen > 0) {
                le16(0x0001);
                le16(extraLen);
                if (big) {
                    le64(it.size);
                    le64(it.csize);
                }
                if (farOffset) {
                    le64(it.offset);
                }
            }
        }
        long cdEnd = out.count;
        long cdSize = cdEnd - cdStart;
        boolean z64 = items.size() >= 0xFFFF || cdStart >= MAX32 || cdSize >= MAX32;
        if (z64) {
            le32(SIG_END64);
            le64(44);
            le16(45);
            le16(45);
            le32(0);
            le32(0);
            le64(items.size());
            le64(items.size());
            le64(cdSize);
            le64(cdStart);
            le32(SIG_LOC64);
            le32(0);
            le64(cdEnd);
            le32(1);
        }
        le32(SIG_END);
        le16(0);
        le16(0);
        le16(z64 ? 0xFFFF : items.size());
        le16(z64 ? 0xFFFF : items.size());
        le32(z64 ? (int) MAX32 : (int) cdSize);
        le32(z64 ? (int) MAX32 : (int) cdStart);
        le16(0);
        out.flush();
    }

    private static int dosTime(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms <= 0 ? System.currentTimeMillis() : ms);
        int y = c.get(Calendar.YEAR);
        if (y < 1980) {
            return (1 << 21) | (1 << 16);
        }
        return ((y - 1980) << 25) | ((c.get(Calendar.MONTH) + 1) << 21) | (c.get(Calendar.DAY_OF_MONTH) << 16)
                | (c.get(Calendar.HOUR_OF_DAY) << 11) | (c.get(Calendar.MINUTE) << 5) | (c.get(Calendar.SECOND) >> 1);
    }

    private void le16(int v) throws IOException {
        out.write(v & 0xff);
        out.write((v >>> 8) & 0xff);
    }

    private void le32(int v) throws IOException {
        le16(v & 0xffff);
        le16((v >>> 16) & 0xffff);
    }

    private void le64(long v) throws IOException {
        le32((int) v);
        le32((int) (v >>> 32));
    }

    private static final class CountingOut extends OutputStream {
        final OutputStream o;
        long count;

        CountingOut(OutputStream o) {
            this.o = o;
        }

        @Override
        public void write(int b) throws IOException {
            o.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            o.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            o.flush();
        }
    }

    private static final class NonClosing extends OutputStream {
        final OutputStream o;

        NonClosing(OutputStream o) {
            this.o = o;
        }

        @Override
        public void write(int b) throws IOException {
            o.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            o.write(b, off, len);
        }

        @Override
        public void close() {
        }
    }
}
