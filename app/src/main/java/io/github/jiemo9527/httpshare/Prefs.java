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

    // ---------------------------------------------------------------- 共享目录（只有一个）

    public static final String TYPE_INTERNAL = "internal";
    public static final String TYPE_SYSTEM = "system";

    public static String sdcard() {
        return Environment.getExternalStorageDirectory().getAbsolutePath();
    }

    /** 内部存储（普通权限，限 /sdcard 下）或系统位置（ROOT，任意路径），二选一 */
    public String shareType() {
        migrateShares();
        return TYPE_SYSTEM.equals(sp.getString("share_type", TYPE_INTERNAL)) ? TYPE_SYSTEM : TYPE_INTERNAL;
    }

    /** 每种类型各记住上次选的路径，切换类型时恢复 */
    public String sharePath(String type) {
        migrateShares();
        return TYPE_SYSTEM.equals(type)
                ? sp.getString("share_system", "/")
                : sp.getString("share_internal", sdcard());
    }

    public void setShare(String type, String path) {
        sp.edit().putString("share_type", type)
                .putString(TYPE_SYSTEM.equals(type) ? "share_system" : "share_internal", path).apply();
    }

    public void setShareType(String type) {
        sp.edit().putString("share_type", type).apply();
    }

    /** 服务端仍按列表处理（序号固定为 0），只返回当前选中的一个 */
    public List<Share> shares() {
        List<Share> out = new ArrayList<>();
        String type = shareType();
        String path = sharePath(type);
        out.add(new Share(displayName(type, path), path, TYPE_SYSTEM.equals(type)));
        return out;
    }

    public static String displayName(String type, String path) {
        String sd = sdcard();
        if (!TYPE_SYSTEM.equals(type) && (path.equals(sd) || path.equals(sd + "/"))) {
            return "内部存储";
        }
        if (path.equals("/")) {
            return "根目录";
        }
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return p.substring(p.lastIndexOf('/') + 1);
    }

    /** 0.3 及以前可配置多个共享：取第一个转换为新格式 */
    private void migrateShares() {
        if (sp.contains("share_type")) {
            return;
        }
        SharedPreferences.Editor e = sp.edit().putString("share_type", TYPE_INTERNAL);
        String raw = sp.getString("shares", null);
        if (raw != null) {
            try {
                JSONArray a = new JSONArray(raw);
                if (a.length() > 0) {
                    JSONObject o = a.getJSONObject(0);
                    boolean root = o.optBoolean("root");
                    e.putString("share_type", root ? TYPE_SYSTEM : TYPE_INTERNAL)
                            .putString(root ? "share_system" : "share_internal", o.optString("path"));
                }
            } catch (Exception ignored) {
            }
            e.remove("shares");
        }
        e.apply();
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

    /** 上传 / 新建文件夹 */
    public boolean allowUpload() {
        return sp.getBoolean("perm_upload", sp.getBoolean("write", false));
    }

    public void setAllowUpload(boolean b) {
        sp.edit().putBoolean("perm_upload", b).apply();
    }

    /** 改名 / 删除 / 覆盖已有文件 */
    public boolean allowModify() {
        return sp.getBoolean("perm_modify", sp.getBoolean("write", false));
    }

    public void setAllowModify(boolean b) {
        sp.edit().putBoolean("perm_modify", b).apply();
    }

    // ---------------------------------------------------------------- 访问密码

    private static final String PW_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final String KS_ALIAS = "httpshare_pw";

    /** 9–12 位随机密码，大小写字母与数字各至少一个（去掉 0/O、1/l/I 等易混字符） */
    public static String randomPassword() {
        SecureRandom r = new SecureRandom();
        int len = 9 + r.nextInt(4);
        while (true) {
            StringBuilder b = new StringBuilder(len);
            for (int i = 0; i < len; i++) {
                b.append(PW_CHARS.charAt(r.nextInt(PW_CHARS.length())));
            }
            String s = b.toString();
            if (s.matches(".*[A-Z].*") && s.matches(".*[a-z].*") && s.matches(".*[0-9].*")) {
                return s;
            }
        }
    }

    public boolean hasPassword() {
        return sp.getString("pw_hash", null) != null;
    }

    /**
     * 传 null 或空串表示取消密码。校验用加盐哈希；为了能在 App 内查看/复制，
     * 另存一份用 Android Keystore（AES-GCM，密钥不可导出）加密的密文。
     */
    public void setPassword(String pw) {
        cachedPw = null;
        if (pw == null || pw.isEmpty()) {
            sp.edit().remove("pw_hash").remove("pw_salt").remove("pw_enc").apply();
            return;
        }
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String s = Base64.encodeToString(salt, Base64.NO_WRAP);
        SharedPreferences.Editor e = sp.edit().putString("pw_salt", s).putString("pw_hash", hash(s, pw));
        String enc = encrypt(pw);
        if (enc != null) {
            e.putString("pw_enc", enc);
        } else {
            e.remove("pw_enc");
        }
        e.apply();
    }

    private static volatile String lastEnc;
    private static volatile String lastPlain;

    /** 已设置的明文密码；旧版本设置的（只有哈希）或解密失败返回 null */
    public String password() {
        String enc = sp.getString("pw_enc", null);
        if (!hasPassword() || enc == null) {
            return null;
        }
        if (enc.equals(lastEnc)) {
            return lastPlain;
        }
        String plain = decrypt(enc);
        lastPlain = plain;
        lastEnc = enc;
        return plain;
    }

    private volatile String cachedPw;
    private volatile String cachedHash;

    public boolean checkPassword(String pw) {
        String h = sp.getString("pw_hash", null);
        if (h == null) {
            return true;
        }
        if (pw == null) {
            return false;
        }
        // WebDAV 客户端每个请求都带 Basic 认证，缓存最近一次校验成功的结果，避免每次 2 万轮哈希
        String cp = cachedPw;
        if (cp != null && h.equals(cachedHash) && MessageDigest.isEqual(
                cp.getBytes(StandardCharsets.UTF_8), pw.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        boolean ok = MessageDigest.isEqual(
                h.getBytes(StandardCharsets.US_ASCII),
                hash(sp.getString("pw_salt", ""), pw).getBytes(StandardCharsets.US_ASCII));
        if (ok) {
            cachedHash = h;
            cachedPw = pw;
        }
        return ok;
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

    private static javax.crypto.SecretKey key() throws Exception {
        java.security.KeyStore ks = java.security.KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KS_ALIAS)) {
            return ((java.security.KeyStore.SecretKeyEntry) ks.getEntry(KS_ALIAS, null)).getSecretKey();
        }
        javax.crypto.KeyGenerator kg = javax.crypto.KeyGenerator.getInstance(
                android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new android.security.keystore.KeyGenParameterSpec.Builder(KS_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
                        | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    private static String encrypt(String pw) {
        try {
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, key());
            byte[] ct = c.doFinal(pw.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    private static String decrypt(String enc) {
        try {
            int i = enc.indexOf(':');
            byte[] iv = Base64.decode(enc.substring(0, i), Base64.NO_WRAP);
            byte[] ct = Base64.decode(enc.substring(i + 1), Base64.NO_WRAP);
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            c.init(javax.crypto.Cipher.DECRYPT_MODE, key(), new javax.crypto.spec.GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
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

    // ---------------------------------------------------------------- 同步查阅

    /** App 内浏览时是否向 /showme 广播当前目录 */
    public boolean showmeSync() {
        return sp.getBoolean("showme", true);
    }

    public void setShowmeSync(boolean b) {
        sp.edit().putBoolean("showme", b).apply();
    }

    // ---------------------------------------------------------------- WebDAV

    /** 在 /dav/ 提供 WebDAV（可挂载为网络盘），权限与网页端相同 */
    public boolean webdav() {
        return sp.getBoolean("webdav", true);
    }

    public void setWebdav(boolean b) {
        sp.edit().putBoolean("webdav", b).apply();
    }
}
