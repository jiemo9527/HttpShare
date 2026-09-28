package io.github.jiemo9527.httpshare;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import io.github.jiemo9527.httpshare.server.RootBackend;
import io.github.jiemo9527.httpshare.server.Tls;

import java.io.File;
import java.util.List;

public class MainActivity extends Activity {

    private static final int ACCENT = 0xFF0F8A6B;
    private static final int RED = 0xFFC62828;
    private static final int ORANGE = 0xFFE65100;
    private static final int LINE = 0x33888888;
    private static final String LAUNCHER_ALIAS = "io.github.jiemo9527.httpshare.Launcher";

    private static final int TAB_HOME = 0;
    private static final int TAB_BROWSE = 1;
    private static final int TAB_LOG = 2;
    private static final int TAB_SETTINGS = 3;
    private static final int TAB_COUNT = 4;

    private Prefs prefs;
    private int currentTab;
    private final View[] pages = new View[TAB_COUNT];
    private final TextView[] tabs = new TextView[TAB_COUNT];
    private final TextView[] modeChips = new TextView[3];

    // 浏览页（同步查阅）
    private int browseShare = 0;
    private String browseRel = "";
    private int browseToken;
    private ScrollView browseScroll;
    private TextView browsePath;
    private TextView browseSync;
    private LinearLayout browseList;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable syncTicker = new Runnable() {
        @Override
        public void run() {
            refreshSyncInfo();
            ui.postDelayed(this, 2000);
        }
    };

    private TextView moduleView;
    private TextView stateView;
    private TextView toggleBtn;
    private LinearLayout urlBox;
    private final TextView[] typeChips = new TextView[2];
    private TextView sharePathView;
    private TextView shareHint;
    private TextView permView;
    private TextView logView;
    private Switch hideIconSwitch;
    private TextView passwordState;
    private TextView passwordValue;
    private TextView pwEye;
    private View passwordShowRow;
    private boolean pwVisible;

