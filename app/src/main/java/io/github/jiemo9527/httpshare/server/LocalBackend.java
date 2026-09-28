package io.github.jiemo9527.httpshare.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public final class LocalBackend implements FileBackend {

    @Override
    public Entry stat(String path) {
        File f = new File(path);
        if (!f.exists()) {
            return null;
        }
        return new Entry(f.getName(), f.isDirectory(), f.isDirectory() ? 0 : f.length(), f.lastModified());
    }

    @Override
    public List<Entry> list(String dir) throws IOException {
        File[] files = new File(dir).listFiles();
        if (files == null) {
            throw new IOException("无法读取目录（权限不足？可对该共享开启 root）");
        }
        List<Entry> out = new ArrayList<>(files.length);
        for (File f : files) {
            boolean d = f.isDirectory();
            out.add(new Entry(f.getName(), d, d ? 0 : f.length(), f.lastModified()));
        }
        return out;
    }

    @Override
    public InputStream open(String path, long offset) throws IOException {
        FileInputStream in = new FileInputStream(path);
        if (offset > 0) {
            in.getChannel().position(offset);
        }
        return in;
    }

    @Override
    public void write(String path, InputStream in, long length) throws IOException {
        File tmp = new File(path + ".part");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            Streams.copy(in, out, length);
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        File dst = new File(path);
        dst.delete();
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            throw new IOException("写入失败");
        }
    }

    @Override
    public void mkdir(String path) throws IOException {
        if (!new File(path).mkdirs()) {
            throw new IOException("创建目录失败");
        }
    }

    @Override
    public void delete(String path) throws IOException {
        if (!deleteRec(new File(path))) {
            throw new IOException("删除失败");
        }
    }

    private static boolean deleteRec(File f) {
        if (f.isDirectory() && !isSymlink(f)) {
            File[] c = f.listFiles();
            if (c != null) {
                for (File x : c) {
                    deleteRec(x);
                }
            }
        }
        return f.delete();
    }

    private static boolean isSymlink(File f) {
        try {
            return !f.getCanonicalFile().equals(f.getAbsoluteFile());
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void rename(String from, String to) throws IOException {
        if (new File(to).exists() || !new File(from).renameTo(new File(to))) {
            throw new IOException("重命名失败（目标已存在？）");
        }
    }
}
