package io.github.jiemo9527.httpshare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import io.github.jiemo9527.httpshare.server.HttpServer;
import io.github.jiemo9527.httpshare.server.Tls;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLContext;

public class ShareService extends Service {

    public static final String ACTION_STOP = "io.github.jiemo9527.httpshare.STOP";
    private static final String CHANNEL = "share";

    /** 界面读取的全局状态（同进程） */
    public static volatile boolean running;
    public static volatile String error;
    public static volatile String scheme = "http";
    public static volatile int port;
    private static final LinkedList<String> LOG = new LinkedList<>();
    public static volatile Runnable onChange;
    public static final int MAX_LOG = 3000;
    /** 同步查阅：服务运行期间可用；App 浏览页调用 publish */
    public static volatile io.github.jiemo9527.httpshare.server.ShowMe showme;
    public static volatile HttpServer current;
    /** FTP 端口；0 表示未开启 */
    public static volatile int ftpPort;
    public static volatile String ftpError;

    private HttpServer server;
    private io.github.jiemo9527.httpshare.server.FtpServer ftp;
    private Remote remote;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private final Handler main = new Handler(Looper.getMainLooper());

    /**
     * 日志持久化到 files/log.txt（最早的在前，每行一条）：App 被杀、服务重启、手机重启后都保留；
     * 超过 MAX_LOG 行时只丢弃最早的，除非用户点「清空」不会主动清除。
     */
    private static java.io.File logFile;
    private static boolean logLoaded;
    private static int logAppends;
    private static final java.util.concurrent.ExecutorService LOG_IO =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "HttpShare-log");
                t.setDaemon(true);
                return t;
            });

    /** Application/Activity/Service 启动时调用一次 */
    public static void initLog(android.content.Context c) {
        synchronized (LOG) {
            if (logFile != null) {
                return;
            }
            logFile = new java.io.File(c.getApplicationContext().getFilesDir(), "log.txt");
        }
        LOG_IO.execute(ShareService::loadLog);
    }

    private static void loadLog() {
        List<String> old = new ArrayList<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(logFile), java.nio.charset.StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (!l.isEmpty()) {
                    old.add(l);
                }
            }
        } catch (java.io.IOException ignored) {
        }
        synchronized (LOG) {
            // 文件里最早的在前；内存里最新的在前，且可能已有本次启动后新写的几行（它们比文件里的都新）
            for (int i = old.size() - 1; i >= 0; i--) {
                LOG.add(old.get(i));
            }
            while (LOG.size() > MAX_LOG) {
                LOG.removeLast();
            }
            logLoaded = true;
            if (old.size() > MAX_LOG + 500) {
                rewriteLogLocked();
            }
        }
        notifyChange();
    }

    /** 调用方持有 LOG 锁 */
    private static void rewriteLogLocked() {
        if (logFile == null) {
            return;
        }
        final List<String> snap = new ArrayList<>(LOG);
        LOG_IO.execute(() -> {
            java.io.File tmp = new java.io.File(logFile.getPath() + ".tmp");
            try (java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(tmp),
                    java.nio.charset.StandardCharsets.UTF_8)) {
                for (int i = snap.size() - 1; i >= 0; i--) {
                    w.write(snap.get(i));
                    w.write('\n');
                }
            } catch (java.io.IOException e) {
                tmp.delete();
                return;
            }
            tmp.renameTo(logFile);
        });
    }

    public static void log(String s) {
        String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + "  "
                + s.replace('\n', ' ').replace('\r', ' ');
        synchronized (LOG) {
            LOG.addFirst(line);
            while (LOG.size() > MAX_LOG) {
                LOG.removeLast();
            }
            if (logFile != null) {
                // 追加写入；文件超过上限较多时整体重写一次，只保留最新 MAX_LOG 行
                if (logLoaded && ++logAppends >= 500) {
                    logAppends = 0;
                    rewriteLogLocked();
                } else {
                    final java.io.File f = logFile;
                    LOG_IO.execute(() -> {
                        try (java.io.Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(f, true),
                                java.nio.charset.StandardCharsets.UTF_8)) {
                            w.write(line);
                            w.write('\n');
                        } catch (java.io.IOException ignored) {
                        }
                    });
                }
            }
        }
        notifyChange();
    }

    public static List<String> logs() {
        synchronized (LOG) {
            return new ArrayList<>(LOG);
        }
    }

    /** 仅在用户点「清空」时调用 */
    public static void clearLogs() {
        synchronized (LOG) {
            LOG.clear();
            logAppends = 0;
            rewriteLogLocked();
        }
        notifyChange();
    }

    private static void notifyChange() {
        Runnable r = onChange;
        if (r != null) {
            new Handler(Looper.getMainLooper()).post(r);
        }
    }

    /** FTP 只接受局域网/回环连接。CF 隧道只转发 HTTP，FTP 不提供公网暴露。 */
    private static boolean ftpAllowed(Prefs prefs, InetAddress peer) {
        String h = peer.getHostAddress();
        return peer.isLoopbackAddress() || isPrivate(h);
    }

    /** HTTPS 证书要覆盖的地址：所有非蜂窝网卡上的 IPv4 与非临时 IPv6（证书根带名称约束，公网地址会被忽略） */
    private static List<String> allAddresses() {
        List<String> out = new ArrayList<>(addresses());
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String n = ni.getName();
                if (!ni.isUp() || ni.isLoopback() || n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("dummy")) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof java.net.Inet6Address && (a.isLinkLocalAddress() || a.isSiteLocalAddress()
                            || (a.getAddress()[0] & 0xFE) == 0xFC)) {
                        out.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        List<String> priv = new ArrayList<>();
        for (String h : out) {
            if (isPrivate(h)) {
                priv.add(h);
            }
        }
        return priv;
    }

    /** 与根证书的名称约束一致，只把内网地址放进证书 */
    private static boolean isPrivate(String h) {
        try {
            byte[] b = InetAddress.getByName(h.contains("%") ? h.substring(0, h.indexOf('%')) : h).getAddress();
            if (b.length == 4) {
                int a0 = b[0] & 0xFF;
                int a1 = b[1] & 0xFF;
                return a0 == 10 || (a0 == 172 && a1 >= 16 && a1 < 32) || (a0 == 192 && a1 == 168)
                        || (a0 == 100 && a1 >= 64 && a1 < 128) || a0 == 127 || (a0 == 169 && a1 == 254);
            }
            return (b[0] & 0xFE) == 0xFC || ((b[0] & 0xFF) == 0xFE && (b[1] & 0xC0) == 0x80);
        } catch (Exception e) {
            return false;
        }
    }

    /** 局域网 IPv4 地址（排除回环与蜂窝常见接口优先 wlan/ap） */
    public static List<String> addresses() {
        List<String> wlan = new ArrayList<>();
        List<String> other = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String n = ni.getName();
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) {
                        continue;
                    }
                    String ip = a.getHostAddress();
                    if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan")
                            || n.startsWith("eth") || n.startsWith("rndis") || n.startsWith("usb")) {
                        wlan.add(ip);
                    } else if (!n.startsWith("rmnet") && !n.startsWith("ccmni") && !n.startsWith("dummy")) {
                        other.add(ip);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        wlan.addAll(other);
        return wlan;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundCompat("正在启动…");
        if (server != null) {
            return START_NOT_STICKY;
        }
        initLog(this);
        final Prefs prefs = new Prefs(this);
        final byte[] page = readAsset("web.html");
        final byte[] showmePage = readAsset("showme.html");
        final byte[] mountBat = readAsset("mount.bat");
        new Thread(() -> {
            try {
                SSLContext ssl = prefs.https() ? Tls.context(getFilesDir(), allAddresses()) : null;
                HttpServer.Config cfg = new HttpServer.Config() {
                    @Override
                    public List<HttpServer.Share> shares() {
                        List<HttpServer.Share> out = new ArrayList<>();
                        for (Prefs.Share s : prefs.shares()) {
                            out.add(new HttpServer.Share(s.name, s.path, s.root));
                        }
                        return out;
                    }

                    @Override
                    public boolean allowUpload() {
                        return prefs.allowUpload();
                    }

                    @Override
                    public boolean allowModify() {
                        return prefs.allowModify();
                    }

                    @Override
                    public boolean hasPassword() {
                        return prefs.hasPassword();
                    }

                    @Override
                    public boolean checkPassword(String pw) {
                        return prefs.checkPassword(pw);
                    }

                    @Override
                    public byte[] webPage() {
                        return page;
                    }

                    @Override
                    public byte[] showmePage() {
                        return showmePage;
                    }

                    @Override
                    public boolean webdav() {
                        return prefs.webdav();
                    }

                    @Override
                    public byte[] mountBat() {
                        return mountBat;
                    }

                    @Override
                    public byte[] caCert() {
                        if (!prefs.https()) {
                            return null;
                        }
                        try {
                            return Tls.caCertificate(getFilesDir()).getEncoded();
                        } catch (Exception e) {
                            return null;
                        }
                    }
                };
                HttpServer s = new HttpServer(cfg, prefs.port(), ssl, ShareService::log);
                s.start();
                io.github.jiemo9527.httpshare.server.FtpServer f = null;
                String fErr = null;
                if (prefs.ftp()) {
                    try {
                        f = new io.github.jiemo9527.httpshare.server.FtpServer(s,
                                io.github.jiemo9527.httpshare.server.FtpServer.PORT, peer -> ftpAllowed(prefs, peer));
                        f.start();
                    } catch (Exception e) {
                        f = null;
                        fErr = "FTP 启动失败：" + e.getMessage();
                    }
                }
                final io.github.jiemo9527.httpshare.server.FtpServer fs = f;
                final String ftpErr = fErr;
                main.post(() -> {
                    server = s;
                    current = s;
                    ftp = fs;
                    ftpPort = fs != null ? fs.port() : 0;
                    ftpError = ftpErr;
                    if (ftpErr != null) {
                        log(ftpErr);
                    } else if (fs != null) {
                        log("FTP 已启动 端口 " + fs.port() + "（被动端口 "
                                + io.github.jiemo9527.httpshare.server.FtpServer.PASV_FROM + "-"
                                + io.github.jiemo9527.httpshare.server.FtpServer.PASV_TO + "，明文，仅限局域网）");
                    }
                    showme = s.showme();
                    running = true;
                    error = null;
                    scheme = ssl != null ? "https" : "http";
                    port = prefs.port();
                    acquireLocks();
                    List<String> ips = addresses();
                    String url = scheme + "://" + (ips.isEmpty() ? "127.0.0.1" : ips.get(0)) + ":" + port;
                    startForegroundCompat(url);
                    log("服务已启动 " + url + (prefs.hasPassword() ? "（已设密码）" : "（无密码）"));
                    startRemote(prefs);
                });
            } catch (Exception e) {
                main.post(() -> {
                    error = "启动失败：" + e.getMessage();
                    log(error);
                    stopSelf();
                });
            }
        }, "HttpShare-start").start();
        return START_NOT_STICKY;
    }

    public static java.io.File tunnelBinary(android.content.Context c) {
        return new java.io.File(c.getApplicationInfo().nativeLibraryDir, "libcloudflared.so");
    }

    private void startRemote(Prefs prefs) {
        int mode = prefs.remoteMode();
        if (mode == Remote.MODE_OFF) {
            return;
        }
        if (!prefs.hasPassword()) {
            log("未设置访问密码，已拒绝开启外网访问");
            Remote.state = "未设置访问密码，外网访问未开启";
            notifyChange();
            return;
        }
        remote = new Remote(tunnelBinary(this), getFilesDir(), prefs.port(), prefs.https(), mode,
                new Remote.Listener() {
                    @Override
                    public void onState() {
                        notifyChange();
                        main.post(() -> {
                            if (server != null) {
                                String u = Remote.url;
                                startForegroundCompat(u != null ? u : Remote.state);
                            }
                        });
                    }

                    @Override
                    public void onLog(String line) {
                        log(line);
                    }
                });
        remote.start();
    }

    private void acquireLocks() {
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HttpShare:server");
            wake.acquire();
            WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HttpShare");
            wifi.acquire();
        } catch (Exception ignored) {
        }
    }

    private void startForegroundCompat(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "共享服务", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ShareService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_share)
                .setContentTitle("HTTP 共享运行中")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止", stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, n);
        }
    }

    private byte[] readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
            }
            return b.toByteArray();
        } catch (Exception e) {
            return ("<h1>" + e + "</h1>").getBytes();
        }
    }

    @Override
    public void onDestroy() {
        if (remote != null) {
            remote.stop();
            remote = null;
        }
        Remote.state = "";
        Remote.url = null;
        if (ftp != null) {
            ftp.stop();
            ftp = null;
        }
        ftpPort = 0;
        ftpError = null;
        if (server != null) {
            showme = null;
            current = null;
            server.stop();
            server = null;
            log("服务已停止");
        }
        running = false;
        try {
            if (wake != null && wake.isHeld()) {
                wake.release();
            }
            if (wifi != null && wifi.isHeld()) {
                wifi.release();
            }
        } catch (Exception ignored) {
        }
        notifyChange();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