    // ================================================================== 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        ShareService.initLog(this);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        buildUi();
        restoreIconOnce();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 旋转 / 分屏 / 深色模式切换：重建界面以适配宽度与配色
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ShareService.onChange = this::refresh;
        refresh();
        if (currentTab == TAB_BROWSE) {
            loadBrowse();
        }
    }

    @Override
    protected void onPause() {
        ShareService.onChange = null;
        ui.removeCallbacks(syncTicker);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (currentTab == TAB_BROWSE && !browseRel.isEmpty()) {
            browseUp();
            return;
        }
        super.onBackPressed();
    }

    private void buildUi() {
        LinearLayout outer = vertical();
        outer.setFitsSystemWindows(true);

        LinearLayout header = vertical();
        header.setPadding(dp(16), dp(12), dp(16), dp(6));
        LinearLayout titleRow = horizontal();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(32), dp(32));
        llp.setMarginEnd(dp(10));
        titleRow.addView(logo, llp);
        TextView title = text(getString(R.string.app_name), 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(title);
        header.addView(titleRow);
        moduleView = text("", 12);
        moduleView.setPadding(0, dp(4), 0, 0);
        header.addView(moduleView);
        outer.addView(header);

        FrameLayout container = new FrameLayout(this);
        pages[TAB_HOME] = buildHomePage();
        pages[TAB_BROWSE] = buildBrowsePage();
        pages[TAB_LOG] = buildLogPage();
        pages[TAB_SETTINGS] = buildSettingsPage();
        for (View p : pages) {
            container.addView(p, new FrameLayout.LayoutParams(-1, -1));
        }
        outer.addView(container, new LinearLayout.LayoutParams(-1, 0, 1));

        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        outer.addView(divider, new LinearLayout.LayoutParams(-1, 1));
        LinearLayout nav = horizontal();
        String[] names = {"共享", "浏览", "日志", "设置"};
        for (int i = 0; i < TAB_COUNT; i++) {
            final int idx = i;
            TextView t = text(names[i], 15);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(12), 0, dp(12));
            t.setOnClickListener(v -> switchTab(idx));
            tabs[i] = t;
            nav.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
        outer.addView(nav);
        setContentView(outer);
        switchTab(currentTab);
        refresh();
    }

    private void switchTab(int idx) {
        boolean leftBrowse = currentTab == TAB_BROWSE && idx != TAB_BROWSE;
        currentTab = idx;
        for (int i = 0; i < TAB_COUNT; i++) {
            pages[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            tabs[i].setTextColor(i == idx ? ACCENT : fg());
            tabs[i].setTypeface(i == idx ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        if (idx == TAB_LOG) {
            refreshLog();
        }
        ui.removeCallbacks(syncTicker);
        if (idx == TAB_BROWSE) {
            loadBrowse();
            ui.post(syncTicker);
        } else if (leftBrowse) {
            publishShowme(null, null);
        }
    }

    /** 宽屏（平板/横屏）时内容限宽居中，窄屏铺满 */
    private ScrollView page(LinearLayout content) {
        ScrollView sv = new ScrollView(this);
        FrameLayout wrap = new FrameLayout(this);
        int maxW = dp(720);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.min(maxW, screenW), -2);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        content.setPadding(dp(16), dp(8), dp(16), dp(24));
        wrap.addView(content, lp);
        sv.addView(wrap);
        return sv;
    }

    // ================================================================== 共享页

    private View buildHomePage() {
        LinearLayout page = vertical();

        LinearLayout card = card();
        stateView = text("", 16);
        stateView.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(stateView);
        urlBox = vertical();
        card.addView(urlBox);

        TextView nl = text("网络方式", 12);
        nl.setAlpha(0.7f);
        nl.setPadding(0, dp(12), 0, dp(4));
        card.addView(nl);
        LinearLayout modes = horizontal();
        String[] mn = {"仅局域网", "自动", "CF 隧道"};
        for (int i = 0; i < 3; i++) {
            final int m = i;
            TextView c = text(mn[i], 14);
            c.setGravity(Gravity.CENTER);
            c.setPadding(dp(4), dp(8), dp(4), dp(8));
            c.setOnClickListener(v -> setMode(m));
            modeChips[i] = c;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) {
                lp.setMarginStart(dp(6));
            }
            modes.addView(c, lp);
        }
        card.addView(modes);
        TextView mh = text("自动：有公网 IPv4 直连，否则走 Cloudflare 隧道；外网需先设访问密码。运行中切换会自动重启服务。", 11);
        mh.setAlpha(0.6f);
        mh.setPadding(0, dp(4), 0, 0);
        card.addView(mh);

        toggleBtn = button("启动", ACCENT, true);
        toggleBtn.setOnClickListener(v -> toggleServer());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(12);
        card.addView(toggleBtn, blp);
        page.addView(card, cardLp());

        permView = text("", 13);
        permView.setTextColor(ORANGE);
        permView.setPadding(dp(4), dp(8), dp(4), dp(4));
        permView.setOnClickListener(v -> requestStorage());
        page.addView(permView);

        LinearLayout sc = card();
        TextView h = text("共享目录", 16);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        sc.addView(h);
        LinearLayout types = horizontal();
        types.setPadding(0, dp(8), 0, 0);
        String[] tn = {"内部存储", "系统位置（ROOT）"};
        for (int i = 0; i < 2; i++) {
            final String type = i == 0 ? Prefs.TYPE_INTERNAL : Prefs.TYPE_SYSTEM;
            TextView c = text(tn[i], 14);
            c.setGravity(Gravity.CENTER);
            c.setPadding(dp(4), dp(8), dp(4), dp(8));
            c.setOnClickListener(v -> setShareType(type));
            typeChips[i] = c;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) {
                lp.setMarginStart(dp(6));
            }
            types.addView(c, lp);
        }
        sc.addView(types);
        LinearLayout pr = horizontal();
        pr.setGravity(Gravity.CENTER_VERTICAL);
        pr.setPadding(0, dp(10), 0, 0);
        sharePathView = text("", 14);
        sharePathView.setTypeface(Typeface.MONOSPACE);
        sharePathView.setTextColor(ACCENT);
        pr.addView(sharePathView, new LinearLayout.LayoutParams(0, -2, 1));
        TextView change = button("选择目录…", ACCENT, false);
        change.setOnClickListener(v -> {
            String type = prefs.shareType();
            boolean sys = Prefs.TYPE_SYSTEM.equals(type);
            pickDir(prefs.sharePath(type), sys, sys ? "/" : Prefs.sdcard(), chosen -> {
                prefs.setShare(type, chosen);
                browseRel = "";
                refreshShares();
                if (currentTab == TAB_BROWSE) {
                    loadBrowse();
                }
            });
        });
        pr.addView(change);
        sc.addView(pr);
        shareHint = text("", 12);
        shareHint.setAlpha(0.65f);
        shareHint.setPadding(0, dp(6), 0, 0);
        sc.addView(shareHint);
        page.addView(sc, cardLp());
        return page(page);
    }

    private void refreshShares() {
        if (sharePathView == null) {
            return;
        }
        String type = prefs.shareType();
        boolean sys = Prefs.TYPE_SYSTEM.equals(type);
        sharePathView.setText(prefs.sharePath(type));
        shareHint.setText(sys
                ? "通过 su 访问，可共享 /data、/system 等任意位置。网页端可改动系统文件，请务必设置访问密码。"
                : "只能选择内部存储（" + Prefs.sdcard() + "）下的目录，需要“所有文件访问”权限。");
        for (int i = 0; i < 2; i++) {
            TextView c = typeChips[i];
            boolean on = (i == 1) == sys;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(8));
            if (on) {
                g.setColor(i == 1 ? RED : ACCENT);
                c.setTextColor(Color.WHITE);
                c.setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                g.setStroke(dp(1), 0x88888888);
                c.setTextColor(fg());
                c.setTypeface(Typeface.DEFAULT);
            }
            c.setBackground(g);
        }
    }

    /** 内部存储 / 系统位置 二选一；即时生效（服务运行中也无需重启） */
    private void setShareType(String type) {
        if (type.equals(prefs.shareType())) {
            return;
        }
        if (Prefs.TYPE_SYSTEM.equals(type)) {
            new Thread(() -> {
                boolean ok = RootBackend.available();
                runOnUiThread(() -> {
                    if (!ok) {
                        toast("未获得 root 授权（请在 KernelSU/Magisk 中允许本应用）");
                        return;
                    }
                    applyShareType(type);
                });
            }).start();
            return;
        }
        applyShareType(type);
    }

    private void applyShareType(String type) {
        prefs.setShareType(type);
        browseRel = "";
        refreshShares();
        if (currentTab == TAB_BROWSE) {
            loadBrowse();
        }
        toast(Prefs.TYPE_SYSTEM.equals(type) ? "已切换为系统位置（ROOT）" : "已切换为内部存储");
    }

    private void setMode(int m) {
        if (m == prefs.remoteMode()) {
            return;
        }
        if (m != Remote.MODE_OFF && !prefs.hasPassword()) {
            toast("外网访问需要先在「设置」里设置访问密码");
            return;
        }
        if (m != Remote.MODE_OFF && !Remote.binaryAvailable(ShareService.tunnelBinary(this))) {
            toast("本机架构不支持 Cloudflare 隧道，只能公网 IPv4 直连");
        }
        prefs.setRemoteMode(m);
        refreshModeChips();
        if (ShareService.running) {
            Intent i = new Intent(this, ShareService.class);
            stopService(i);
            stateView.setText("正在切换网络方式…");
            ui.postDelayed(() -> {
                ShareService.error = null;
                startForegroundService(i);
                ui.postDelayed(this::refresh, 600);
            }, 700);
        }
    }

    private void refreshModeChips() {
        int cur = prefs.remoteMode();
        for (int i = 0; i < 3; i++) {
            TextView c = modeChips[i];
            if (c == null) {
                continue;
            }
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(8));
            if (i == cur) {
                g.setColor(ACCENT);
                c.setTextColor(Color.WHITE);
                c.setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                g.setStroke(dp(1), 0x88888888);
                c.setTextColor(fg());
                c.setTypeface(Typeface.DEFAULT);
            }
            c.setBackground(g);
        }
    }

    private void toggleServer() {
        Intent i = new Intent(this, ShareService.class);
        if (ShareService.running) {
            stopService(i);
        } else {
            ShareService.error = null;
            startForegroundService(i);
            stateView.setText("启动中…");
        }
        stateView.postDelayed(this::refresh, 600);
    }

    // ================================================================== 浏览页（同步查阅）

    private View buildBrowsePage() {
        LinearLayout page = vertical();
        browseSync = text("", 12);
        browseSync.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));
        g.setColor(dark() ? 0xFF1E2326 : 0xFFF3F5F7);
        browseSync.setBackground(g);
        browseSync.setOnClickListener(v -> {
            String u = showmeUrl();
            if (u != null) {
                getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("url", u));
                toast("已复制 " + u);
            }
        });
        page.addView(browseSync);

        LinearLayout bar = horizontal();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(10), 0, dp(4));
        TextView upBtn = button("↑ 上级", ACCENT, false);
        upBtn.setOnClickListener(v -> browseUp());
        bar.addView(upBtn);
        browsePath = text("", 13);
        browsePath.setTypeface(Typeface.MONOSPACE);
        browsePath.setPadding(dp(10), 0, 0, 0);
        bar.addView(browsePath, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(bar);

        browseList = vertical();
        page.addView(browseList);
        ScrollView sv = page(page);
        browseScroll = sv;
        return sv;
    }

    private String showmeUrl() {
        if (!ShareService.running) {
            return null;
        }
        if (Remote.url != null) {
            return Remote.url + "/showme";
        }
        List<String> ips = ShareService.addresses();
        return ShareService.scheme + "://" + (ips.isEmpty() ? "127.0.0.1" : ips.get(0)) + ":" + ShareService.port + "/showme";
    }

    private void refreshSyncInfo() {
        if (browseSync == null) {
            return;
        }
        io.github.jiemo9527.httpshare.server.ShowMe sm = ShareService.showme;
        if (!ShareService.running || sm == null) {
            browseSync.setText("同步查阅：服务未启动。启动后网页打开 /showme，会实时显示你在这里打开的目录。");
            browseSync.setTextColor(fg());
            return;
        }
        if (!prefs.showmeSync()) {
            browseSync.setText("同步查阅已在设置中关闭");
            browseSync.setTextColor(fg());
            return;
        }
        int n = sm.viewers();
        browseSync.setText("● 同步查阅中 · " + n + " 个网页在看（点此复制地址）\n" + showmeUrl());
        browseSync.setTextColor(n > 0 ? ACCENT : fg());
    }

    private void publishShowme(String p, String title) {
        io.github.jiemo9527.httpshare.server.ShowMe sm = ShareService.showme;
        if (sm != null) {
            sm.publish(prefs.showmeSync() ? p : null, title);
        }
    }

    private void browseUp() {
        if (browseRel.isEmpty()) {
            return;
        }
        int i = browseRel.lastIndexOf('/');
        browseRel = i < 0 ? "" : browseRel.substring(0, i);
        loadBrowse();
    }

    private void loadBrowse() {
        if (browseList == null) {
            return;
        }
        refreshSyncInfo();
        final List<Prefs.Share> shares = prefs.shares();
        browseShare = 0;
        browseList.removeAllViews();
        final Prefs.Share sh = shares.get(browseShare);
        final String abs = joinPath(sh.path, browseRel);
        final String title = sh.name + (browseRel.isEmpty() ? "" : "/" + browseRel);
        browsePath.setText(title);
        publishShowme("/" + browseShare + (browseRel.isEmpty() ? "" : "/" + browseRel), title);
        TextView loading = text("加载中…", 13);
        loading.setAlpha(0.6f);
        loading.setPadding(dp(4), dp(16), 0, 0);
        browseList.addView(loading);
        final int token = ++browseToken;
        new Thread(() -> {
            List<io.github.jiemo9527.httpshare.server.FileBackend.Entry> list = null;
            String err = null;
            try {
                list = listDir(abs, sh.root);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final List<io.github.jiemo9527.httpshare.server.FileBackend.Entry> fl = list;
            final String fe = err;
            runOnUiThread(() -> {
                if (token != browseToken) {
                    return;
                }
                browseList.removeAllViews();
                if (fe != null) {
                    TextView e = text("读取失败：" + fe, 13);
                    e.setTextColor(RED);
                    e.setPadding(dp(4), dp(16), 0, 0);
                    browseList.addView(e);
                    return;
                }
                if (fl.isEmpty()) {
                    TextView e = text("空目录", 13);
                    e.setAlpha(0.6f);
                    e.setPadding(dp(4), dp(16), 0, 0);
                    browseList.addView(e);
                }
                int shown = 0;
                for (io.github.jiemo9527.httpshare.server.FileBackend.Entry e : fl) {
                    if (++shown > 1000) {
                        TextView more = text("…共 " + fl.size() + " 项，仅显示前 1000 项（网页端显示全部）", 12);
                        more.setAlpha(0.6f);
                        browseList.addView(more);
                        break;
                    }
                    String sub = e.dir ? time(e.mtime) : size(e.size) + " · " + time(e.mtime);
                    View.OnClickListener click = e.dir ? v -> {
                        browseRel = browseRel.isEmpty() ? e.name : browseRel + "/" + e.name;
                        loadBrowse();
                    } : null;
                    browseList.addView(entryRow(e.dir ? "📁" : fileIcon(e.name), e.name, sub, click));
                }
                if (browseScroll != null) {
                    browseScroll.scrollTo(0, 0);
                }
            });
        }).start();
    }

    /** 后台线程：按共享类型列目录，目录在前、名称自然排序 */
    private static List<io.github.jiemo9527.httpshare.server.FileBackend.Entry> listDir(String abs, boolean root)
            throws Exception {
        io.github.jiemo9527.httpshare.server.FileBackend fs = root
                ? new RootBackend() : new io.github.jiemo9527.httpshare.server.LocalBackend();
        List<io.github.jiemo9527.httpshare.server.FileBackend.Entry> l = fs.list(abs);
        final java.text.Collator col = java.text.Collator.getInstance(java.util.Locale.CHINA);
        java.util.Collections.sort(l, (a, b) -> a.dir != b.dir ? (a.dir ? -1 : 1) : col.compare(a.name, b.name));
        return l;
    }

    private static String joinPath(String base, String rel) {
        if (rel.isEmpty()) {
            return base;
        }
        return base.endsWith("/") ? base + rel : base + "/" + rel;
    }

    private View entryRow(String icon, String name, String sub, View.OnClickListener click) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(9), dp(4), dp(9));
        TextView ic = text(icon, 20);
        ic.setPadding(0, 0, dp(10), 0);
        row.addView(ic);
        LinearLayout col = vertical();
        TextView n = text(name, 15);
        col.addView(n);
        if (sub != null && !sub.isEmpty()) {
            TextView s2 = text(sub, 11);
            s2.setAlpha(0.6f);
            col.addView(s2);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        if (click != null) {
            row.setOnClickListener(click);
            TextView arrow = text("›", 18);
            arrow.setAlpha(0.4f);
            row.addView(arrow);
        }
        return row;
    }

    private static String size(long n) {
        if (n < 1024) {
            return n + " B";
        }
        String[] u = {"KB", "MB", "GB", "TB"};
        double d = n;
        int i = -1;
        do {
            d /= 1024;
            i++;
        } while (d >= 1024 && i < 3);
        return String.format(java.util.Locale.ROOT, d < 10 ? "%.2f %s" : "%.1f %s", d, u[i]);
    }

    private static String time(long t) {
        return t <= 0 ? "" : new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(new java.util.Date(t));
    }

    private static String fileIcon(String n) {
        int d = n.lastIndexOf('.');
        String x = d < 0 ? "" : n.substring(d + 1).toLowerCase(java.util.Locale.ROOT);
        if (x.matches("jpe?g|png|gif|webp|bmp|heic|svg")) return "🖼️";
        if (x.matches("mp4|mkv|webm|mov|3gp|avi")) return "🎬";
        if (x.matches("mp3|m4a|flac|wav|ogg|aac|opus|amr")) return "🎵";
        if (x.matches("zip|rar|7z|tar|gz|xz")) return "🗜️";
        if (x.equals("apk")) return "📦";
        if (x.matches("txt|log|md|json|xml|prop|conf|ini")) return "📄";
        return "📃";
    }

    // ================================================================== 路径选择器

    interface PathCallback {
        void onPicked(String path);
    }

    /** 简易文件管理器：逐级进入目录，选中当前目录。root=true 时通过 su 列目录，可进入 /data 等；不能退到 floor 之上 */
    private void pickDir(String start, boolean root, String floor, PathCallback cb) {
        final String fl = floor.length() > 1 && floor.endsWith("/") ? floor.substring(0, floor.length() - 1) : floor;
        final String[] cur = {start != null && (start.equals(fl) || fl.equals("/") && start.startsWith("/")
                || start.startsWith(fl + "/")) ? start : fl};
        LinearLayout box = vertical();
        box.setPadding(dp(16), dp(4), dp(16), 0);
        TextView pathView = text("", 13);
        pathView.setTypeface(Typeface.MONOSPACE);
        pathView.setTextColor(ACCENT);
        pathView.setPadding(0, 0, 0, dp(6));
        box.addView(pathView);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout quick = horizontal();
        String sd = Environment.getExternalStorageDirectory().getAbsolutePath();
        String[][] qs = root
                ? new String[][]{{"/", "/"}, {"/data", "/data"}, {"/system", "/system"}, {"内部存储", sd}, {"/vendor", "/vendor"}}
                : new String[][]{{"内部存储", sd}, {"DCIM", sd + "/DCIM"}, {"Download", sd + "/Download"}, {"Pictures", sd + "/Pictures"}};
        hs.addView(quick);
        box.addView(hs);

        LinearLayout list = vertical();
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        box.addView(sv, new LinearLayout.LayoutParams(-1, (int) (getResources().getDisplayMetrics().heightPixels * 0.45f)));
        TextView mode = text(root ? "系统位置（ROOT）：可进入任意目录" : "内部存储：只能选择 " + fl + " 下的目录", 11);
        mode.setAlpha(0.6f);
        mode.setPadding(0, dp(6), 0, 0);
        box.addView(mode);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("选择目录")
                .setView(box)
                .setPositiveButton("选择此目录", (d, w) -> cb.onPicked(cur[0]))
                .setNeutralButton("上一级", null)
                .setNegativeButton("取消", null)
                .create();

        final int[] tok = {0};
        final Runnable[] load = new Runnable[1];
        load[0] = () -> {
            pathView.setText(cur[0]);
            list.removeAllViews();
            TextView l = text("加载中…", 13);
            l.setAlpha(0.6f);
            list.addView(l);
            final String dir = cur[0];
            final int t = ++tok[0];
            new Thread(() -> {
                List<io.github.jiemo9527.httpshare.server.FileBackend.Entry> es = null;
                String err = null;
                try {
                    es = listDir(dir, root);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final List<io.github.jiemo9527.httpshare.server.FileBackend.Entry> fes = es;
                final String fe = err;
                runOnUiThread(() -> {
                    if (t != tok[0]) {
                        return;
                    }
                    list.removeAllViews();
                    if (fe != null) {
                        TextView e = text("无法读取：" + fe + (root ? "" : "\n可开启「使用 ROOT 访问」后再浏览"), 13);
                        e.setTextColor(RED);
                        list.addView(e);
                        return;
                    }
                    int dirs = 0;
                    for (io.github.jiemo9527.httpshare.server.FileBackend.Entry e : fes) {
                        if (!e.dir) {
                            continue;
                        }
                        dirs++;
                        list.addView(entryRow("📁", e.name, null, v -> {
                            cur[0] = joinPath(dir, e.name);
                            load[0].run();
                        }));
                    }
                    int files = fes.size() - dirs;
                    TextView info = text(dirs == 0 ? "（没有子目录" + (files > 0 ? "，含 " + files + " 个文件）" : "）")
                            : (files > 0 ? "另有 " + files + " 个文件" : ""), 12);
                    info.setAlpha(0.5f);
                    info.setPadding(dp(4), dp(8), 0, 0);
                    list.addView(info);
                    sv.scrollTo(0, 0);
                });
            }).start();
        };
        for (String[] q : qs) {
            TextView chip = text(q[0], 13);
            chip.setPadding(dp(10), dp(5), dp(10), dp(5));
            chip.setBackground(outline(0x88888888));
            chip.setOnClickListener(v -> {
                cur[0] = q[1];
                load[0].run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginEnd(dp(6));
            lp.bottomMargin = dp(6);
            quick.addView(chip, lp);
        }
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            String c = cur[0];
            if (c.equals("/") || c.equals(fl)) {
                toast("已是最上层");
                return;
            }
            int i = c.lastIndexOf('/');
            cur[0] = i <= 0 ? "/" : c.substring(0, i);
            load[0].run();
        }));
        dlg.show();
        load[0].run();
    }

    // ================================================================== 日志页

    private View buildLogPage() {
        LinearLayout page = vertical();
        LinearLayout bar = horizontal();
        bar.setGravity(Gravity.END);
        TextView clear = button("清空", ACCENT, false);
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("清空全部日志？（日志保存在本机，最多保留最新 " + ShareService.MAX_LOG + " 行，不会自动清空）")
                .setPositiveButton("清空", (d, w) -> {
                    ShareService.clearLogs();
                    refreshLog();
                })
                .setNegativeButton("取消", null)
                .show());
        bar.addView(clear);
        page.addView(bar);
        logView = text("", 12);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(0, dp(8), 0, 0);
        page.addView(logView);
        return page(page);
    }

    private void refreshLog() {
        if (logView == null) {
            return;
        }
        List<String> l = ShareService.logs();
        logView.setText(l.isEmpty() ? "暂无日志（保存在本机，最多保留最新 " + ShareService.MAX_LOG + " 行，不会自动清空）"
                : String.join("\n", l));
    }

    // ================================================================== 设置页

    private View buildSettingsPage() {
        LinearLayout page = vertical();

        section(page, "网络");
        LinearLayout portRow = horizontal();
        portRow.setGravity(Gravity.CENTER_VERTICAL);
        portRow.addView(text("端口", 15), new LinearLayout.LayoutParams(0, -2, 1));
        TextView portVal = button(String.valueOf(prefs.port()), ACCENT, false);
        portVal.setOnClickListener(v -> {
            EditText e = edit("1024–65535", String.valueOf(prefs.port()));
            e.setInputType(InputType.TYPE_CLASS_NUMBER);
            new AlertDialog.Builder(this).setTitle("端口").setView(wrapDialog(e))
                    .setPositiveButton("保存", (d, w) -> {
                        try {
                            int p = Integer.parseInt(e.getText().toString().trim());
                            if (p < 1024 || p > 65535) {
                                throw new NumberFormatException();
                            }
                            prefs.setPort(p);
                            portVal.setText(String.valueOf(p));
                            restartHint();
                        } catch (NumberFormatException ex) {
                            toast("端口需在 1024–65535");
                        }
                    }).setNegativeButton("取消", null).show();
        });
        portRow.addView(portVal);
        page.addView(portRow);

        section(page, "网页端权限");
        Switch up = sw("允许上传 / 新建文件夹", prefs.allowUpload());
        up.setOnCheckedChangeListener((b, c) -> prefs.setAllowUpload(c));
        page.addView(up);
        Switch mod = sw("允许改名 / 删除（含覆盖同名文件）", prefs.allowModify());
        mod.setOnCheckedChangeListener((b, c) -> prefs.setAllowModify(c));
        page.addView(mod);
        hint(page, "两项都关闭时网页端只读（仍可浏览、下载）。即时生效，无需重启服务。");

        section(page, "同步查阅");
        Switch smSw = sw("在「浏览」页打开目录时同步到 /showme", prefs.showmeSync());
        smSw.setOnCheckedChangeListener((b, c) -> {
            prefs.setShowmeSync(c);
            if (!c) {
                publishShowme(null, null);
            }
        });
        page.addView(smSw);
        hint(page, "网页打开 http(s)://地址/showme ，会实时显示你在 App「浏览」页打开的目录（需登录，只读）。离开浏览页后网页显示等待状态。");

        section(page, "WebDAV（挂载为网络盘）");
        Switch davSw = sw("开启 WebDAV（地址 /dav/）", prefs.webdav());
        davSw.setOnCheckedChangeListener((b, c) -> {
            prefs.setWebdav(c);
            refresh();
        });
        page.addView(davSw);
        hint(page, "在电脑/手机文件管理器里把手机挂成网络盘，直接打开、编辑、保存文件，改动实时写回手机。"
                + "用户名任意，密码为访问密码；读写权限与网页端相同。即时生效。\n"
                + "· Windows：网页上点「挂载为网络盘」→ 下载一键挂载脚本，双击运行、输入密码即可（首次会弹一次管理员确认，自动修改 WebDAV 设置）。\n"
                + "· macOS：Finder → 前往 → 连接服务器。\n"
                + "· 安卓/iOS：支持 WebDAV 的文件管理器（如 MT 管理器、Solid Explorer、Documents）。");

        section(page, "加密");
        LinearLayout pwRow = horizontal();
        pwRow.setGravity(Gravity.CENTER_VERTICAL);
        passwordState = text("", 15);
        pwRow.addView(passwordState, new LinearLayout.LayoutParams(0, -2, 1));
        TextView pwBtn = button("设置", ACCENT, false);
        pwBtn.setOnClickListener(v -> editPassword());
        pwRow.addView(pwBtn);
        page.addView(pwRow);
        LinearLayout pwShow = horizontal();
        pwShow.setGravity(Gravity.CENTER_VERTICAL);
        pwShow.setPadding(0, dp(2), 0, dp(4));
        passwordValue = text("", 16);
        passwordValue.setTypeface(Typeface.MONOSPACE);
        passwordValue.setOnClickListener(v -> togglePwVisible());
        pwShow.addView(passwordValue, new LinearLayout.LayoutParams(0, -2, 1));
        pwEye = button("显示", ACCENT, false);
        pwEye.setOnClickListener(v -> togglePwVisible());
        pwShow.addView(pwEye);
        TextView pwCopy = button("复制", ACCENT, false);
        pwCopy.setOnClickListener(v -> {
            String pw = prefs.password();
            if (pw != null) {
                copySecret("访问密码", pw);
            }
        });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.setMarginStart(dp(6));
        pwShow.addView(pwCopy, cl);
        passwordShowRow = pwShow;
        page.addView(pwShow);
        hint(page, "访问网页或文件前需输入密码。可随机生成 9–12 位（大小写字母 + 数字）。校验用加盐哈希；"
                + "明文用系统 Keystore 加密保存，仅本机 App 内可查看/复制。同一 IP 连续输错 3 次封锁 2 小时，"
                + "重启服务即可解除全部封锁（外网访问按真实访客 IP 计）。"
                + "curl/wget/WebDAV 用 HTTP Basic：用户名任意，密码为访问密码。即时生效。");

        Switch https = sw("HTTPS 加密传输（自签名证书）", prefs.https());
        https.setOnCheckedChangeListener((b, c) -> {
            prefs.setHttps(c);
            restartHint();
        });
        page.addView(https);
        TextView fp = text("", 11);
        fp.setTypeface(Typeface.MONOSPACE);
        fp.setAlpha(0.6f);
        TextView showFp = button("查看证书指纹", ACCENT, false);
        showFp.setOnClickListener(v -> new Thread(() -> {
            String f = Tls.fingerprint(getFilesDir());
            runOnUiThread(() -> fp.setText("SHA-256\n" + f));
        }).start());
        hint(page, "防止同一 Wi-Fi 下被窃听密码和文件。浏览器会提示证书不受信任，核对指纹一致后继续访问即可。修改后需重启服务。");
        page.addView(showFp, wrapLp());
        page.addView(fp);

        section(page, "外网访问");
        hint(page, "网络方式在「共享」页启动按钮上方切换。Cloudflare 临时隧道：手机主动连出，无需公网 IP，流量下也能用；"
                + "地址形如 xxx.trycloudflare.com，每次启动会变，单文件上传上限约 100 MB。外网访问强制要求设置访问密码。"
                + "如手机装了 box/mihomo 等透明代理，需让本应用绕过代理。");

        section(page, "桌面图标");
        hideIconSwitch = sw("隐藏桌面图标", isIconHidden());
        hideIconSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (checked == isIconHidden()) {
                return;
            }
            setIconHidden(checked);
            toast(checked ? "已隐藏，可从 LSPosed 管理器打开" : "已恢复桌面图标");
        });
        page.addView(hideIconSwitch);
        hint(page, "手动开关，默认不隐藏。隐藏后打开方式：LSPosed 管理器 → 模块 → HTTP 共享，或点通知栏。");

        section(page, "关于");
        hint(page, "项目源码、版本说明与问题反馈：");
        TextView link = text("GitHub · HttpShare ↗", 15);
        link.setTextColor(ACCENT);
        link.setPadding(0, dp(8), 0, dp(8));
        link.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(Prefs.GITHUB_URL));
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                toast("没有可打开网页的应用");
            }
        });
        page.addView(link);
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        TextView ver = text("版本 " + version, 12);
        ver.setAlpha(0.5f);
        page.addView(ver);
        return page(page);
    }

    private void refreshPassword() {
        if (passwordState == null) {
            return;
        }
        boolean has = prefs.hasPassword();
        String pw = has ? prefs.password() : null;
        passwordState.setText(has ? "访问密码：已设置" : "访问密码：未设置");
        passwordShowRow.setVisibility(pw != null ? View.VISIBLE : View.GONE);
        if (has && pw == null) {
            passwordState.setText("访问密码：已设置（旧版本设置，无法查看，重新设置后可查看）");
        }
        if (pw != null) {
            passwordValue.setText(pwVisible ? pw : "••••••••••");
            passwordValue.setTextColor(pwVisible ? ACCENT : fg());
            pwEye.setText(pwVisible ? "隐藏" : "显示");
        }
    }

    private void togglePwVisible() {
        pwVisible = !pwVisible;
        refreshPassword();
    }

    /** 复制敏感内容：Android 13+ 标记为敏感，剪贴板预览不显示明文 */
    private void copySecret(String label, String value) {
        ClipData cd = ClipData.newPlainText(label, value);
        if (Build.VERSION.SDK_INT >= 33) {
            android.os.PersistableBundle extras = new android.os.PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            cd.getDescription().setExtras(extras);
        }
        getSystemService(ClipboardManager.class).setPrimaryClip(cd);
        toast("已复制" + label);
    }

    private void editPassword() {
        LinearLayout box = vertical();
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText e = edit("访问密码（至少 4 位）", "");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        e.setTypeface(Typeface.MONOSPACE);
        box.addView(e);
        LinearLayout row = horizontal();
        row.setPadding(0, dp(8), 0, 0);
        TextView gen = button("🎲 随机生成", ACCENT, false);
        gen.setOnClickListener(v -> {
            e.setText(Prefs.randomPassword());
            e.setSelection(e.getText().length());
        });
        row.addView(gen);
        box.addView(row);
        TextView tip = text("随机密码为 9–12 位大小写字母与数字（已去掉 0/O、1/l/I 等易混字符）。保存后可在此处查看、复制。", 12);
        tip.setAlpha(0.65f);
        tip.setPadding(0, dp(8), 0, 0);
        box.addView(tip);
        if (!prefs.hasPassword()) {
            e.setText(Prefs.randomPassword());
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("访问密码").setView(box)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null);
        if (prefs.hasPassword()) {
            b.setNeutralButton("取消密码", (d, w) -> {
                if (prefs.remoteMode() != Remote.MODE_OFF) {
                    toast("外网访问需要密码：请先在「共享」页切换为仅局域网");
                    return;
                }
                new Thread(() -> {
                    prefs.setPassword(null);
                    runOnUiThread(() -> {
                        toast("已取消密码");
                        refresh();
                    });
                }).start();
            });
        }
        AlertDialog dlg = b.create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pw = e.getText().toString();
            if (pw.length() < 4) {
                toast("密码至少 4 位");
                return;
            }
            if (pw.length() > 64 || !pw.equals(pw.trim())) {
                toast("密码最长 64 位，且首尾不能是空格");
                return;
            }
            dlg.dismiss();
            new Thread(() -> {
                prefs.setPassword(pw);
                runOnUiThread(() -> {
                    pwVisible = true;
                    refresh();
                    copySecret("访问密码", pw);
                });
            }).start();
        }));
        dlg.show();
    }

    private void restartHint() {
        if (ShareService.running) {
            toast("重启服务后生效");
        }
    }

    // ================================================================== 状态

    private void refresh() {
        if (stateView == null) {
            return;
        }
        boolean active = ModuleStatus.isActive();
        moduleView.setText(active ? "● LSPosed 模块已生效" : "○ LSPosed 模块未启用（共享功能不受影响）");
        moduleView.setTextColor(active ? ACCENT : ORANGE);

        urlBox.removeAllViews();
        if (ShareService.running) {
            stateView.setText("● 运行中");
            stateView.setTextColor(ACCENT);
            toggleBtn.setText("停止");
            ((GradientDrawable) toggleBtn.getBackground()).setColor(RED);
            List<String> ips = ShareService.addresses();
            if (ips.isEmpty()) {
                urlBox.addView(text("未检测到局域网地址，请连接 Wi-Fi 或开启热点", 13));
            }
            for (String ip : ips) {
                String url = ShareService.scheme + "://" + ip + ":" + ShareService.port;
                TextView u = text(url, 17);
                u.setTypeface(Typeface.MONOSPACE);
                u.setTextColor(ACCENT);
                u.setPadding(0, dp(6), 0, dp(2));
                u.setOnClickListener(v -> {
                    getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("url", url));
                    toast("已复制 " + url);
                });
                urlBox.addView(u);
            }
            TextView tip = text("点击地址复制；同一局域网的电脑/手机浏览器打开即可。"
                    + (prefs.webdav() ? "WebDAV：地址后加 /dav/" : "")
                    + (prefs.hasPassword() ? "" : "\n⚠ 未设置访问密码，同网段任何人都能访问。"), 12);
            tip.setAlpha(0.75f);
            urlBox.addView(tip);
            if (prefs.remoteMode() != Remote.MODE_OFF) {
                TextView rh = text("外网", 13);
                rh.setTypeface(Typeface.DEFAULT_BOLD);
                rh.setPadding(0, dp(12), 0, 0);
                urlBox.addView(rh);
                String ru = Remote.url;
                if (ru != null) {
                    TextView u = text(ru, 15);
                    u.setTypeface(Typeface.MONOSPACE);
                    u.setTextColor(ACCENT);
                    u.setPadding(0, dp(4), 0, dp(2));
                    u.setOnClickListener(v -> {
                        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("url", ru));
                        toast("已复制 " + ru);
                    });
                    urlBox.addView(u);
                }
                TextView rs = text(Remote.state == null || Remote.state.isEmpty() ? "准备中…" : Remote.state, 12);
                rs.setAlpha(0.75f);
                urlBox.addView(rs);
            }
        } else {
            stateView.setText(ShareService.error != null ? ShareService.error : "○ 已停止");
            stateView.setTextColor(ShareService.error != null ? RED : fg());
            toggleBtn.setText("启动");
            ((GradientDrawable) toggleBtn.getBackground()).setColor(ACCENT);
        }

        boolean storage = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
        permView.setVisibility(storage ? View.GONE : View.VISIBLE);
        permView.setText("⚠ 未授予“所有文件访问”权限，非 ROOT 共享可能无法读取内部存储。点此授权");

        refreshShares();
        refreshModeChips();
        refreshPassword();
        if (hideIconSwitch != null) {
            hideIconSwitch.setChecked(isIconHidden());
        }
        if (currentTab == TAB_LOG) {
            refreshLog();
        }
    }

    private void requestStorage() {
        if (Build.VERSION.SDK_INT < 30) {
            return;
        }
        // 有 root 时直接用 appops 授权，失败再跳系统设置
        new Thread(() -> {
            boolean ok = false;
            try {
                if (RootBackend.available()) {
                    new ProcessBuilder("su", "-c", "appops set " + getPackageName()
                            + " MANAGE_EXTERNAL_STORAGE allow").start().waitFor();
                    ok = Environment.isExternalStorageManager();
                }
            } catch (Exception ignored) {
            }
            boolean done = ok;
            runOnUiThread(() -> {
                if (done) {
                    toast("已通过 root 授权");
                    refresh();
                    return;
                }
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            });
        }).start();
    }

    // ================================================================== 图标

    /** 0.2 及以前版本会自动隐藏图标；升级后恢复一次，之后只由用户手动控制 */
    private void restoreIconOnce() {
        android.content.SharedPreferences sp = getSharedPreferences("config", MODE_PRIVATE);
        if (sp.getBoolean("icon_migrated", false)) {
            return;
        }
        sp.edit().putBoolean("icon_migrated", true).remove("auto_hide").apply();
        if (isIconHidden()) {
            setIconHidden(false);
            if (hideIconSwitch != null) {
                hideIconSwitch.setChecked(false);
            }
        }
    }

    private boolean isIconHidden() {
        int s = getPackageManager().getComponentEnabledSetting(new ComponentName(getPackageName(), LAUNCHER_ALIAS));
        return s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    private void setIconHidden(boolean hidden) {
        getPackageManager().setComponentEnabledSetting(
                new ComponentName(getPackageName(), LAUNCHER_ALIAS),
                hidden ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
    }

    // ================================================================== 视图工具

    private boolean dark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private int fg() {
        return dark() ? 0xFFE6E8EA : 0xFF1D2126;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(fg());
        return t;
    }

    private TextView button(String s, int color, boolean filled) {
        TextView b = text(s, 14);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(filled ? 11 : 6), dp(14), dp(filled ? 11 : 6));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(filled ? 10 : 16));
        if (filled) {
            g.setColor(color);
            b.setTextColor(Color.WHITE);
            b.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            g.setColor(Color.TRANSPARENT);
            g.setStroke(dp(1), color);
            b.setTextColor(color);
        }
        b.setBackground(g);
        return b;
    }

    private GradientDrawable outline(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setStroke(dp(1), color);
        return g;
    }

    private LinearLayout card() {
        LinearLayout c = vertical();
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(dark() ? 0xFF1E2326 : 0xFFF3F5F7);
        c.setBackground(g);
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        return lp;
    }

    private LinearLayout.LayoutParams wrapLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(6);
        return lp;
    }

    private void section(LinearLayout page, String title) {
        TextView t = text(title, 13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(ACCENT);
        t.setPadding(0, dp(20), 0, dp(4));
        page.addView(t);
    }

    private void hint(LinearLayout page, String s) {
        TextView t = text(s, 12);
        t.setAlpha(0.65f);
        t.setLineSpacing(dp(2), 1f);
        page.addView(t);
    }

    private Switch sw(String label, boolean checked) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        s.setTextColor(fg());
        s.setChecked(checked);
        s.setPadding(0, dp(8), 0, dp(8));
        return s;
    }

    private EditText edit(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        return e;
    }

    private View wrapDialog(View v) {
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(v, new FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));
        return f;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
