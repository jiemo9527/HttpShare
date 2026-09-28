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
 * GET  /api/zip?p=/0/dir      文件夹打包下载（流式 ZIP）
 * *    /dav/...               WebDAV（仅 HTTP Basic 认证，见 {@link #dav}）
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

        /** 是否在 /dav/ 提供 WebDAV */
        boolean webdav();
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
        /** Transfer-Encoding: chunked；未被处理的 chunked 请求体无法跳过，只能断开连接 */
        boolean chunked;
        boolean consumed;

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
                if (r.chunked && !r.consumed) {
                    break;
                }
                // 带 Expect: 100-continue 却被直接拒绝的请求，客户端可能不会发送请求体，不能再等着读
                if (r.contentLength > 0 && "100-continue".equalsIgnoreCase(r.h("expect"))) {
                    break;
                }
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
        r.chunked = "chunked".equalsIgnoreCase(r.h("transfer-encoding"));
        if (r.chunked) {
            r.contentLength = 0;
        }
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

        if (path.equals("/dav") || path.startsWith("/dav/")) {
            return dav(r, out);
        }
        if (m.equals("OPTIONS")) {
            return options(out);
        }

        if (path.equals("/api/info")) {
            return sendJson(out, 200, new JSONObject()
                    .put("auth", config.hasPassword())
                    .put("loggedIn", authed(r))
                    .put("upload", config.allowUpload())
                    .put("modify", config.allowModify())
                    .put("https", ssl != null)
                    .put("webdav", config.webdav()), null);
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
            case "/api/zip":
                return zip(r, out, resolve(param(r, "p")));
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
        return basicAuth(r);
    }

    /** HTTP Basic（用户名任意），失败计入封锁 */
    private boolean basicAuth(Req r) {
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
        if (r.h("content-length") == null && !r.chunked) {
            throw new HttpError(411, "需要 Content-Length");
        }
        FileBackend.Entry st = t.fs.stat(t.abs);
        if (st != null && st.dir) {
            throw new HttpError(409, "同名目录已存在");
        }
        if (st != null && !"1".equals(r.q("overwrite"))) {
            throw new HttpError(409, "文件已存在");
        }
        if (st != null && st.size > 0) {
            requireModify(); // 覆盖等同于修改已有文件（0 字节的空文件除外）
        }
        long len = receive(r, out, t);
        log(r.ip + " 上传 " + t.abs + " (" + len + " B)");
        return ok(out);
    }

    /** 读取请求体写入 t（支持 chunked 与 Expect: 100-continue），返回字节数 */
    private long receive(Req r, OutputStream out, Target t) throws IOException, HttpError {
        String parent = t.abs.substring(0, Math.max(1, t.abs.lastIndexOf('/')));
        FileBackend.Entry ps = t.fs.stat(parent);
        if (ps == null || !ps.dir) {
            throw new HttpError(409, "上级目录不存在");
        }
        if ("100-continue".equalsIgnoreCase(r.h("expect"))) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
        if (r.chunked) {
            CountingIn in = new CountingIn(new ChunkedInputStream(r.body));
            t.fs.write(t.abs, in, -1);
            r.consumed = true;
            return in.count;
        }
        long len = r.contentLength;
        r.contentLength = 0; // 由 write 负责读取
        t.fs.write(t.abs, r.body, len);
        return len;
    }

    private static final class CountingIn extends java.io.FilterInputStream {
        long count;

        CountingIn(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }
    }

    /** 读取小请求体（PROPFIND/PROPPATCH/LOCK 的 XML） */
    private static byte[] readSmallBody(Req r) throws IOException, HttpError {
        InputStream in;
        long max = 256 * 1024;
        if (r.chunked) {
            in = new ChunkedInputStream(r.body);
            r.consumed = true;
        } else {
            if (r.contentLength > max) {
                throw new HttpError(413, "too large");
            }
            in = new java.io.FilterInputStream(r.body) {
                long left = r.contentLength;

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (left <= 0) {
                        return -1;
                    }
                    int n = super.read(b, off, (int) Math.min(len, left));
                    if (n > 0) {
                        left -= n;
                    }
                    return n;
                }
            };
            r.contentLength = 0;
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf, 0, buf.length)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > max) {
                throw new HttpError(413, "too large");
            }
        }
        return bo.toByteArray();
    }

    // ================================================================== 文件夹打包下载

    private boolean zip(Req r, OutputStream out, Target t) throws Exception {
        FileBackend.Entry st = t.fs.stat(t.abs);
        if (st == null) {
            throw new HttpError(404, "不存在");
        }
        if (!st.dir) {
            throw new HttpError(400, "不是目录");
        }
        String base = t.rel.isEmpty() ? t.share.name : t.rel.substring(t.rel.lastIndexOf('/') + 1);
        if (base.isEmpty() || base.equals("/")) {
            base = "share";
        }
        String enc = URLEncoder.encode(base + ".zip", "UTF-8").replace("+", "%20");
        String h = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/zip\r\n"
                + "Content-Disposition: attachment; filename*=UTF-8''" + enc + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.UTF_8));
        if (r.method.equals("HEAD")) {
            return false;
        }
        long t0 = System.currentTimeMillis();
        log(r.ip + " 打包下载 " + t.abs + " …");
        Zip z = new Zip(out);
        long[] stat = new long[3]; // 文件数、原始字节、跳过数
        try {
            zipWalk(t.fs, t.share.root, t.abs, base + "/", st.mtime, z, stat, 0);
            z.finish();
            out.flush();
        } catch (IOException e) {
            // 响应头已发出，无法再返回错误；直接断开，浏览器会显示下载失败
            log(r.ip + " 打包中断 " + t.abs + "：" + e.getMessage());
            throw new SocketException("zip aborted");
        }
        log(r.ip + " 打包完成 " + t.abs + "：" + stat[0] + " 个文件，" + stat[1] / 1024 / 1024 + " MB"
                + (stat[2] > 0 ? "，跳过 " + stat[2] + " 项" : "") + "，用时 "
                + (System.currentTimeMillis() - t0) / 1000 + " 秒");
        return false; // 无 Content-Length，靠关闭连接结束
    }

    private void zipWalk(FileBackend fs, boolean rootShare, String dir, String prefix, long mtime, Zip z, long[] stat,
                         int depth) throws IOException {
        List<FileBackend.Entry> list;
        try {
            list = fs.list(dir);
        } catch (IOException e) {
            stat[2]++;
            return;
        }
        z.dir(prefix, mtime);
        if (depth > 64) {
            return;
        }
        for (FileBackend.Entry e : list) {
            String child = dir.endsWith("/") ? dir + e.name : dir + "/" + e.name;
            // 跳过设备/管道等；普通共享不跟随任何符号链接（防止越出共享目录），ROOT 共享不进入链接目录（防止循环）
            if (e.special || (e.link && (!rootShare || e.dir))) {
                stat[2]++;
                continue;
            }
            if (e.dir) {
                zipWalk(fs, rootShare, child, prefix + e.name + "/", e.mtime, z, stat, depth + 1);
                continue;
            }
            InputStream in;
            try {
                in = fs.open(child, 0);
            } catch (IOException ex) {
                stat[2]++;
                continue;
            }
            try {
                stat[1] += z.file(prefix + e.name, e.mtime, e.size, in);
                stat[0]++;
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // ================================================================== WebDAV

    private static final String DAV_ALLOW =
            "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, LOCK, UNLOCK";

    private boolean options(OutputStream out) throws IOException {
        return send(out, 200, "text/plain; charset=utf-8", new byte[0], false, null,
                "DAV: 1, 2\r\nMS-Author-Via: DAV\r\nAllow: " + DAV_ALLOW + "\r\n");
    }

    /**
     * WebDAV：/dav/ 对应共享根目录，可在 Windows 资源管理器、macOS Finder、RaiDrive、
     * 手机文件管理器等挂载为网络盘，直接打开/保存文件。
     * 只接受 HTTP Basic（不认网页 Cookie），因此跨站页面无法借用登录态，不需要 X-HS 头。
     * 权限与网页一致：新建/上传需「上传」，删除/移动/覆盖非空文件需「改名/删除」。
     */
    private boolean dav(Req r, OutputStream out) throws Exception {
        String m = r.method;
        if (!config.webdav()) {
            throw new HttpError(404, "WebDAV 未开启");
        }
        if (m.equals("OPTIONS")) {
            return options(out);
        }
        if (config.hasPassword() && !basicAuth(r)) {
            if (locked(r.ip)) {
                throw new HttpError(403, "密码错误次数过多，已封锁");
            }
            return send(out, 401, "text/plain; charset=utf-8", "需要密码".getBytes(StandardCharsets.UTF_8),
                    m.equals("HEAD"), null, "WWW-Authenticate: Basic realm=\"HttpShare\", charset=\"UTF-8\"\r\n");
        }
        String rel = r.path.length() > 4 ? r.path.substring(4) : "";
        Target t = resolve("/0" + rel);
        switch (m) {
            case "GET":
            case "HEAD": {
                FileBackend.Entry st = t.fs.stat(t.abs);
                if (st == null) {
                    throw new HttpError(404, "不存在");
                }
                if (st.dir) {
                    byte[] b = ("<!doctype html><meta charset=utf-8><title>WebDAV</title>"
                            + "<p>这是 HTTP 共享的 WebDAV 地址，请在文件管理器中挂载为网络位置。网页浏览请打开 <a href=\"/\">首页</a>。")
                            .getBytes(StandardCharsets.UTF_8);
                    return send(out, 200, "text/html; charset=utf-8", b, m.equals("HEAD"), null);
                }
                return download(r, out, t);
            }
            case "PROPFIND":
                return propfind(r, out, t, rel);
            case "PROPPATCH":
                return proppatch(r, out, rel);
            case "PUT": {
                requireUpload();
                if (t.rel.isEmpty()) {
                    throw new HttpError(405, "不能写入共享根目录");
                }
                FileBackend.Entry st = t.fs.stat(t.abs);
                if (st != null && st.dir) {
                    throw new HttpError(405, "同名目录已存在");
                }
                if (st != null && st.size > 0) {
                    requireModify();
                }
                long len = receive(r, out, t);
                log(r.ip + " [WebDAV] 上传 " + t.abs + " (" + len + " B)");
                return send(out, st == null ? 201 : 204, "text/plain", new byte[0], false, null);
            }
            case "MKCOL": {
                requireUpload();
                // 经 Cloudflare 隧道时空请求体会被改成 chunked 传输，读完后再判断是否真的有内容
                if (r.contentLength > 0 || r.chunked) {
                    if (readSmallBody(r).length > 0) {
                        throw new HttpError(415, "MKCOL 不支持请求体");
                    }
                }
                if (t.rel.isEmpty() || t.fs.stat(t.abs) != null) {
                    throw new HttpError(405, "已存在");
                }
                String parent = t.abs.substring(0, Math.max(1, t.abs.lastIndexOf('/')));
                FileBackend.Entry ps = t.fs.stat(parent);
                if (ps == null || !ps.dir) {
                    throw new HttpError(409, "上级目录不存在");
                }
                t.fs.mkdir(t.abs);
                log(r.ip + " [WebDAV] 新建目录 " + t.abs);
                return send(out, 201, "text/plain", new byte[0], false, null);
            }
            case "DELETE": {
                requireModify();
                if (t.rel.isEmpty()) {
                    throw new HttpError(403, "不能删除共享根目录");
                }
                if (t.fs.stat(t.abs) == null) {
                    throw new HttpError(404, "不存在");
                }
                t.fs.delete(t.abs);
                log(r.ip + " [WebDAV] 删除 " + t.abs);
                return send(out, 204, "text/plain", new byte[0], false, null);
            }
            case "MOVE":
            case "COPY":
                return moveCopy(r, out, t, m.equals("MOVE"));
            case "LOCK":
                return lock(r, out, t, rel);
            case "UNLOCK":
                return send(out, 204, "text/plain", new byte[0], false, null);
            default:
                return send(out, 405, "text/plain", new byte[0], false, null, "Allow: " + DAV_ALLOW + "\r\n");
        }
    }

    private static String hrefOf(String rel, boolean dir) {
        StringBuilder b = new StringBuilder("/dav");
        for (String s : rel.split("/")) {
            if (s.isEmpty()) {
                continue;
            }
            try {
                b.append('/').append(URLEncoder.encode(s, "UTF-8").replace("+", "%20"));
            } catch (Exception e) {
                b.append('/').append(s);
            }
        }
        if (dir || b.length() == 4) {
            b.append('/');
        }
        return b.toString();
    }

    private static String xml(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                default:
                    if (c >= 0x20 || c == '\t') {
                        b.append(c);
                    }
            }
        }
        return b.toString();
    }

    private static final String XML_HEAD = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";

    private static void propEntry(StringBuilder x, String href, String name, FileBackend.Entry e) {
        x.append("<D:response><D:href>").append(xml(href)).append("</D:href><D:propstat><D:prop>");
        x.append("<D:displayname>").append(xml(name)).append("</D:displayname>");
        if (e.dir) {
            x.append("<D:resourcetype><D:collection/></D:resourcetype>");
        } else {
            x.append("<D:resourcetype/>");
            x.append("<D:getcontentlength>").append(e.size).append("</D:getcontentlength>");
            x.append("<D:getcontenttype>").append(xml(Mime.of(name))).append("</D:getcontenttype>");
        }
        x.append("<D:getlastmodified>").append(httpDate(e.mtime)).append("</D:getlastmodified>");
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("GMT"));
        x.append("<D:creationdate>").append(iso.format(new Date(e.mtime))).append("</D:creationdate>");
        x.append("<D:getetag>\"").append(Long.toHexString(e.size)).append('-').append(Long.toHexString(e.mtime))
                .append("\"</D:getetag>");
        x.append("<D:supportedlock><D:lockentry><D:lockscope><D:exclusive/></D:lockscope>"
                + "<D:locktype><D:write/></D:locktype></D:lockentry></D:supportedlock>");
        x.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>\n");
    }

    private boolean propfind(Req r, OutputStream out, Target t, String rel) throws Exception {
        readSmallBody(r); // 始终返回全部属性，请求体忽略
        FileBackend.Entry st = t.fs.stat(t.abs);
        if (st == null) {
            throw new HttpError(404, "不存在");
        }
        String depth = r.h("depth");
        StringBuilder x = new StringBuilder(XML_HEAD).append("<D:multistatus xmlns:D=\"DAV:\">\n");
        String name = t.rel.isEmpty() ? t.share.name : t.rel.substring(t.rel.lastIndexOf('/') + 1);
        propEntry(x, hrefOf(rel, st.dir), name, st);
        if (st.dir && !"0".equals(depth)) {
            String base = rel.endsWith("/") ? rel : rel + "/";
            for (FileBackend.Entry e : t.fs.list(t.abs)) {
                propEntry(x, hrefOf(base + e.name, e.dir), e.name, e);
            }
        }
        x.append("</D:multistatus>\n");
        return send(out, 207, "application/xml; charset=utf-8", x.toString().getBytes(StandardCharsets.UTF_8),
                false, null);
    }

    /** 不保存自定义属性，但对请求的每个属性回 200（Windows 会设置 Win32 时间属性，失败会报错） */
    private boolean proppatch(Req r, OutputStream out, String rel) throws Exception {
        byte[] body = readSmallBody(r);
        StringBuilder props = new StringBuilder();
        try {
            org.xmlpull.v1.XmlPullParser p = android.util.Xml.newPullParser();
            p.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
            p.setInput(new java.io.ByteArrayInputStream(body), "UTF-8");
            int propDepth = -1;
            int n = 0;
            for (int ev = p.getEventType(); ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    if ("DAV:".equals(p.getNamespace()) && "prop".equals(p.getName())) {
                        propDepth = p.getDepth();
                    } else if (propDepth > 0 && p.getDepth() == propDepth + 1) {
                        props.append("<x").append(n).append(':').append(p.getName()).append(" xmlns:x").append(n)
                                .append("=\"").append(xml(p.getNamespace())).append("\"/>");
                        n++;
                    }
                } else if (ev == org.xmlpull.v1.XmlPullParser.END_TAG && p.getDepth() == propDepth) {
                    propDepth = -1;
                }
            }
        } catch (Exception ignored) {
        }
        String x = XML_HEAD + "<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>" + xml(hrefOf(rel, false))
                + "</D:href><D:propstat><D:prop>" + props + "</D:prop><D:status>HTTP/1.1 200 OK</D:status>"
                + "</D:propstat></D:response></D:multistatus>\n";
        return send(out, 207, "application/xml; charset=utf-8", x.getBytes(StandardCharsets.UTF_8), false, null);
    }

    private boolean lock(Req r, OutputStream out, Target t, String rel) throws Exception {
        readSmallBody(r);
        if (!config.allowUpload() && !config.allowModify()) {
            throw new HttpError(403, "只读共享");
        }
        FileBackend.Entry st = t.fs.stat(t.abs);
        boolean created = false;
        if (st == null) {
            // 对不存在的路径加锁 = 创建空文件（RFC 4918 7.3）
            requireUpload();
            if (t.rel.isEmpty()) {
                throw new HttpError(409, "无效路径");
            }
            t.fs.write(t.abs, new java.io.ByteArrayInputStream(new byte[0]), 0);
            created = true;
        }
        byte[] rnd = new byte[16];
        random.nextBytes(rnd);
        StringBuilder hex = new StringBuilder();
        for (byte b : rnd) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }
        String token = "opaquelocktoken:" + hex;
        String x = XML_HEAD + "<D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock>"
                + "<D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope>"
                + "<D:depth>" + ("0".equals(r.h("depth")) ? "0" : "infinity") + "</D:depth>"
                + "<D:timeout>Second-3600</D:timeout>"
                + "<D:locktoken><D:href>" + token + "</D:href></D:locktoken>"
                + "<D:lockroot><D:href>" + xml(hrefOf(rel, st != null && st.dir)) + "</D:href></D:lockroot>"
                + "</D:activelock></D:lockdiscovery></D:prop>\n";
        return send(out, created ? 201 : 200, "application/xml; charset=utf-8", x.getBytes(StandardCharsets.UTF_8),
                false, null, "Lock-Token: <" + token + ">\r\n");
    }

    private boolean moveCopy(Req r, OutputStream out, Target t, boolean move) throws Exception {
        if (move) {
            requireModify();
        } else {
            requireUpload();
        }
        if (move && t.rel.isEmpty()) {
            throw new HttpError(403, "不能移动共享根目录");
        }
        String dest = r.h("destination");
        if (dest == null) {
            throw new HttpError(400, "缺少 Destination");
        }
        int s = dest.indexOf("://");
        if (s >= 0) {
            int p = dest.indexOf('/', s + 3);
            dest = p < 0 ? "/" : dest.substring(p);
        }
        int qi = dest.indexOf('?');
        if (qi >= 0) {
            dest = dest.substring(0, qi);
        }
        dest = decPath(dest);
        if (!dest.startsWith("/dav/")) {
            throw new HttpError(502, "目标不在本服务器");
        }
        Target d = resolve("/0" + dest.substring(4));
        if (d.rel.isEmpty()) {
            throw new HttpError(403, "无效目标");
        }
        FileBackend.Entry src = t.fs.stat(t.abs);
        if (src == null) {
            throw new HttpError(404, "不存在");
        }
        if (d.abs.equals(t.abs)) {
            throw new HttpError(403, "源与目标相同");
        }
        if (src.dir && (d.abs + "/").startsWith(t.abs + "/")) {
            throw new HttpError(409, "不能移动/复制到自身子目录");
        }
        String parent = d.abs.substring(0, Math.max(1, d.abs.lastIndexOf('/')));
        FileBackend.Entry ps = d.fs.stat(parent);
        if (ps == null || !ps.dir) {
            throw new HttpError(409, "目标上级目录不存在");
        }
        FileBackend.Entry existing = d.fs.stat(d.abs);
        if (existing != null) {
            if ("F".equalsIgnoreCase(r.h("overwrite"))) {
                throw new HttpError(412, "目标已存在");
            }
            requireModify();
            d.fs.delete(d.abs);
        }
        if (move) {
            t.fs.rename(t.abs, d.abs);
            log(r.ip + " [WebDAV] 移动 " + t.abs + " → " + d.abs);
        } else {
            copyRec(t.fs, t.abs, src, d.abs, 0);
            log(r.ip + " [WebDAV] 复制 " + t.abs + " → " + d.abs);
        }
        return send(out, existing == null ? 201 : 204, "text/plain", new byte[0], false, null);
    }

    private void copyRec(FileBackend fs, String from, FileBackend.Entry st, String to, int depth) throws IOException {
        if (depth > 64) {
            throw new IOException("目录层级过深");
        }
        if (st.dir) {
            fs.mkdir(to);
            for (FileBackend.Entry e : fs.list(from)) {
                if (e.special || e.link && e.dir) {
                    continue;
                }
                copyRec(fs, from + "/" + e.name, e, to + "/" + e.name, depth + 1);
            }
        } else {
            try (InputStream in = fs.open(from, 0)) {
                fs.write(to, in, -1);
            }
        }
    }

    // ================================================================== 响应

    private static String httpDate(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f.format(new Date(ms));
    }

    private static String reason(int code) {
        switch (code) {
            case 100: return "Continue";
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 207: return "Multi-Status";
            case 412: return "Precondition Failed";
            case 415: return "Unsupported Media Type";
            case 423: return "Locked";
            case 502: return "Bad Gateway";
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
        return send(out, code, type, body, head, setCookie, null);
    }

    private boolean send(OutputStream out, int code, String type, byte[] body, boolean head,
                         String setCookie, String extra) throws IOException {
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
        if (extra != null) {
            h.append(extra);
        }
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
        if (!head) {
            out.write(body);
        }
        return true;
    }
}
