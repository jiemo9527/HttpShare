package io.github.jiemo9527.httpshare.server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 轻量 HTTP/1.1 文件服务器（无第三方依赖）。
 *
 * <pre>
 * GET  /                      网页（assets/web.html，单页应用）
 * GET  /api/info              {auth, loggedIn, upload, modify, https}
 * GET  /showme                同步查阅页；GET /api/showme?v=版本&c=客户端  长轮询当前目录
 * POST /api/login             body=密码，成功设置会话 Cookie
 * POST /api/logout
 * GET  /api/ls?p=/0/sub       目录列表（p 的第一段为共享序号）
 * GET  /f/0/sub/file[?dl=1]   下载，支持 Range
 * PUT  /f/0/sub/file          上传（需上传权限；覆盖另需改名/删除权限）
 * POST /api/mkdir|rm|mv?p=..[&to=新名]
 * </pre>
 *
 * 除登录外所有非 GET 请求必须带 X-HS: 1 头（跨站表单无法设置自定义头，防 CSRF）。
 * 设置密码后也接受 HTTP Basic（用户名任意），方便 curl/wget。
 */
public final class HttpServer {

    public interface Config {
        List<Share> shares();

        /** 上传 / 新建文件夹 */
        boolean allowUpload();

        /** 改名 / 删除 / 覆盖 */
        boolean allowModify();

        boolean hasPassword();

        boolean checkPassword(String pw);

        byte[] webPage();

        byte[] showmePage();
    }

    public static final class Share {
        public final String name;
        public final String path;
        public final boolean root;

        public Share(String name, String path, boolean root) {
            this.name = name;
            this.path = path;
            this.root = root;
        }
    }

    public interface Listener {
        void onLog(String line);
    }

    private static final long SESSION_TTL = 7L * 24 * 3600 * 1000;
    public static final int MAX_FAILS = 3;
    public static final long LOCK_MS = 2L * 3600 * 1000;

    private final Config config;
    private final int port;
    private final SSLContext ssl;
    private final Listener listener;
    private final FileBackend local = new LocalBackend();
    private final FileBackend root = new RootBackend();
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    /** 登录失败记录只在内存：重启服务即解除封锁 */
    private final Map<String, long[]> fails = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final ShowMe showme = new ShowMe();

    private ServerSocket server;
    private ExecutorService pool;
    private volatile boolean running;

    public HttpServer(Config config, int port, SSLContext ssl, Listener listener) {
        this.config = config;
        this.port = port;
        this.ssl = ssl;
        this.listener = listener;
    }

    public ShowMe showme() {
        return showme;
    }

