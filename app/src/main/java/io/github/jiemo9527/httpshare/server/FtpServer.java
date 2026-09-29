package io.github.jiemo9527.httpshare.server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 明文 FTP 服务器（仅被动模式）。
 *
 * <ul>
 *   <li>控制和数据连接都是明文；只应在受信任的局域网中使用。</li>
 *   <li>根目录 = 当前共享目录；路径解析、越界检查、读写权限、密码错误封锁都复用 {@link HttpServer}。</li>
 *   <li>被动端口在固定范围内挑选；只接受与控制连接同一 IP 的数据连接（防止端口被抢占 / FXP）。</li>
 *   <li>用户名任意，密码为访问密码；未设置访问密码时任意密码都可登录（与网页端一致）。</li>
 * </ul>
 */
public final class FtpServer {
    public static final int PORT = 2121;
    public static final int PASV_FROM = 50000;
    public static final int PASV_TO = 50100;
    private static final int MAX_CLIENTS = 16;
    private static final int IDLE_MS = 10 * 60 * 1000;
    private static final int DATA_ACCEPT_MS = 30_000;

    public interface Access {
        /** 是否接受来自该地址的连接。 */
        boolean allow(InetAddress peer);
    }

    private final HttpServer http;
    private final Access access;
    private final int port;
    private final Set<Socket> clients = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Session> sessions = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<ServerSocket> pasvListeners = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Socket> dataSockets = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Integer> pasvBusy = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final AtomicInteger nextPasv = new AtomicInteger(PASV_FROM);
    private ServerSocket server;
    private volatile boolean running;

    public FtpServer(HttpServer http, int port, Access access) {
        this.http = http;
        this.port = port;
        this.access = access;
    }

    public int port() {
        return port;
    }

