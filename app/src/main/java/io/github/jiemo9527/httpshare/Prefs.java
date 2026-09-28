package io.github.jiemo9527.httpshare;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** 所有配置都存在 SharedPreferences，Activity 与 Service 同进程共享。 */
public final class Prefs {

    public static final String GITHUB_URL = "https://github.com/jiemo9527/HttpShare";

    public static final class Share {
        public String name;
        public String path;
        public boolean root;

        public Share(String name, String path, boolean root) {
            this.name = name;
            this.path = path;
            this.root = root;
        }
    }

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("config", Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- 共享目录

    public List<Share> shares() {
        List<Share> out = new ArrayList<>();
        String raw = sp.getString("shares", null);
        if (raw == null) {
            out.add(new Share("内部存储",
                    Environment.getExternalStorageDirectory().getAbsolutePath(), false));
            return out;
        }
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Share(o.optString("name"), o.optString("path"), o.optBoolean("root")));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public void setShares(List<Share> list) {
        JSONArray a = new JSONArray();
        try {
            for (Share s : list) {
                a.put(new JSONObject().put("name", s.name).put("path", s.path).put("root", s.root));
            }
        } catch (Exception ignored) {
        }
        sp.edit().putString("shares", a.toString()).apply();
    }

    // ---------------------------------------------------------------- 网络

    public int port() {
        return sp.getInt("port", 8080);
    }

    public void setPort(int p) {
        sp.edit().putInt("port", p).apply();
    }

    public boolean https() {
        return sp.getBoolean("https", false);
    }

    public void setHttps(boolean b) {
        sp.edit().putBoolean("https", b).apply();
    }

    public boolean allowWrite() {
        return sp.getBoolean("write", false);
    }

    public void setAllowWrite(boolean b) {
        sp.edit().putBoolean("write", b).apply();
    }

    // ---------------------------------------------------------------- 访问密码

    public boolean hasPassword() {
        return sp.getString("pw_hash", null) != null;
    }

    /** 传 null 或空串表示取消密码。仅保存加盐 SHA-256，不保存明文。 */
    public void setPassword(String pw) {
        if (pw == null || pw.isEmpty()) {
            sp.edit().remove("pw_hash").remove("pw_salt").apply();
            return;
        }
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String s = Base64.encodeToString(salt, Base64.NO_WRAP);
        sp.edit().putString("pw_salt", s).putString("pw_hash", hash(s, pw)).apply();
    }

    public boolean checkPassword(String pw) {
        String h = sp.getString("pw_hash", null);
        if (h == null) {
            return true;
        }
        if (pw == null) {
            return false;
        }
        return MessageDigest.isEqual(
                h.getBytes(StandardCharsets.US_ASCII),
                hash(sp.getString("pw_salt", ""), pw).getBytes(StandardCharsets.US_ASCII));
    }

    private static String hash(String salt, String pw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = (salt + ":" + pw).getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < 20000; i++) {
                d = md.digest(d);
            }
            return Base64.encodeToString(d, Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- 外网

    /** Remote.MODE_OFF / MODE_AUTO(公网 IPv4 优先，否则 Cloudflare) / MODE_TUNNEL(总是 Cloudflare) */
    public int remoteMode() {
        return sp.getInt("remote", 0);
    }

    public void setRemoteMode(int m) {
        sp.edit().putInt("remote", m).apply();
    }

    // ---------------------------------------------------------------- 图标

    /** 模块生效时自动隐藏桌面图标（默认开） */
    public boolean autoHideIcon() {
        return sp.getBoolean("auto_hide", true);
    }

    public void setAutoHideIcon(boolean b) {
        sp.edit().putBoolean("auto_hide", b).apply();
    }
}
