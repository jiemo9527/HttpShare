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
    private static final int TAB_LOG = 1;
    private static final int TAB_SETTINGS = 2;

    private Prefs prefs;
    private int currentTab;
    private final View[] pages = new View[3];
    private final TextView[] tabs = new TextView[3];

    private TextView moduleView;
    private TextView stateView;
    private TextView toggleBtn;
    private LinearLayout urlBox;
    private LinearLayout shareList;
    private TextView permView;
    private TextView logView;
    private Switch hideIconSwitch;
    private TextView passwordState;

    // ================================================================== 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        buildUi();
        autoHideIcon();
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
    }

    @Override
    protected void onPause() {
        ShareService.onChange = null;
        super.onPause();
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
        String[] names = {"共享", "日志", "设置"};
        for (int i = 0; i < 3; i++) {
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
        currentTab = idx;
        for (int i = 0; i < 3; i++) {
            pages[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            tabs[i].setTextColor(i == idx ? ACCENT : fg());
            tabs[i].setTypeface(i == idx ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        if (idx == TAB_LOG) {
            refreshLog();
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

        LinearLayout head = horizontal();
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, dp(14), 0, dp(6));
        TextView h = text("共享目录", 16);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(h, new LinearLayout.LayoutParams(0, -2, 1));
        TextView add = button("＋ 添加", ACCENT, false);
        add.setOnClickListener(v -> editShare(-1));
        head.addView(add);
        page.addView(head);

        shareList = vertical();
        page.addView(shareList);

        TextView hint = text("开启 ROOT 的目录通过 su 访问，可共享 /data、/system 等系统目录；"
                + "未开启的只能访问本应用有权限的目录（内部存储需“所有文件访问”权限）。修改共享目录无需重启服务。", 12);
        hint.setAlpha(0.7f);
        hint.setPadding(dp(4), dp(10), dp(4), 0);
        page.addView(hint);
        return page(page);
    }

    private void refreshShares() {
        shareList.removeAllViews();
        List<Prefs.Share> list = prefs.shares();
        if (list.isEmpty()) {
            TextView e = text("还没有共享目录，点右上角添加", 13);
            e.setAlpha(0.6f);
            e.setPadding(dp(4), dp(12), 0, dp(12));
            shareList.addView(e);
        }
        for (int i = 0; i < list.size(); i++) {
            final int idx = i;
            Prefs.Share s = list.get(i);
            LinearLayout row = card();
            LinearLayout top = horizontal();
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView n = text(s.name, 15);
            n.setTypeface(Typeface.DEFAULT_BOLD);
            top.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
            if (s.root) {
                TextView tag = text("ROOT", 11);
                tag.setTextColor(RED);
                tag.setPadding(dp(6), dp(1), dp(6), dp(1));
                tag.setBackground(outline(RED));
                top.addView(tag);
            }
            row.addView(top);
            TextView p = text(s.path, 12);
            p.setAlpha(0.7f);
            p.setTextIsSelectable(false);
            row.addView(p);
            row.setOnClickListener(v -> editShare(idx));
            shareList.addView(row, cardLp());
        }
    }

    private void editShare(int idx) {
        List<Prefs.Share> list = prefs.shares();
        Prefs.Share s = idx >= 0 ? list.get(idx) : new Prefs.Share("", "", false);

        LinearLayout box = vertical();
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText name = edit("名称（网页上显示）", s.name);
        EditText path = edit("绝对路径，如 /sdcard/DCIM 或 /data", s.path);
        Switch root = new Switch(this);
        root.setText("使用 ROOT 访问");
        root.setChecked(s.root);
        root.setPadding(0, dp(8), 0, dp(8));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout presets = horizontal();
        String sd = Environment.getExternalStorageDirectory().getAbsolutePath();
        String[][] ps = {
                {"内部存储", sd, "0"}, {"DCIM", sd + "/DCIM", "0"}, {"Download", sd + "/Download", "0"},
                {"/ 根目录", "/", "1"}, {"/data", "/data", "1"}, {"/system", "/system", "1"},
                {"应用数据", "/data/data", "1"}, {"/vendor", "/vendor", "1"},
        };
        for (String[] p : ps) {
            TextView chip = text(p[0], 13);
            chip.setPadding(dp(10), dp(5), dp(10), dp(5));
            chip.setBackground(outline(0x88888888));
            chip.setOnClickListener(v -> {
                path.setText(p[1]);
                root.setChecked(p[2].equals("1"));
                if (name.getText().length() == 0) {
                    name.setText(p[0]);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginEnd(dp(6));
            presets.addView(chip, lp);
        }
        hs.addView(presets);
        box.addView(hs);
        box.addView(name);
        box.addView(path);
        box.addView(root);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(idx >= 0 ? "编辑共享" : "添加共享")
                .setView(box)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null);
        if (idx >= 0) {
            b.setNeutralButton("删除", (d, w) -> {
                List<Prefs.Share> l = prefs.shares();
                l.remove(idx);
                prefs.setShares(l);
                refreshShares();
            });
        }
        AlertDialog dlg = b.create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String p = path.getText().toString().trim();
            String n = name.getText().toString().trim();
            if (!p.startsWith("/")) {
                toast("请填写以 / 开头的绝对路径");
                return;
            }
            if (p.length() > 1 && p.endsWith("/")) {
                p = p.substring(0, p.length() - 1);
            }
            if (n.isEmpty()) {
                n = p.equals("/") ? "根目录" : p.substring(p.lastIndexOf('/') + 1);
            }
            final String fp = p;
            final String fn = n;
            final boolean fr = root.isChecked();
            v.setEnabled(false);
            new Thread(() -> {
                String err = validate(fp, fr);
                runOnUiThread(() -> {
                    v.setEnabled(true);
                    if (err != null) {
                        toast(err);
                        return;
                    }
                    List<Prefs.Share> l = prefs.shares();
                    Prefs.Share ns = new Prefs.Share(fn, fp, fr);
                    if (idx >= 0) {
                        l.set(idx, ns);
                    } else {
                        l.add(ns);
                    }
                    prefs.setShares(l);
                    refreshShares();
                    dlg.dismiss();
                });
            }).start();
        }));
        dlg.show();
    }

    /** 后台线程调用：root 目录用 su 检查，普通目录检查可读 */
    private String validate(String path, boolean root) {
        if (root) {
            if (!RootBackend.available()) {
                return "未获得 root 授权（请在 KernelSU/Magisk 中允许本应用）";
            }
            try {
                io.github.jiemo9527.httpshare.server.FileBackend.Entry e = new RootBackend().stat(path);
                if (e == null || !e.dir) {
                    return "目录不存在：" + path;
                }
            } catch (Exception e) {
                return e.getMessage();
            }
            return null;
        }
        File f = new File(path);
        if (!f.isDirectory()) {
            return "目录不存在或无权限（系统目录请开启 ROOT）";
        }
        if (f.list() == null) {
            return "无法读取该目录：请授予“所有文件访问”或开启 ROOT";
        }
        return null;
    }

    private void toggleServer() {
        Intent i = new Intent(this, ShareService.class);
        if (ShareService.running) {
            stopService(i);
        } else {
            if (prefs.shares().isEmpty()) {
                toast("请先添加共享目录");
                return;
            }
            ShareService.error = null;
            startForegroundService(i);
            stateView.setText("启动中…");
        }
        stateView.postDelayed(this::refresh, 600);
    }

    // ================================================================== 日志页

    private View buildLogPage() {
        LinearLayout page = vertical();
        LinearLayout bar = horizontal();
        bar.setGravity(Gravity.END);
        TextView clear = button("清空", ACCENT, false);
        clear.setOnClickListener(v -> {
            ShareService.clearLogs();
            refreshLog();
        });
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
        logView.setText(l.isEmpty() ? "暂无日志（仅保存在内存中，最多 300 条）" : String.join("\n", l));
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

        Switch write = sw("允许上传 / 新建 / 改名 / 删除", prefs.allowWrite());
        write.setOnCheckedChangeListener((b, c) -> prefs.setAllowWrite(c));
        page.addView(write);
        hint(page, "关闭时网页端只读。即时生效。");

        section(page, "加密");
        LinearLayout pwRow = horizontal();
        pwRow.setGravity(Gravity.CENTER_VERTICAL);
        passwordState = text("", 15);
        pwRow.addView(passwordState, new LinearLayout.LayoutParams(0, -2, 1));
        TextView pwBtn = button("设置", ACCENT, false);
        pwBtn.setOnClickListener(v -> editPassword());
        pwRow.addView(pwBtn);
        page.addView(pwRow);
        hint(page, "访问网页或文件前需输入密码；只保存加盐哈希。错误 8 次锁定该 IP 5 分钟。"
                + "curl/wget 可用 HTTP Basic：curl -u x:密码 URL。即时生效。");

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
        String[] modes = {"关闭（仅局域网）", "自动：有公网 IPv4 直连，否则 Cloudflare 隧道", "总是使用 Cloudflare 隧道"};
        LinearLayout modeBox = vertical();
        android.widget.RadioGroup rg = new android.widget.RadioGroup(this);
        for (int i = 0; i < modes.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(1000 + i);
            rb.setText(modes[i]);
            rb.setTextColor(fg());
            rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            rb.setPadding(0, dp(4), 0, dp(4));
            rg.addView(rb);
        }
        rg.check(1000 + prefs.remoteMode());
        rg.setOnCheckedChangeListener((g, id) -> {
            int m = id - 1000;
            if (m != 0 && !prefs.hasPassword()) {
                toast("开启外网访问前请先设置访问密码");
            }
            if (m != 0 && !Remote.binaryAvailable(ShareService.tunnelBinary(this))) {
                toast("本机架构不支持隧道，只能公网 IPv4 直连");
            }
            prefs.setRemoteMode(m);
            restartHint();
        });
        modeBox.addView(rg);
        page.addView(modeBox);
        hint(page, "Cloudflare 临时隧道：手机主动连出，无需公网 IP，流量/移动网络也能用；地址形如 xxx.trycloudflare.com，每次启动会变，"
                + "单文件上传上限约 100 MB，速度取决于到 Cloudflare 的线路。为防止被扫描，外网访问强制要求设置访问密码。修改后需重启服务。");

        section(page, "桌面图标");
        Switch auto = sw("模块生效时自动隐藏桌面图标", prefs.autoHideIcon());
        auto.setOnCheckedChangeListener((b, c) -> {
            prefs.setAutoHideIcon(c);
            if (c) {
                autoHideIcon();
            }
        });
        page.addView(auto);
        hideIconSwitch = sw("隐藏桌面图标", isIconHidden());
        hideIconSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (checked == isIconHidden()) {
                return;
            }
            setIconHidden(checked);
            if (!checked) {
                // 手动恢复后不再自动隐藏，否则下次打开又会被隐藏
                prefs.setAutoHideIcon(false);
                auto.setChecked(false);
            }
            toast(checked ? "已隐藏，可从 LSPosed 管理器打开" : "已恢复桌面图标");
        });
        page.addView(hideIconSwitch);
        hint(page, "隐藏后打开方式：LSPosed 管理器 → 模块 → HTTP 共享 → 设置按钮，或点通知栏。"
                + "作用域勾选「系统框架」可避免部分桌面生成“应用详情”替身图标（需重启）。");

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

    private void editPassword() {
        EditText e = edit(prefs.hasPassword() ? "新密码（留空=取消密码）" : "访问密码", "");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this).setTitle("访问密码").setView(wrapDialog(e))
                .setPositiveButton("保存", (d, w) -> {
                    String pw = e.getText().toString();
                    if (!pw.isEmpty() && pw.length() < 4) {
                        toast("密码至少 4 位");
                        return;
                    }
                    new Thread(() -> {
                        prefs.setPassword(pw);
                        runOnUiThread(() -> {
                            toast(pw.isEmpty() ? "已取消密码" : "密码已设置");
                            refresh();
                        });
                    }).start();
                }).setNegativeButton("取消", null).show();
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
        if (passwordState != null) {
            passwordState.setText(prefs.hasPassword() ? "访问密码：已设置" : "访问密码：未设置");
        }
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

    private void autoHideIcon() {
        if (prefs.autoHideIcon() && ModuleStatus.isActive() && !isIconHidden()) {
            setIconHidden(true);
            toast("模块已生效，已自动隐藏桌面图标（可在设置中恢复）");
            if (hideIconSwitch != null) {
                hideIconSwitch.setChecked(true);
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
