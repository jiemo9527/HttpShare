package io.github.jiemo9527.httpshare.server;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * root 模式：每个操作起一个 su 进程执行 toybox 命令。
 * 路径全部单引号转义，不拼接任何未转义的用户输入。
 */
public final class RootBackend implements FileBackend {

    /** stat 格式：类型|大小|修改秒|名称（名称放最后，允许包含 |） */
    private static final String FMT = "'%F|%s|%Y|%n'";

    public static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static Process su(String cmd) throws IOException {
        return new ProcessBuilder("su", "-c", cmd).start();
    }

    /** 执行并返回 stdout，非 0 退出抛出 stderr */
    static String run(String cmd) throws IOException {
        Process p = su(cmd);
        try {
            p.getOutputStream().close();
            byte[] out = readAll(p.getInputStream());
            byte[] err = readAll(p.getErrorStream());
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroy();
                throw new IOException("root 命令超时");
            }
            if (p.exitValue() != 0) {
                String e = new String(err, StandardCharsets.UTF_8).trim();
                throw new IOException(e.isEmpty() ? "root 命令失败(" + p.exitValue() + ")" : e);
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            p.destroy();
            throw new IOException("中断");
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) {
            bo.write(b, 0, n);
        }
        return bo.toByteArray();
    }

    public static boolean available() {
        try {
            return run("id -u").trim().equals("0");
        } catch (Exception e) {
            return false;
        }
    }

    private static Entry parse(String line) {
        String[] p = line.split("\\|", 4);
        if (p.length < 4) {
            return null;
        }
        String name = p[3];
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash < name.length() - 1) {
            name = name.substring(slash + 1);
        }
        boolean dir = p[0].contains("directory");
        long size = 0;
        long mt = 0;
        try {
            size = Long.parseLong(p[1]);
            mt = Long.parseLong(p[2]) * 1000L;
        } catch (NumberFormatException ignored) {
        }
        return new Entry(name, dir, dir ? 0 : size, mt);
    }

    @Override
    public Entry stat(String path) throws IOException {
        String out;
        try {
            out = run("stat -L -c " + FMT + " " + q(path));
        } catch (IOException e) {
            return null;
        }
        return parse(out.trim());
    }

    @Override
    public List<Entry> list(String dir) throws IOException {
        // 先确认是可访问目录，再列出；失效符号链接 stat -L 会失败，改用不跟随的 stat 兜底
        String cmd = "cd " + q(dir) + " || exit 1; for f in * .*; do "
                + "case \"$f\" in .|..) continue;; esac; "
                + "[ -e \"$f\" ] || [ -L \"$f\" ] || continue; "
                + "stat -L -c " + FMT + " -- \"$f\" 2>/dev/null || stat -c " + FMT + " -- \"$f\"; done";
        String out = run(cmd);
        List<Entry> list = new ArrayList<>();
        for (String line : out.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            Entry e = parse(line);
            if (e != null) {
                list.add(e);
            }
        }
        return list;
    }

    @Override
    public InputStream open(String path, long offset) throws IOException {
        String cmd = offset > 0
                ? "exec tail -c +" + (offset + 1) + " " + q(path)
                : "exec cat " + q(path);
        final Process p = su(cmd);
        p.getOutputStream().close();
        return new FilterInputStream(p.getInputStream()) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    p.destroy();
                }
            }
        };
    }

    @Override
    public void write(String path, InputStream in, long length) throws IOException {
        String part = path + ".part";
        Process p = su("cat > " + q(part) + " && mv -f " + q(part) + " " + q(path));
        try (OutputStream out = p.getOutputStream()) {
            Streams.copy(in, out, length);
        } catch (IOException e) {
            p.destroy();
            try {
                run("rm -f " + q(part));
            } catch (IOException ignored) {
            }
            throw e;
        }
        try {
            byte[] err = readAll(p.getErrorStream());
            if (p.waitFor() != 0) {
                throw new IOException(new String(err, StandardCharsets.UTF_8).trim());
            }
        } catch (InterruptedException e) {
            throw new IOException("中断");
        }
    }

    @Override
    public void mkdir(String path) throws IOException {
        run("mkdir -p " + q(path));
    }

    @Override
    public void delete(String path) throws IOException {
        run("rm -rf -- " + q(path));
    }

    @Override
    public void rename(String from, String to) throws IOException {
        run("[ ! -e " + q(to) + " ] && mv -- " + q(from) + " " + q(to));
    }
}