    /** 当前被封锁的 IP 与剩余毫秒 */
    public Map<String, Long> lockedIps() {
        Map<String, Long> out = new HashMap<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, long[]> e : fails.entrySet()) {
            long[] v = e.getValue();
            if (v[0] >= MAX_FAILS && now - v[1] < LOCK_MS) {
                out.put(e.getKey(), LOCK_MS - (now - v[1]));
            }
        }
        return out;
    }

    public void unlockAll() {
        fails.clear();
    }

    public void start() throws IOException {
        server = ssl != null
                ? ssl.getServerSocketFactory().createServerSocket()
                : new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port), 64);
        pool = new ThreadPoolExecutor(0, 48, 30, TimeUnit.SECONDS, new SynchronousQueue<>(),
                new ThreadPoolExecutor.AbortPolicy());
        running = true;
        Thread t = new Thread(this::acceptLoop, "HttpShare-accept");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        showme.shutdown();
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
        }
        if (pool != null) {
            pool.shutdownNow();
        }
        sessions.clear();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                try {
                    pool.execute(() -> serve(s));
                } catch (Exception busy) {
                    closeQuiet(s);
                }
            } catch (IOException e) {
                if (!running) {
                    return;
                }
            }
        }
    }

    private static void closeQuiet(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private void log(String s) {
        if (listener != null) {
            listener.onLog(s);
        }
    }

    // ================================================================== 连接

    private static final class Req {
        String method;
        String path;
        String rawQuery = "";
        final Map<String, String> query = new HashMap<>();
        final Map<String, String> headers = new HashMap<>();
        String ip;
        long contentLength;
        InputStream body;

        String h(String k) {
            return headers.get(k.toLowerCase(Locale.ROOT));
        }

        String q(String k) {
            return query.get(k);
        }
    }

    static final class HttpError extends Exception {
        final int code;

        HttpError(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(60_000);
            InputStream in = new BufferedInputStream(s.getInputStream(), 16 * 1024);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
            String ip = s.getInetAddress().getHostAddress();
            while (running) {
                Req r = readRequest(in, ip);
                if (r == null) {
                    break;
                }
                boolean keep = handle(r, out);
                out.flush();
                // 未读完的请求体：尝试丢弃，太大则直接断开
                if (r.contentLength > 0) {
                    if (r.contentLength > 1024 * 1024) {
                        break;
                    }
                    Streams.drain(r.body, r.contentLength);
                }
                if (!keep || "close".equalsIgnoreCase(r.h("connection"))) {
                    break;
                }
            }
        } catch (SocketException ignored) {
        } catch (Exception e) {
            log("连接异常: " + e);
        } finally {
            closeQuiet(s);
        }
    }

    private Req readRequest(InputStream in, String ip) throws IOException {
        String line = readLine(in);
        if (line == null || line.isEmpty()) {
            return null;
        }
        String[] p = line.split(" ");
        if (p.length < 3) {
            return null;
        }
        Req r = new Req();
        r.ip = ip;
        r.method = p[0];
        String target = p[1];
        int qi = target.indexOf('?');
        String rawPath = qi >= 0 ? target.substring(0, qi) : target;
        if (qi >= 0) {
            r.rawQuery = target.substring(qi + 1);
            for (String kv : r.rawQuery.split("&")) {
                if (kv.isEmpty()) {
                    continue;
                }
                int e = kv.indexOf('=');
                r.query.put(dec(e >= 0 ? kv.substring(0, e) : kv), e >= 0 ? dec(kv.substring(e + 1)) : "");
            }
        }
        r.path = decPath(rawPath);
        int count = 0;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            if (++count > 100) {
                return null;
            }
            int c = line.indexOf(':');
            if (c > 0) {
                r.headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
            }
        }
        String cl = r.h("content-length");
        // 经 Cloudflare 隧道进来的连接都来自 127.0.0.1，取真实客户端 IP（仅信任本机回环来源）
        String cf = r.h("cf-connecting-ip");
        if (cf != null && ("127.0.0.1".equals(ip) || "::1".equals(ip)) && cf.length() < 64) {
            r.ip = cf;
        }
        try {
            r.contentLength = cl == null ? 0 : Long.parseLong(cl);
        } catch (NumberFormatException e) {
            r.contentLength = 0;
        }
        r.body = in;
        return r;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                b.write(c);
            }
            if (b.size() > 16 * 1024) {
                throw new IOException("header too long");
            }
        }
        if (c < 0 && b.size() == 0) {
            return null;
        }
        return b.toString("UTF-8");
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** 路径中的 + 是字面量，不能按表单解码 */
    private static String decPath(String s) {
        return dec(s.replace("+", "%2B"));
    }

    // ================================================================== 路由

    /** 返回是否可保持连接 */
    private boolean handle(Req r, OutputStream out) throws IOException {
        try {
            return route(r, out);
        } catch (HttpError e) {
            return sendError(out, e.code, e.getMessage());
        } catch (IOException e) {
            if (e instanceof SocketException) {
                throw e;
            }
            return sendError(out, 500, String.valueOf(e.getMessage()));
        } catch (Exception e) {
            return sendError(out, 500, e.toString());
        }
    }

    private boolean sendError(OutputStream out, int code, String msg) throws IOException {
        return send(out, code, "application/json; charset=utf-8",
                ("{\"error\":" + JSONObject.quote(msg) + "}").getBytes(StandardCharsets.UTF_8), false, null);
    }

    private boolean route(Req r, OutputStream out) throws Exception {
        String path = r.path;
        String m = r.method;

        if (path.equals("/api/info")) {
            return sendJson(out, 200, new JSONObject()
                    .put("auth", config.hasPassword())
                    .put("loggedIn", authed(r))
                    .put("upload", config.allowUpload())
                    .put("modify", config.allowModify())
                    .put("https", ssl != null), null);
        }
        if (path.equals("/api/login") && m.equals("POST")) {
            return login(r, out);
        }
        if (path.equals("/api/logout") && m.equals("POST")) {
            String sid = cookie(r, "hs_sid");
            if (sid != null) {
                sessions.remove(sid);
            }
            return sendJson(out, 200, new JSONObject().put("ok", true),
                    "hs_sid=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
        }

        boolean isApi = path.startsWith("/api/");
        boolean isFile = path.startsWith("/f/");
        if (!isApi && !isFile) {
            if (!m.equals("GET") && !m.equals("HEAD")) {
                throw new HttpError(405, "method not allowed");
            }
            boolean sm = path.equals("/showme") || path.equals("/showme/");
            byte[] page = sm ? config.showmePage() : config.webPage();
            return send(out, 200, "text/html; charset=utf-8", page, m.equals("HEAD"), null);
        }

        if (!authed(r)) {
            throw new HttpError(401, locked(r.ip) ? "尝试次数过多，已封锁" : "需要密码");
        }
        boolean mutating = !m.equals("GET") && !m.equals("HEAD");
        if (mutating && !"1".equals(r.h("x-hs"))) {
            throw new HttpError(403, "缺少 X-HS 请求头");
        }

        if (isFile) {
            Target t = resolve(path.substring(2));
            if (m.equals("GET") || m.equals("HEAD")) {
                return download(r, out, t);
            }
            if (m.equals("PUT")) {
                requireUpload();
                return upload(r, out, t);
            }
            throw new HttpError(405, "method not allowed");
        }

        switch (path) {
            case "/api/showme": {
                long v;
                try {
                    v = Long.parseLong(r.q("v") == null ? "-1" : r.q("v"));
                } catch (NumberFormatException e) {
                    v = -1;
                }
                String cid = r.q("c");
                return sendJson(out, 200, showme.poll(cid == null ? r.ip : cid, v), null);
            }
            case "/api/shares": {
                JSONArray a = new JSONArray();
                List<Share> list = config.shares();
                for (int i = 0; i < list.size(); i++) {
                    a.put(new JSONObject().put("i", i).put("name", list.get(i).name)
                            .put("root", list.get(i).root));
                }
                return sendJson(out, 200, new JSONObject().put("shares", a), null);
            }
            case "/api/ls":
                return listDir(out, resolve(param(r, "p")));
            case "/api/mkdir": {
                requireUpload();
                Target t = resolve(param(r, "p"));
                t.fs.mkdir(t.abs);
                log(r.ip + " 新建目录 " + t.abs);
                return ok(out);
            }
            case "/api/rm": {
                requireModify();
                Target t = resolve(param(r, "p"));
                if (t.rel.isEmpty()) {
                    throw new HttpError(400, "不能删除共享根目录");
                }
                t.fs.delete(t.abs);
                log(r.ip + " 删除 " + t.abs);
                return ok(out);
            }
            case "/api/mv": {
                requireModify();
                Target t = resolve(param(r, "p"));
                String to = param(r, "to");
                if (t.rel.isEmpty() || !validName(to)) {
                    throw new HttpError(400, "名称无效");
                }
                String dst = t.abs.substring(0, t.abs.lastIndexOf('/') + 1) + to;
                t.fs.rename(t.abs, dst);
                log(r.ip + " 重命名 " + t.abs + " → " + to);
                return ok(out);
            }
            default:
                throw new HttpError(404, "not found");
        }
    }

    private void requireUpload() throws HttpError {
        if (!config.allowUpload()) {
            throw new HttpError(403, "未开启上传/新建权限");
        }
    }

    private void requireModify() throws HttpError {
        if (!config.allowModify()) {
            throw new HttpError(403, "未开启改名/删除权限");
        }
    }

    private static String param(Req r, String k) throws HttpError {
        String v = r.q(k);
        if (v == null) {
            throw new HttpError(400, "缺少参数 " + k);
        }
        return v;
    }

    private boolean ok(OutputStream out) throws Exception {
        return sendJson(out, 200, new JSONObject().put("ok", true), null);
    }

    // ================================================================== 认证

    private boolean authed(Req r) {
        if (!config.hasPassword()) {
            return true;
        }
        String sid = cookie(r, "hs_sid");
        if (sid != null) {
            Long exp = sessions.get(sid);
            if (exp != null && exp > System.currentTimeMillis()) {
                return true;
            }
            sessions.remove(sid);
        }
        String a = r.h("authorization");
        if (a != null && a.regionMatches(true, 0, "Basic ", 0, 6)) {
            if (locked(r.ip)) {
                return false;
            }
            try {
                String up = new String(android.util.Base64.decode(a.substring(6).trim(),
                        android.util.Base64.DEFAULT), StandardCharsets.UTF_8);
                int c = up.indexOf(':');
                boolean good = config.checkPassword(c >= 0 ? up.substring(c + 1) : up);
                if (!good) {
                    fail(r.ip);
                }
                return good;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private boolean locked(String ip) {
        long[] f = fails.get(ip);
        return f != null && f[0] >= MAX_FAILS && System.currentTimeMillis() - f[1] < LOCK_MS;
    }

    private void fail(String ip) {
        long[] f = fails.computeIfAbsent(ip, k -> new long[2]);
        long n;
        synchronized (f) {
            if (System.currentTimeMillis() - f[1] > LOCK_MS) {
                f[0] = 0;
            }
            n = ++f[0];
            f[1] = System.currentTimeMillis();
        }
        if (n >= MAX_FAILS) {
            log(ip + " 密码连续错误 " + n + " 次，封锁 2 小时（重启服务可解除）");
        } else {
            log(ip + " 密码错误（" + n + "/" + MAX_FAILS + "）");
        }
    }

    private static String remain(long ms) {
        long m = (ms + 59_999) / 60_000;
        return m >= 60 ? (m / 60) + " 小时 " + (m % 60) + " 分钟" : m + " 分钟";
    }

    private boolean login(Req r, OutputStream out) throws Exception {
        if (r.contentLength > 4096) {
            throw new HttpError(413, "too large");
        }
        byte[] b = new byte[(int) r.contentLength];
        int off = 0;
        while (off < b.length) {
            int n = r.body.read(b, off, b.length - off);
            if (n < 0) {
                break;
            }
            off += n;
        }
        r.contentLength = 0;
        if (locked(r.ip)) {
            long[] f = fails.get(r.ip);
            throw new HttpError(429, "密码错误次数过多，已封锁，请 "
                    + remain(LOCK_MS - (System.currentTimeMillis() - f[1])) + " 后再试");
        }
        String pw = new String(b, 0, off, StandardCharsets.UTF_8);
        if (!config.checkPassword(pw)) {
            fail(r.ip);
            long[] f = fails.get(r.ip);
            long left = MAX_FAILS - f[0];
            throw new HttpError(left > 0 ? 403 : 429, left > 0
                    ? "密码错误，还可尝试 " + left + " 次"
                    : "密码错误次数过多，已封锁 2 小时");
        }
        fails.remove(r.ip);
        byte[] rnd = new byte[24];
        random.nextBytes(rnd);
        String sid = android.util.Base64.encodeToString(rnd,
                android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
        sessions.put(sid, System.currentTimeMillis() + SESSION_TTL);
        log(r.ip + " 登录成功");
        return sendJson(out, 200, new JSONObject().put("ok", true),
                "hs_sid=" + sid + "; Path=/; Max-Age=" + (SESSION_TTL / 1000)
                        + "; HttpOnly; SameSite=Strict" + (ssl != null ? "; Secure" : ""));
    }

    private static String cookie(Req r, String name) {
        String c = r.h("cookie");
        if (c == null) {
            return null;
        }
        for (String part : c.split(";")) {
            String t = part.trim();
            if (t.startsWith(name + "=")) {
                return t.substring(name.length() + 1);
            }
        }
        return null;
    }

    // ================================================================== 路径

    private static final class Target {
        FileBackend fs;
        Share share;
        /** 共享内相对路径，无首尾 / */
        String rel;
        String abs;
    }

    static boolean validName(String n) {
        return n != null && !n.isEmpty() && !n.equals(".") && !n.equals("..")
                && n.indexOf('/') < 0 && n.indexOf('\0') < 0 && n.length() <= 255;
    }

    /** "/序号/a/b" → 绝对路径；拒绝 . 与 .. 段 */
    private Target resolve(String p) throws Exception {
        List<String> segs = new ArrayList<>();
        for (String s : p.split("/")) {
            if (s.isEmpty()) {
                continue;
            }
            if (!validName(s)) {
                throw new HttpError(400, "非法路径");
            }
            segs.add(s);
        }
        if (segs.isEmpty()) {
            throw new HttpError(404, "未指定共享");
        }
        List<Share> shares = config.shares();
        int idx;
        try {
            idx = Integer.parseInt(segs.get(0));
        } catch (NumberFormatException e) {
            throw new HttpError(404, "共享不存在");
        }
        if (idx < 0 || idx >= shares.size()) {
            throw new HttpError(404, "共享不存在");
        }
        Target t = new Target();
        t.share = shares.get(idx);
        t.fs = t.share.root ? root : local;
        t.rel = String.join("/", segs.subList(1, segs.size()));
        String base = t.share.path.endsWith("/") && t.share.path.length() > 1
                ? t.share.path.substring(0, t.share.path.length() - 1) : t.share.path;
        t.abs = t.rel.isEmpty() ? base : (base.equals("/") ? "/" : base + "/") + t.rel;
        // 普通模式禁止通过符号链接跳出共享目录；root 模式本身就是完全访问，不做限制
        if (!t.share.root && !t.rel.isEmpty()) {
            File f = new File(t.abs);
            String canonBase = new File(base).getCanonicalPath();
            File probe = f.exists() ? f : f.getParentFile();
            if (probe != null && probe.exists()) {
                String c = probe.getCanonicalPath();
                if (!c.equals(canonBase) && !c.startsWith(canonBase.endsWith("/") ? canonBase : canonBase + "/")) {
                    throw new HttpError(403, "路径越界");
                }
            }
        }
        return t;
    }

    // ================================================================== 目录 / 下载 / 上传

    private boolean listDir(OutputStream out, Target t) throws Exception {
        FileBackend.Entry st = t.fs.stat(t.abs);
        if (st == null) {
            throw new HttpError(404, "不存在");
        }
        if (!st.dir) {
            throw new HttpError(400, "不是目录");
        }
        JSONArray a = new JSONArray();
        for (FileBackend.Entry e : t.fs.list(t.abs)) {
            a.put(new JSONObject().put("n", e.name).put("d", e.dir).put("s", e.size).put("t", e.mtime));
        }
        return sendJson(out, 200, new JSONObject()
                .put("share", t.share.name).put("root", t.share.root).put("entries", a), null);
    }

    private boolean download(Req r, OutputStream out, Target t) throws Exception {
        FileBackend.Entry st = t.fs.stat(t.abs);
        if (st == null) {
            throw new HttpError(404, "不存在");
        }
        if (st.dir) {
            throw new HttpError(400, "是目录");
        }
        long size = st.size;
        long start = 0;
        long end = size - 1;
        boolean partial = false;
        String range = r.h("range");
        if (range != null && range.startsWith("bytes=") && !range.contains(",") && size > 0) {
            String spec = range.substring(6).trim();
            int dash = spec.indexOf('-');
            try {
                if (dash == 0) {
                    long n = Long.parseLong(spec.substring(1));
                    start = Math.max(0, size - n);
                } else if (dash > 0) {
                    start = Long.parseLong(spec.substring(0, dash));
                    if (dash < spec.length() - 1) {
                        end = Math.min(size - 1, Long.parseLong(spec.substring(dash + 1)));
                    }
                }
                if (start > end || start >= size) {
                    StringBuilder h = new StringBuilder();
                    h.append("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */")
                            .append(size).append("\r\nContent-Length: 0\r\n\r\n");
                    out.write(h.toString().getBytes(StandardCharsets.UTF_8));
                    return true;
                }
                partial = true;
            } catch (NumberFormatException ignored) {
                start = 0;
                end = size - 1;
            }
        }
        long len = size == 0 ? 0 : end - start + 1;
        String name = t.rel.isEmpty() ? "file" : t.rel.substring(t.rel.lastIndexOf('/') + 1);
        StringBuilder h = new StringBuilder();
        h.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        h.append("Content-Type: ").append(Mime.of(name)).append("\r\n");
        h.append("Content-Length: ").append(len).append("\r\n");
        h.append("Accept-Ranges: bytes\r\n");
        h.append("Last-Modified: ").append(httpDate(st.mtime)).append("\r\n");
        h.append("X-Content-Type-Options: nosniff\r\n");
        // 预览 HTML/SVG 时禁止执行脚本，避免被共享文件反打本站会话
        h.append("Content-Security-Policy: sandbox\r\n");
        if (partial) {
            h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(size).append("\r\n");
        }
        String enc = URLEncoder.encode(name, "UTF-8").replace("+", "%20");
        h.append("Content-Disposition: ").append(r.q("dl") != null ? "attachment" : "inline")
                .append("; filename*=UTF-8''").append(enc).append("\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
        if (!r.method.equals("HEAD") && len > 0) {
            try (InputStream in = t.fs.open(t.abs, start)) {
                Streams.copy(in, out, len);
            }
            log(r.ip + " 下载 " + t.abs + (partial ? " [" + start + "-" + end + "]" : ""));
        }
        return true;
    }

    private boolean upload(Req r, OutputStream out, Target t) throws Exception {
        if (t.rel.isEmpty()) {
            throw new HttpError(400, "无效文件名");
        }
        if (r.h("content-length") == null) {
            throw new HttpError(411, "需要 Content-Length");
        }
        FileBackend.Entry st = t.fs.stat(t.abs);
        if (st != null && st.dir) {
            throw new HttpError(409, "同名目录已存在");
        }
        if (st != null && !"1".equals(r.q("overwrite"))) {
            throw new HttpError(409, "文件已存在");
        }
        if (st != null) {
            requireModify(); // 覆盖等同于修改已有文件
        }
        long len = r.contentLength;
        r.contentLength = 0; // 由 write 负责读取
        t.fs.write(t.abs, r.body, len);
        log(r.ip + " 上传 " + t.abs + " (" + len + " B)");
        return ok(out);
    }

    // ================================================================== 响应

    private static String httpDate(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f.format(new Date(ms));
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 429: return "Too Many Requests";
            default: return "Error";
        }
    }

    private boolean sendJson(OutputStream out, int code, JSONObject o, String setCookie) throws IOException {
        return send(out, code, "application/json; charset=utf-8",
                o.toString().getBytes(StandardCharsets.UTF_8), false, setCookie);
    }

    private boolean send(OutputStream out, int code, String type, byte[] body, boolean head,
                         String setCookie) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        h.append("Cache-Control: no-store\r\n");
        h.append("X-Content-Type-Options: nosniff\r\n");
        h.append("X-Frame-Options: DENY\r\n");
        h.append("Referrer-Policy: no-referrer\r\n");
        if (setCookie != null) {
            h.append("Set-Cookie: ").append(setCookie).append("\r\n");
        }
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
        if (!head) {
            out.write(body);
        }
        return true;
    }
}
