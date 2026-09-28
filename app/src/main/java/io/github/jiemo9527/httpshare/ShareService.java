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

    private HttpServer server;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private final Handler main = new Handler(Looper.getMainLooper());

    public static void log(String s) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + s;
        synchronized (LOG) {
            LOG.addFirst(line);
            while (LOG.size() > 300) {
                LOG.removeLast();
            }
        }
        notifyChange();
    }

    public static List<String> logs() {
        synchronized (LOG) {
            return new ArrayList<>(LOG);
        }
    }

    public static void clearLogs() {
        synchronized (LOG) {
            LOG.clear();
        }
        notifyChange();
    }

    private static void notifyChange() {
        Runnable r = onChange;
        if (r != null) {
            new Handler(Looper.getMainLooper()).post(r);
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
        final Prefs prefs = new Prefs(this);
        final byte[] page = readAsset("web.html");
        new Thread(() -> {
            try {
                SSLContext ssl = prefs.https() ? Tls.context(getFilesDir()) : null;
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
                    public boolean allowWrite() {
                        return prefs.allowWrite();
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
                };
                HttpServer s = new HttpServer(cfg, prefs.port(), ssl, ShareService::log);
                s.start();
                main.post(() -> {
                    server = s;
                    running = true;
                    error = null;
                    scheme = ssl != null ? "https" : "http";
                    port = prefs.port();
                    acquireLocks();
                    List<String> ips = addresses();
                    String url = scheme + "://" + (ips.isEmpty() ? "127.0.0.1" : ips.get(0)) + ":" + port;
                    startForegroundCompat(url);
                    log("服务已启动 " + url + (prefs.hasPassword() ? "（已设密码）" : "（无密码）"));
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
        if (server != null) {
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
