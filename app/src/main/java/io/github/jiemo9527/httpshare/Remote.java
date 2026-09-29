package io.github.jiemo9527.httpshare;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 外网访问：
 * <ul>
 *   <li>自动模式会检测公网 IPv4；本地 CA 的名称约束不适合为公网 IP 提供可验证 TLS，
 *       所以网页仍启动 Cloudflare 临时隧道，FTP 公网直连也保持关闭。</li>
 *   <li>Cloudflare 临时隧道（trycloudflare.com）只承载网页 HTTP(S)，手机主动连出去，无需公网 IP。</li>
 * </ul>
 *
 * Android 上 Go 程序（cloudflared）用裸 syscall 建连，不经过 bionic 的 netd 标记，
 * 在蜂窝网络下会报 network unreachable、也找不到 DNS。因此 DNS 解析、申请隧道、
 * 以及到 Cloudflare 边缘的 TCP 连接全部由本进程（Java，走系统默认网络）完成：
 * cloudflared 只连 127.0.0.1 上的本地中继。
 */
public final class Remote {

    public static final int MODE_OFF = 0;
    public static final int MODE_AUTO = 1;
    public static final int MODE_TUNNEL = 2;

    public interface Listener {
        void onState();

        void onLog(String line);
    }

    /** 状态（同进程由界面读取） */
    public static volatile String state = "";
    public static volatile String url;
    public static volatile String publicV4;
    public static volatile List<String> ipv6 = new ArrayList<>();

    private static final String UA = "cloudflared/2026.9.3";
    private static final String[] EDGE_HOSTS = {"region1.v2.argotunnel.com", "region2.v2.argotunnel.com"};

    private final File bin;
    private final File dir;
    private final int port;
    private final boolean https;
    private final int mode;
    private final Listener listener;
    private final List<ServerSocket> relays = new ArrayList<>();
    private volatile Process proc;
    private volatile boolean running;
    private Thread worker;

    public Remote(File bin, File dir, int port, boolean https, int mode, Listener l) {
        this.bin = bin;
        this.dir = dir;
        this.port = port;
        this.https = https;
        this.mode = mode;
        this.listener = l;
    }

    public static boolean binaryAvailable(File bin) {
        return bin.isFile() && bin.canExecute();
    }

    public void start() {
        publicV4 = null;
        ipv6 = new ArrayList<>();
        running = true;
        worker = new Thread(this::loop, "HttpShare-remote");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        running = false;
        Process p = proc;
        if (p != null) {
            p.destroy();
        }
        closeRelays();
        if (worker != null) {
            worker.interrupt();
        }
        url = null;
        state = "";
        publicV4 = null;
        ipv6 = new ArrayList<>();
        notifyState();
    }

    // ================================================================== 主循环

    private void loop() {
        int backoff = 5;
        while (running) {
            try {
                if (mode == MODE_AUTO) {
                    set("检测公网 IPv4…", null);
                    String v4 = detectPublicV4();
                    publicV4 = v4;
                    ipv6 = globalV6();
                    if (v4 != null) {
                        // The bundled CA deliberately constrains its names to private networks.  Do not
                        // advertise a public-IP TLS endpoint that compliant clients must reject.
                        log("检测到公网 IPv4 " + v4 + "；网页改用 Cloudflare 隧道，FTP 不支持公网直连");
                    }
                    if (v4 == null) {
                        log("无公网 IPv4，改用 Cloudflare 隧道");
                    }
                } else {
                    ipv6 = globalV6();
                }
                runTunnel();
                backoff = 5;
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                log("外网访问失败：" + e.getMessage());
                set("失败，" + backoff + " 秒后重试：" + e.getMessage(), null);
            } finally {
                closeRelays();
            }
            if (!running) {
                return;
            }
            try {
                Thread.sleep(backoff * 1000L);
            } catch (InterruptedException e) {
                return;
            }
            backoff = Math.min(backoff * 2, 120);
        }
    }