    public void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port), 16);
        running = true;
        Thread t = new Thread(this::acceptLoop, "HttpShare-ftp");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
        }
        for (Socket s : clients) {
            close(s);
        }
        for (Session s : sessions) {
            s.closeResources();
        }
        for (ServerSocket s : pasvListeners) {
            closeQuiet(s);
        }
        for (Socket s : dataSockets) {
            close(s);
        }
        sessions.clear();
        pasvListeners.clear();
        dataSockets.clear();
        clients.clear();
    }

    private void acceptLoop() {
        while (running) {
            Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                if (!running) {
                    return;
                }
                continue;
            }
            if (!access.allow(s.getInetAddress())) {
                http.logLine(s.getInetAddress().getHostAddress() + " [FTP] 拒绝：不在允许的局域网地址范围");
                close(s);
                continue;
            }
            if (clients.size() >= MAX_CLIENTS) {
                http.logLine(s.getInetAddress().getHostAddress() + " [FTP] 拒绝：连接数已达上限");
                close(s);
                continue;
            }
            clients.add(s);
            Thread t = new Thread(() -> {
                Session session = null;
                try {
                    session = new Session(s);
                    sessions.add(session);
                    session.run();
                } catch (Exception ignored) {
                } finally {
                    if (session != null) {
                        sessions.remove(session);
                        session.closeResources();
                    }
                    clients.remove(s);
                    close(s);
                }
            }, "HttpShare-ftp-client");
            t.setDaemon(true);
            t.start();
        }
    }

    private static void close(Socket s) {
        try {
            if (s != null) {
                try {
                    s.shutdownInput();
                } catch (IOException | UnsupportedOperationException ignored) {
                }
                try {
                    s.shutdownOutput();
                } catch (IOException | UnsupportedOperationException ignored) {
                }
                s.close();
            }
        } catch (IOException ignored) {
        }
    }

    private static void closeQuiet(ServerSocket s) {
        try {
            if (s != null) {
                s.close();
            }
        } catch (IOException ignored) {
        }
    }

    /** 一个控制连接 */
    private final class Session {
        private Socket sock;
        private InputStream in;
        private OutputStream out;
        private final String ip;
        private boolean loggedIn;
        private String user;
        private String cwd = "/";
        private long rest;
        private String renameFrom;
        private ServerSocket pasv;
        private int pasvPort;

        Session(Socket s) throws IOException {
            sock = s;
            ip = s.getInetAddress().getHostAddress();
            s.setSoTimeout(IDLE_MS);
            in = new BufferedInputStream(s.getInputStream());
            out = new BufferedOutputStream(s.getOutputStream());
        }

        void run() throws Exception {
            try {
                reply(220, "HttpShare FTP 就绪（明文传输，仅限受信任局域网）");
                String line;
                while ((line = readLine()) != null) {
                    int sp = line.indexOf(' ');
                    String cmd = (sp < 0 ? line : line.substring(0, sp)).toUpperCase(Locale.ROOT);
                    String arg = sp < 0 ? "" : line.substring(sp + 1);
                    if (!handle(cmd, arg)) {
                        return;
                    }
                }
            } catch (SocketTimeoutException e) {
                try {
                    reply(421, "空闲超时，连接关闭");
                } catch (IOException ignored) {
                }
            } finally {
                closeResources();
            }
        }

        private String readLine() throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) >= 0) {
                if (c == '\n') {
                    break;
                }
                if (c != '\r') {
                    if (b.size() >= 4096) {
                        throw new IOException("命令过长");
                    }
                    b.write(c);
                }
            }
            if (c < 0 && b.size() == 0) {
                return null;
            }
            return b.toString("UTF-8");
        }

        private void reply(int code, String msg) throws IOException {
            out.write((code + " " + msg.replace("\r", " ").replace("\n", " ") + "\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private void multi(int code, List<String> lines, String last) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append(code).append('-').append(lines.isEmpty() ? "" : lines.get(0)).append("\r\n");
            for (int i = 1; i < lines.size(); i++) {
                sb.append(' ').append(lines.get(i)).append("\r\n");
            }
            sb.append(code).append(' ').append(last).append("\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        /** @return false 表示结束会话 */
        private boolean handle(String cmd, String arg) throws Exception {
            switch (cmd) {
                case "AUTH":
                    reply(502, "不支持 TLS：这是明文 FTP 服务");
                    return true;
                case "FEAT":
                    multi(211, java.util.Arrays.asList("功能：", "UTF8", "EPSV",
                            "PASV", "SIZE", "MDTM", "REST STREAM", "MLST type*;size*;modify*;"), "End");
                    return true;
                case "OPTS":
                    reply(arg.toUpperCase(Locale.ROOT).startsWith("UTF8") ? 200 : 501,
                            arg.toUpperCase(Locale.ROOT).startsWith("UTF8") ? "始终使用 UTF-8" : "不支持的选项");
                    return true;
                case "SYST":
                    reply(215, "UNIX Type: L8");
                    return true;
                case "NOOP":
                case "CLNT":
                    reply(200, "OK");
                    return true;
                case "QUIT":
                    reply(221, "再见");
                    return false;
                case "HELP":
                    reply(214, "见 FEAT");
                    return true;
                case "PBSZ":
                case "PROT":
                    reply(502, "不支持 TLS 数据保护：这是明文 FTP 服务");
                    return true;
                default:
                    break;
            }
            switch (cmd) {
                case "USER":
                    user = arg;
                    loggedIn = false;
                    reply(331, "请输入访问密码（用户名任意）");
                    return true;
                case "PASS":
                    return pass(arg);
                default:
                    break;
            }
            if (!loggedIn) {
                reply(530, "请先登录");
                return true;
            }
            try {
                return command(cmd, arg);
            } catch (HttpServer.HttpError e) {
                reply(e.code == 403 ? 550 : e.code == 404 ? 550 : 553, e.getMessage());
            } catch (IOException e) {
                reply(550, e.getMessage() == null ? "操作失败" : e.getMessage());
            }
            return true;
        }

        private boolean pass(String pw) throws IOException {
            if (user == null) {
                reply(503, "先发送 USER");
                return true;
            }
            if (http.locked(ip)) {
                reply(530, "密码错误次数过多，已封锁 2 小时");
                return false;
            }
            HttpServer.Config c = http.config();
            if (c.hasPassword() && !c.checkPassword(pw)) {
                http.fail(ip, pw);
                if (http.locked(ip)) {
                    reply(530, "密码错误次数过多，已封锁 2 小时");
                    return false;
                }
                reply(530, "密码错误");
                return true;
            }
            http.loginOk(ip);
            loggedIn = true;
            http.logLine(ip + " [FTP] 登录成功");
            reply(230, "登录成功");
            return true;
        }

        private boolean command(String cmd, String arg) throws Exception {
            HttpServer.Config c = http.config();
            switch (cmd) {
                case "PWD":
                case "XPWD":
                    reply(257, "\"" + cwd.replace("\"", "\"\"") + "\" 是当前目录");
                    return true;
                case "CWD":
                case "XCWD": {
                    String p = path(arg);
                    HttpServer.Target t = target(p);
                    FileBackend.Entry st = t.fs.stat(t.abs);
                    if (st == null || !st.dir) {
                        reply(550, "目录不存在");
                    } else {
                        cwd = p;
                        reply(250, "OK");
                    }
                    return true;
                }
                case "CDUP":
                case "XCUP":
                    cwd = path("..");
                    reply(250, "OK");
                    return true;
                case "TYPE":
                    reply(200, "TYPE " + arg);
                    return true;
                case "MODE":
                    reply(arg.equalsIgnoreCase("S") ? 200 : 504, "MODE S");
                    return true;
                case "STRU":
                    reply(arg.equalsIgnoreCase("F") ? 200 : 504, "STRU F");
                    return true;
                case "ALLO":
                    reply(202, "不需要");
                    return true;
                case "REST":
                    try {
                        rest = Math.max(0, Long.parseLong(arg.trim()));
                        reply(350, "从 " + rest + " 字节继续");
                    } catch (NumberFormatException e) {
                        reply(501, "参数错误");
                    }
                    return true;
                case "PASV":
                    return pasv(false);
                case "EPSV":
                    if (arg.equalsIgnoreCase("ALL")) {
                        reply(200, "EPSV ALL");
                        return true;
                    }
                    return pasv(true);
                case "PORT":
                case "EPRT":
                    reply(502, "只支持被动模式（PASV/EPSV）");
                    return true;
                case "SIZE": {
                    HttpServer.Target t = target(path(arg));
                    FileBackend.Entry st = t.fs.stat(t.abs);
                    if (st == null || st.dir) {
                        reply(550, "文件不存在");
                    } else {
                        reply(213, String.valueOf(st.size));
                    }
                    return true;
                }
                case "MDTM": {
                    HttpServer.Target t = target(path(arg));
                    FileBackend.Entry st = t.fs.stat(t.abs);
                    if (st == null) {
                        reply(550, "不存在");
                    } else {
                        reply(213, stamp(st.mtime));
                    }
                    return true;
                }
                case "MLST": {
                    String p = path(arg);
                    HttpServer.Target t = target(p);
                    FileBackend.Entry st = t.fs.stat(t.abs);
                    if (st == null) {
                        reply(550, "不存在");
                    } else {
                        multi(250, java.util.Arrays.asList("详情：", facts(st) + " " + p), "End");
                    }
                    return true;
                }
                case "LIST":
                case "NLST":
                case "MLSD":
                    return list(cmd, arg);
                case "RETR":
                    return retr(arg);
                case "STOR":
                    return stor(arg);
                case "APPE":
                case "STOU":
                    reply(502, "不支持，请使用 STOR");
                    return true;
                case "MKD":
                case "XMKD": {
                    if (!c.allowUpload()) {
                        reply(550, "未开启「上传 / 新建」权限");
                        return true;
                    }
                    String p = path(arg);
                    HttpServer.Target t = target(p);
                    if (t.rel.isEmpty() || t.fs.stat(t.abs) != null) {
                        reply(550, "已存在");
                        return true;
                    }
                    t.fs.mkdir(t.abs);
                    http.logLine(ip + " [FTP] 新建目录 " + t.abs);
                    reply(257, "\"" + p.replace("\"", "\"\"") + "\" 已创建");
                    return true;
                }
                case "DELE":
                case "RMD":
                case "XRMD": {
                    if (!c.allowModify()) {
                        reply(550, "未开启「改名 / 删除」权限");
                        return true;
                    }
                    HttpServer.Target t = target(path(arg));
                    if (t.rel.isEmpty()) {
                        reply(550, "不能删除共享根目录");
                        return true;
                    }
                    FileBackend.Entry st = t.fs.stat(t.abs);
                    if (st == null) {
                        reply(550, "不存在");
                        return true;
                    }
                    if (cmd.equals("DELE") == st.dir) {
                        reply(550, st.dir ? "这是目录，请用 RMD" : "这是文件，请用 DELE");
                        return true;
                    }
                    t.fs.delete(t.abs);
                    http.logLine(ip + " [FTP] 删除 " + t.abs);
                    reply(250, "已删除");
                    return true;
                }
                case "RNFR": {
                    if (!c.allowModify()) {
                        reply(550, "未开启「改名 / 删除」权限");
                        return true;
                    }
                    String p = path(arg);
                    HttpServer.Target t = target(p);
                    if (t.rel.isEmpty() || t.fs.stat(t.abs) == null) {
                        reply(550, "不存在");
                        return true;
                    }
                    renameFrom = p;
                    reply(350, "请发送 RNTO");
                    return true;
                }
                case "RNTO": {
                    if (renameFrom == null) {
                        reply(503, "先发送 RNFR");
                        return true;
                    }
                    if (!c.allowModify()) {
                        renameFrom = null;
                        reply(550, "未开启「改名 / 删除」权限");
                        return true;
                    }
                    HttpServer.Target from = target(renameFrom);
                    renameFrom = null;
                    HttpServer.Target to = target(path(arg));
                    // Shares and files can change while the client waits between RNFR and RNTO.
                    if (from.rel.isEmpty() || from.fs.stat(from.abs) == null) {
                        reply(550, "源文件已不存在");
                        return true;
                    }
                    if (to.rel.isEmpty() || to.fs.stat(to.abs) != null || from.fs != to.fs) {
                        reply(553, "目标已存在");
                        return true;
                    }
                    from.fs.rename(from.abs, to.abs);
                    http.logLine(ip + " [FTP] 重命名 " + from.abs + " → " + to.abs);
                    reply(250, "已重命名");
                    return true;
                }
                case "STAT":
                    reply(211, "HttpShare FTP，已登录");
                    return true;
                case "ABOR":
                    closePasv();
                    reply(226, "已中止");
                    return true;
                default:
                    reply(502, "不支持的命令 " + cmd);
                    return true;
            }
        }

        // ------------------------------------------------------------ 路径

        /** 把 FTP 路径（相对 cwd 或绝对）规范成以 / 开头、不含 . 和 .. 的虚拟路径 */
        private String path(String arg) {
            String a = arg == null ? "" : arg;
            Deque<String> st = new ArrayDeque<>();
            if (!a.startsWith("/")) {
                for (String s : cwd.split("/")) {
                    if (!s.isEmpty()) {
                        st.addLast(s);
                    }
                }
            }
            for (String s : a.split("/")) {
                if (s.isEmpty() || s.equals(".")) {
                    continue;
                }
                if (s.equals("..")) {
                    if (!st.isEmpty()) {
                        st.removeLast();
                    }
                    continue;
                }
                st.addLast(s);
            }
            return "/" + String.join("/", st);
        }

        private HttpServer.Target target(String vpath) throws Exception {
            return http.resolve("/0" + (vpath.equals("/") ? "" : vpath));
        }

        // ------------------------------------------------------------ 数据连接

        private boolean pasv(boolean extended) throws IOException {
            closePasv();
            InetAddress local = sock.getLocalAddress();
            if (!extended && !(local instanceof Inet4Address)) {
                reply(522, "IPv6 连接请使用 EPSV");
                return true;
            }
            ServerSocket ss = null;
            int n = PASV_TO - PASV_FROM + 1;
            for (int i = 0; i < n && ss == null; i++) {
                int p = PASV_FROM + Math.floorMod(nextPasv.getAndIncrement() - PASV_FROM, n);
                if (!pasvBusy.add(p)) {
                    continue;
                }
                try {
                    ServerSocket s = new ServerSocket();
                    s.setReuseAddress(true);
                    s.bind(new InetSocketAddress(local, p), 1);
                    s.setSoTimeout(DATA_ACCEPT_MS);
                    ss = s;
                    pasvPort = p;
                } catch (IOException e) {
                    pasvBusy.remove(p);
                }
            }
            if (ss == null) {
                reply(425, "被动端口 " + PASV_FROM + "-" + PASV_TO + " 都被占用");
                return true;
            }
            pasv = ss;
            pasvListeners.add(ss);
            if (extended) {
                reply(229, "进入扩展被动模式 (|||" + pasvPort + "|)");
            } else {
                byte[] a = local.getAddress();
                reply(227, "进入被动模式 (" + (a[0] & 0xFF) + "," + (a[1] & 0xFF) + "," + (a[2] & 0xFF) + ","
                        + (a[3] & 0xFF) + "," + (pasvPort >> 8) + "," + (pasvPort & 0xFF) + ")");
            }
            return true;
        }

        private void releasePasv() {
            if (pasvPort != 0) {
                pasvBusy.remove(pasvPort);
                pasvPort = 0;
            }
        }

        private void closePasv() {
            ServerSocket p = pasv;
            pasv = null;
            closeQuiet(p);
            if (p != null) {
                pasvListeners.remove(p);
            }
            releasePasv();
        }

        /** Called by server stop and session teardown to terminate pending and active transfers. */
        private void closeResources() {
            closePasv();
            close(sock);
        }

        private void closeData(Socket s) {
            dataSockets.remove(s);
            close(s);
        }

        /** 等待控制连接同一地址的客户端连上被动端口；失败时已回复错误并返回 null。 */
        private Socket openData() throws IOException {
            if (pasv == null) {
                reply(425, "请先发送 PASV 或 EPSV");
                return null;
            }
            ServerSocket ss = pasv;
            pasv = null;
            pasvListeners.remove(ss);
            Socket raw = null;
            try {
                long deadline = System.currentTimeMillis() + DATA_ACCEPT_MS;
                while (raw == null) {
                    Socket s = ss.accept();
                    // 只接受与控制连接同一 IP 的数据连接
                    if (s.getInetAddress().equals(sock.getInetAddress())) {
                        raw = s;
                        dataSockets.add(raw);
                    } else {
                        close(s);
                        if (System.currentTimeMillis() > deadline) {
                            throw new SocketTimeoutException();
                        }
                    }
                }
            } catch (IOException e) {
                reply(425, "数据连接失败：" + e.getMessage());
                return null;
            } finally {
                closeQuiet(ss);
                releasePasv();
            }
            return raw;
        }

        private boolean list(String cmd, String arg) throws Exception {
            String a = arg;
            // 忽略 ls 风格参数（-la 等）
            while (a.startsWith("-")) {
                int sp = a.indexOf(' ');
                a = sp < 0 ? "" : a.substring(sp + 1);
            }
            HttpServer.Target t = target(path(a));
            FileBackend.Entry st = t.fs.stat(t.abs);
            if (st == null) {
                closePasv();
                reply(550, "不存在");
                return true;
            }
            List<FileBackend.Entry> items = st.dir ? t.fs.list(t.abs)
                    : Collections.singletonList(new FileBackend.Entry(
                    t.rel.substring(t.rel.lastIndexOf('/') + 1), false, st.size, st.mtime));
            reply(150, "目录列表");
            Socket d = openData();
            if (d == null) {
                return true;
            }
            try (OutputStream o = new BufferedOutputStream(d.getOutputStream())) {
                StringBuilder sb = new StringBuilder();
                for (FileBackend.Entry e : items) {
                    if (cmd.equals("NLST")) {
                        sb.append(e.name);
                    } else if (cmd.equals("MLSD")) {
                        sb.append(facts(e)).append(' ').append(e.name);
                    } else {
                        sb.append(e.dir ? 'd' : e.link ? 'l' : '-').append(e.dir ? "rwxr-xr-x" : "rw-r--r--")
                                .append(" 1 owner group ").append(String.format(Locale.ROOT, "%13d", e.dir ? 0 : e.size))
                                .append(' ').append(lsDate(e.mtime)).append(' ').append(e.name);
                    }
                    sb.append("\r\n");
                    if (sb.length() > 32 * 1024) {
                        o.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                        sb.setLength(0);
                    }
                }
                o.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                o.flush();
            } catch (IOException e) {
                reply(426, "传输中断");
                return true;
            } finally {
                closeData(d);
            }
            reply(226, "完成，共 " + items.size() + " 项");
            return true;
        }

        private boolean retr(String arg) throws Exception {
            HttpServer.Target t = target(path(arg));
            FileBackend.Entry st = t.fs.stat(t.abs);
            long off = rest;
            rest = 0;
            if (st == null || st.dir) {
                closePasv();
                reply(550, "文件不存在");
                return true;
            }
            if (off > st.size) {
                reply(554, "续传位置超过文件大小");
                return true;
            }
            reply(150, "开始传输 " + (st.size - off) + " 字节");
            Socket d = openData();
            if (d == null) {
                return true;
            }
            long n;
            try (InputStream fin = t.fs.open(t.abs, off); OutputStream o = d.getOutputStream()) {
                n = Streams.copy(fin, o, -1);
                o.flush();
            } catch (IOException e) {
                reply(426, "传输中断：" + e.getMessage());
                return true;
            } finally {
                closeData(d);
            }
            http.logLine(ip + " [FTP] 下载 " + t.abs + (off > 0 ? " [从 " + off + "]" : "") + "（" + n + " B）");
            reply(226, "传输完成");
            return true;
        }

        private boolean stor(String arg) throws Exception {
            HttpServer.Config c = http.config();
            HttpServer.Target t = target(path(arg));
            long off = rest;
            rest = 0;
            String err = null;
            if (!c.allowUpload()) {
                err = "未开启「上传 / 新建」权限";
            } else if (t.rel.isEmpty()) {
                err = "文件名无效";
            } else if (off > 0) {
                err = "不支持续传上传，请重新上传整个文件";
            } else {
                FileBackend.Entry st = t.fs.stat(t.abs);
                if (st != null && st.dir) {
                    err = "同名目录已存在";
                } else if (st != null && !c.allowModify()) {
                    err = "文件已存在，覆盖需要「改名 / 删除」权限";
                }
            }
            if (err != null) {
                closePasv();
                reply(553, err);
                return true;
            }
            reply(150, "开始接收");
            Socket d = openData();
            if (d == null) {
                return true;
            }
            CountingInputStream cin = null;
            try (InputStream din = d.getInputStream()) {
                cin = new CountingInputStream(din);
                t.fs.write(t.abs, cin, -1);
            } catch (IOException e) {
                reply(426, "传输中断：" + e.getMessage());
                return true;
            } finally {
                closeData(d);
            }
            http.logLine(ip + " [FTP] 上传 " + t.abs + "（" + cin.count + " B）");
            reply(226, "上传完成");
            return true;
        }
    }

    private static final class CountingInputStream extends java.io.FilterInputStream {
        long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int c = super.read();
            if (c >= 0) {
                count++;
            }
            return c;
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

    // ------------------------------------------------------------ 格式

    private static String facts(FileBackend.Entry e) {
        return "type=" + (e.dir ? "dir" : "file") + ";" + (e.dir ? "" : "size=" + e.size + ";")
                + "modify=" + stamp(e.mtime) + ";";
    }

    private static String stamp(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    private static String lsDate(long ms) {
        boolean recent = Math.abs(System.currentTimeMillis() - ms) < 180L * 86_400_000L;
        SimpleDateFormat f = new SimpleDateFormat(recent ? "MMM dd HH:mm" : "MMM dd  yyyy", Locale.US);
        return f.format(new Date(ms));
    }
}