    private void runTunnel() throws Exception {
        if (!binaryAvailable(bin)) {
            throw new IOException("本机架构不支持隧道（缺少 cloudflared）");
        }
        set("申请 Cloudflare 隧道…", null);
        JSONObject r = null;
        IOException last = null;
        for (int i = 0; i < 3 && r == null; i++) {
            try {
                r = requestQuickTunnel();
            } catch (IOException e) {
                last = e;
                Thread.sleep(2000);
            }
        }
        if (r == null) {
            throw last != null ? last : new IOException("申请隧道失败");
        }
        String id = r.getString("id");
        String host = r.getString("hostname");
        File cred = new File(dir, "cf_cred.json");
        try (OutputStream o = new FileOutputStream(cred)) {
            o.write(new JSONObject()
                    .put("AccountTag", r.getString("account_tag"))
                    .put("TunnelSecret", r.getString("secret"))
                    .put("TunnelID", id)
                    .toString().getBytes(StandardCharsets.UTF_8));
        }

        List<InetAddress> edges = resolveEdges();
        if (edges.isEmpty()) {
            throw new IOException("无法解析 Cloudflare 边缘节点");
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(bin.getAbsolutePath());
        cmd.add("tunnel");
        cmd.add("--no-autoupdate");
        cmd.add("--protocol");
        cmd.add("http2");
        cmd.add("--metrics");
        cmd.add("127.0.0.1:0");
        for (InetAddress a : edges) {
            cmd.add("--edge");
            cmd.add("127.0.0.1:" + startRelay(a));
        }
        cmd.add("--credentials-file");
        cmd.add(cred.getAbsolutePath());
        cmd.add("--url");
        cmd.add((https ? "https" : "http") + "://127.0.0.1:" + port);
        if (https) {
            cmd.add("--no-tls-verify");
        }
        cmd.add("run");
        cmd.add(id);

        set("连接 Cloudflare…", null);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true);
        pb.environment().put("HOME", dir.getAbsolutePath());
        proc = pb.start();
        String publicUrl = "https://" + host;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int registered = 0;
            while ((line = br.readLine()) != null) {
                if (line.contains("Registered tunnel connection")) {
                    registered++;
                    if (registered == 1) {
                        set("Cloudflare 隧道已连接", publicUrl);
                        log("隧道已连接 " + publicUrl);
                    }
                } else if (line.contains(" ERR ") && !isNoise(line)) {
                    log("cloudflared: " + trim(line));
                }
            }
        }
        int code = proc.waitFor();
        proc = null;
        if (running) {
            throw new IOException("cloudflared 退出(" + code + ")");
        }
    }

    /** DNS 类报错是预期内的（已由 Java 解析），不刷屏 */
    private static boolean isNoise(String l) {
        return l.contains("lookup ") || l.contains("1.1.1.1") || l.contains("dig srv")
                || l.contains("compressed SRV") || l.contains("golang") || l.contains("Please try the following")
                || l.contains("equivalent of");
    }

    private static String trim(String l) {
        int i = l.indexOf(" ERR ");
        String s = i >= 0 ? l.substring(i + 5) : l;
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    private static JSONObject requestQuickTunnel() throws Exception {
        String host = "api.trycloudflare.com";
        List<InetAddress> ips = resolve(host);
        if (ips.isEmpty()) {
            throw new IOException("无法解析 " + host);
        }
        IOException last = null;
        for (InetAddress ip : ips) {
            if (!(ip instanceof Inet4Address)) {
                continue;
            }
            try {
                return quickTunnelVia(host, ip);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("申请隧道失败");
    }

    /** 手写一次 HTTPS POST：连到已解析的 IP，SNI/证书校验仍按域名，避免依赖系统 DNS */
    private static JSONObject quickTunnelVia(String host, InetAddress ip) throws Exception {
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(ip, 443), 10_000);
        raw.setSoTimeout(15_000);
        javax.net.ssl.SSLSocket s = (javax.net.ssl.SSLSocket)
                ((javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault())
                        .createSocket(raw, host, 443, true);
        try {
            javax.net.ssl.SSLParameters p = s.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            s.setSSLParameters(p);
            s.startHandshake();
            OutputStream o = s.getOutputStream();
            o.write(("POST /tunnel HTTP/1.1\r\nHost: " + host + "\r\nUser-Agent: " + UA
                    + "\r\nContent-Type: application/json\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            o.flush();
            String resp = read(s.getInputStream());
            int sp = resp.indexOf(' ');
            int code = sp > 0 ? Integer.parseInt(resp.substring(sp + 1, sp + 4)) : 0;
            int body = resp.indexOf("\n\n");
            String json = body >= 0 ? resp.substring(body + 2).trim() : "";
            // 可能是 chunked：取第一个 { 到最后一个 }
            int a = json.indexOf('{');
            int b = json.lastIndexOf('}');
            if (code != 200 || a < 0 || b < a) {
                throw new IOException("trycloudflare HTTP " + code);
            }
            JSONObject j = new JSONObject(json.substring(a, b + 1));
            if (!j.optBoolean("success")) {
                throw new IOException("trycloudflare 拒绝：" + j.optString("errors"));
            }
            return j.getJSONObject("result");
        } finally {
            s.close();
        }
    }

    private static List<InetAddress> resolveEdges() {
        List<InetAddress> out = new ArrayList<>();
        for (String h : EDGE_HOSTS) {
            try {
                for (InetAddress a : resolve(h)) {
                    if (a instanceof Inet4Address && out.size() < 4 && !out.contains(a)) {
                        out.add(a);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /**
     * 系统 DNS 优先；部分运营商 DNS 不解析 Cloudflare 域名（实测联通流量下 unknown host），
     * 失败时用 IP 直连的 DoH（阿里 223.5.5.5 / 腾讯 1.12.12.12）兜底。
     */
    static List<InetAddress> resolve(String host) {
        List<InetAddress> out = new ArrayList<>();
        try {
            Collections.addAll(out, InetAddress.getAllByName(host));
            if (!out.isEmpty()) {
                return out;
            }
        } catch (Exception ignored) {
        }
        for (String doh : new String[]{"https://223.5.5.5/resolve?type=A&name=", "https://1.12.12.12/dns-query?type=A&name="}) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(doh + host).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                c.setRequestProperty("accept", "application/dns-json");
                org.json.JSONArray ans = new JSONObject(read(c.getInputStream())).optJSONArray("Answer");
                if (ans == null) {
                    continue;
                }
                for (int i = 0; i < ans.length(); i++) {
                    JSONObject a = ans.getJSONObject(i);
                    if (a.optInt("type") == 1) {
                        out.add(InetAddress.getByName(a.getString("data")));
                    }
                }
                if (!out.isEmpty()) {
                    return out;
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    // ================================================================== 本地中继

    private int startRelay(InetAddress target) throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16);
        synchronized (relays) {
            relays.add(ss);
        }
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    Socket local = ss.accept();
                    new Thread(() -> bridge(local, target), "HttpShare-relay").start();
                } catch (IOException e) {
                    return;
                }
            }
        }, "HttpShare-relay-accept");
        t.setDaemon(true);
        t.start();
        return ss.getLocalPort();
    }

    private static void bridge(Socket local, InetAddress target) {
        Socket remote = new Socket();
        try {
            remote.connect(new InetSocketAddress(target, 7844), 10_000);
            remote.setKeepAlive(true);
            remote.setTcpNoDelay(true);
            local.setTcpNoDelay(true);
            Thread up = new Thread(() -> pipe(local, remote), "HttpShare-relay-up");
            up.setDaemon(true);
            up.start();
            pipe(remote, local);
        } catch (IOException ignored) {
        } finally {
            close(local);
            close(remote);
        }
    }

    private static void pipe(Socket from, Socket to) {
        byte[] buf = new byte[32 * 1024];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (IOException ignored) {
        } finally {
            close(from);
            close(to);
        }
    }

    private void closeRelays() {
        synchronized (relays) {
            for (ServerSocket s : relays) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
            relays.clear();
        }
    }

    private static void close(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    // ================================================================== 公网检测

    static boolean isPrivateV4(byte[] b) {
        int a0 = b[0] & 0xFF;
        int a1 = b[1] & 0xFF;
        return a0 == 10 || a0 == 127 || a0 == 0
                || (a0 == 172 && a1 >= 16 && a1 <= 31)
                || (a0 == 192 && a1 == 168)
                || (a0 == 100 && a1 >= 64 && a1 <= 127)   // CGNAT
                || (a0 == 169 && a1 == 254)
                || (a0 == 198 && (a1 == 18 || a1 == 19))  // 基准测试/代理 fake-ip
                || a0 >= 224;
    }

    /** 网卡上的公网 IPv4，且与出口 IP 相同（无 NAT）才算可直连 */
    static String detectPublicV4() {
        Set<String> local = new LinkedHashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.getName().startsWith("tun")) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !isPrivateV4(a.getAddress())) {
                        local.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (local.isEmpty()) {
            return null;
        }
        for (String u : new String[]{"https://4.ipw.cn", "https://api.ipify.org"}) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                String ip = read(c.getInputStream()).trim();
                if (local.contains(ip)) {
                    return ip;
                }
                if (!ip.isEmpty()) {
                    return null; // 出口 IP 与网卡不同：有 NAT
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    static List<String> globalV6() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.getName().startsWith("tun")) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet6Address && !a.isLinkLocalAddress() && !a.isSiteLocalAddress()) {
                        byte b0 = a.getAddress()[0];
                        if ((b0 & 0xE0) == 0x20) { // 2000::/3 全球单播
                            String s = a.getHostAddress();
                            int pct = s.indexOf('%');
                            out.add(pct > 0 ? s.substring(0, pct) : s);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // ================================================================== 工具

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String l;
            while ((l = br.readLine()) != null) {
                sb.append(l).append('\n');
            }
        }
        return sb.toString();
    }

    private void set(String s, String u) {
        state = s;
        url = u;
        notifyState();
    }

    private void notifyState() {
        if (listener != null) {
            listener.onState();
        }
    }

    private void log(String s) {
        if (listener != null) {
            listener.onLog(s);
        }
    }
}
