package io.github.jiemo9527.httpshare.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.TreeSet;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * HTTPS：首次使用时在本机生成一个「本地根证书」（每台手机不同），再用它按当前局域网 IP 签发服务器证书。
 *
 * <p>根证书带关键的名称约束（Name Constraints）：只能为内网 / 回环 IP 和 localhost 签发。
 * 支持名称约束验证的客户端会据此拒绝公网名称；不要把该本地 CA 当成公网 TLS 身份。
 * 服务器证书 SAN 覆盖启动服务时的全部局域网 IP；IP 变化后重启服务会自动重新签发（复用密钥与根证书）。
 *
 * <p>无第三方依赖，DER 编码手写。
 */
public final class Tls {
    private static final char[] PASS = "httpshare".toCharArray();
    private static final String ALIAS = "httpshare";
    private static final long DAY = 86_400_000L;

    private Tls() {
    }

    /** @param hosts 证书需要覆盖的局域网 IP / 主机名；另自动加入 127.0.0.1、::1、localhost */
    public static SSLContext context(File dir, List<String> hosts) throws Exception {
        KeyStore ks = leaf(dir, hosts);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASS);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        return ctx;
    }

    /** 本地根证书，供电脑一次性加入信任 */
    public static X509Certificate caCertificate(File dir) throws Exception {
        return (X509Certificate) ca(dir).getCertificate(ALIAS);
    }

    /** 根证书与当前服务器证书的 SHA-256 指纹，供浏览器警告时人工核对 */
    public static String fingerprint(File dir) {
        try {
            StringBuilder sb = new StringBuilder("根证书 SHA-256\n")
                    .append(hex(sha256(caCertificate(dir).getEncoded()), true));
            File lf = new File(dir, "tls.p12");
            if (lf.exists()) {
                KeyStore ks = KeyStore.getInstance("PKCS12");
                try (FileInputStream in = new FileInputStream(lf)) {
                    ks.load(in, PASS);
                }
                X509Certificate c = (X509Certificate) ks.getCertificate(ALIAS);
                if (c != null && c.getSubjectAlternativeNames() != null) {
                    sb.append("\n服务器证书 SHA-256\n").append(hex(sha256(c.getEncoded()), true))
                            .append("\n覆盖地址：").append(String.join("、", sans(c)));
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "（生成失败：" + e.getMessage() + "）";
        }
    }

    public static byte[] sha256(byte[] b) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(b);
    }

    /** Windows 证书库里的 Thumbprint（SHA-1 大写十六进制） */
    public static String thumbprint(X509Certificate c) throws Exception {
        return hex(MessageDigest.getInstance("SHA-1").digest(c.getEncoded()), false);
    }

    private static String hex(byte[] d, boolean pretty) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i++) {
            if (pretty && i > 0) {
                sb.append(i % 16 == 0 ? "\n" : ":");
            }
            sb.append(String.format(Locale.ROOT, "%02X", d[i]));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 根证书

    private static synchronized KeyStore ca(File dir) throws Exception {
        File f = new File(dir, "tls-ca.p12");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                ks.load(in, PASS);
                if (ks.containsAlias(ALIAS)) {
                    return ks;
                }
            } catch (Exception ignored) {
            }
        }
        KeyPair kp = rsa();
        byte[] id = new byte[4];
        new SecureRandom().nextBytes(id);
        byte[] name = name("HttpShare Local CA " + hex(id, false));
        long now = System.currentTimeMillis();
        byte[] exts = extensions(
                ext(new int[]{2, 5, 29, 19}, true, seq(tlv(0x01, new byte[]{(byte) 0xFF}), tlv(0x02, new byte[]{0}))),
                ext(new int[]{2, 5, 29, 15}, true, tlv(0x03, new byte[]{1, 0x06})),     // keyCertSign | cRLSign
                ext(new int[]{2, 5, 29, 30}, true, nameConstraints()),
                ext(new int[]{2, 5, 29, 14}, false, tlv(0x04, keyId(kp.getPublic()))));
        X509Certificate cert = sign(name, name, kp.getPublic(), kp.getPrivate(), now - DAY, now + 3650L * DAY, exts);
        ks.load(null, null);
        ks.setKeyEntry(ALIAS, kp.getPrivate(), PASS, new Certificate[]{cert});
        try (FileOutputStream out = new FileOutputStream(f)) {
            ks.store(out, PASS);
        }
        return ks;
    }

    /** 只允许：10/8、172.16/12、192.168/16、100.64/10、127/8、169.254/16、fc00::/7、fe80::/10、::1、localhost */
    private static byte[] nameConstraints() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int[][] v4 = {{10, 0, 0, 0, 8}, {172, 16, 0, 0, 12}, {192, 168, 0, 0, 16}, {100, 64, 0, 0, 10},
                {127, 0, 0, 0, 8}, {169, 254, 0, 0, 16}};
        for (int[] n : v4) {
            byte[] a = new byte[8];
            for (int i = 0; i < 4; i++) {
                a[i] = (byte) n[i];
            }
            mask(a, 4, 4, n[4]);
            write(b, seq(tlv(0x87, a)));
        }
        int[][] v6 = {{0xfc, 7}, {0xfe, 0x80, 10}};
        for (int[] n : v6) {
            byte[] a = new byte[32];
            for (int i = 0; i < n.length - 1; i++) {
                a[i] = (byte) n[i];
            }
            mask(a, 16, 16, n[n.length - 1]);
            write(b, seq(tlv(0x87, a)));
        }
        byte[] lo6 = new byte[32];
        lo6[15] = 1;
        mask(lo6, 16, 16, 128);
        write(b, seq(tlv(0x87, lo6)));
        write(b, seq(tlv(0x82, "localhost".getBytes(StandardCharsets.US_ASCII))));
        return seq(tlv(0xA0, b.toByteArray()));
    }

    private static void mask(byte[] a, int off, int len, int bits) {
        for (int i = 0; i < len; i++) {
            int n = Math.max(0, Math.min(8, bits - i * 8));
            a[off + i] = (byte) (0xFF << (8 - n));
        }
    }

    // ------------------------------------------------------------------ 服务器证书

    private static synchronized KeyStore leaf(File dir, List<String> hosts) throws Exception {
        KeyStore caKs = ca(dir);
        X509Certificate caCert = (X509Certificate) caKs.getCertificate(ALIAS);
        PrivateKey caKey = (PrivateKey) caKs.getKey(ALIAS, PASS);

        TreeSet<String> want = new TreeSet<>();
        want.add(normalize("127.0.0.1"));
        want.add(normalize("::1"));
        want.add("localhost");
        for (String h : hosts) {
            if (h != null && !h.isEmpty()) {
                want.add(normalize(h));
            }
        }

        File f = new File(dir, "tls.p12");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        PrivateKey key = null;
        PublicKey pub = null;
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                ks.load(in, PASS);
                Certificate[] chain = ks.getCertificateChain(ALIAS);
                key = (PrivateKey) ks.getKey(ALIAS, PASS);
                if (key != null && chain != null && chain.length > 0) {
                    X509Certificate c = (X509Certificate) chain[0];
                    pub = c.getPublicKey();
                    boolean sameCa = chain.length == 2 && chain[1].equals(caCert);
                    boolean fresh = c.getNotAfter().getTime() - System.currentTimeMillis() > 30 * DAY;
                    if (sameCa && fresh && c.getSubjectAlternativeNames() != null
                            && new TreeSet<>(sans(c)).equals(want)) {
                        return ks;
                    }
                }
            } catch (Exception e) {
                key = null;
                pub = null;
            }
        }
        if (key == null || pub == null) {
            KeyPair kp = rsa();
            key = kp.getPrivate();
            pub = kp.getPublic();
        }

        ByteArrayOutputStream san = new ByteArrayOutputStream();
        for (String h : want) {
            byte[] ip = ipBytes(h);
            write(san, ip != null ? tlv(0x87, ip) : tlv(0x82, h.getBytes(StandardCharsets.US_ASCII)));
        }
        long now = System.currentTimeMillis();
        byte[] exts = extensions(
                ext(new int[]{2, 5, 29, 19}, true, seq()),
                ext(new int[]{2, 5, 29, 15}, true, tlv(0x03, new byte[]{5, (byte) 0xA0})),  // digitalSignature | keyEncipherment
                ext(new int[]{2, 5, 29, 37}, false, seq(oid(new int[]{1, 3, 6, 1, 5, 5, 7, 3, 1}))),
                ext(new int[]{2, 5, 29, 17}, true, tlv(0x30, san.toByteArray())),
                ext(new int[]{2, 5, 29, 14}, false, tlv(0x04, keyId(pub))),
                ext(new int[]{2, 5, 29, 35}, false, seq(tlv(0x80, keyId(caCert.getPublicKey())))));
        // 主题留空，身份全部在（关键的）SAN 里：避免出现名称约束未覆盖的名称类型
        X509Certificate cert = sign(seq(), caCert.getSubjectX500Principal().getEncoded(), pub, caKey,
                now - DAY, now + 397L * DAY, exts);
        ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(ALIAS, key, PASS, new Certificate[]{cert, caCert});
        try (FileOutputStream out = new FileOutputStream(f)) {
            ks.store(out, PASS);
        }
        return ks;
    }

    private static String normalize(String h) {
        byte[] ip = ipBytes(h);
        if (ip == null) {
            return h.toLowerCase(Locale.ROOT);
        }
        try {
            String s = InetAddress.getByAddress(ip).getHostAddress();
            int pct = s.indexOf('%');
            return pct >= 0 ? s.substring(0, pct) : s;
        } catch (Exception e) {
            return h;
        }
    }

    /** 仅解析字面 IP（不做 DNS 查询） */
    private static byte[] ipBytes(String h) {
        String s = h.startsWith("[") && h.endsWith("]") ? h.substring(1, h.length() - 1) : h;
        int pct = s.indexOf('%');
        if (pct >= 0) {
            s = s.substring(0, pct);
        }
        if (s.matches("\\d{1,3}(\\.\\d{1,3}){3}") || (s.contains(":") && s.matches("[0-9A-Fa-f:.]+"))) {
            try {
                return InetAddress.getByName(s).getAddress();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static List<String> sans(X509Certificate c) throws Exception {
        List<String> out = new ArrayList<>();
        Collection<List<?>> all = c.getSubjectAlternativeNames();
        if (all != null) {
            for (List<?> e : all) {
                out.add(normalize(String.valueOf(e.get(1))));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ DER

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    private static byte[] name(String cn) {
        return seq(set(seq(oid(new int[]{2, 5, 4, 3}), tlv(0x0C, cn.getBytes(StandardCharsets.UTF_8)))));
    }

    /** RFC 5280 方法 1：公钥 BIT STRING 内容的 SHA-1 */
    private static byte[] keyId(PublicKey k) throws Exception {
        byte[] spki = k.getEncoded();
        int[] p = {0};
        header(spki, p);            // SubjectPublicKeyInfo SEQUENCE
        int alg = header(spki, p);  // AlgorithmIdentifier
        p[0] += alg;
        int len = header(spki, p);  // BIT STRING
        byte[] bits = new byte[len - 1];
        System.arraycopy(spki, p[0] + 1, bits, 0, bits.length);
        return MessageDigest.getInstance("SHA-1").digest(bits);
    }

    /** 读 TLV 头（tag + 长度），p 移到内容起点，返回内容长度 */
    private static int header(byte[] d, int[] p) {
        p[0]++;
        int len = d[p[0]++] & 0xFF;
        if (len >= 0x80) {
            int n = len & 0x7F;
            len = 0;
            for (int i = 0; i < n; i++) {
                len = (len << 8) | (d[p[0]++] & 0xFF);
            }
        }
        return len;
    }

    private static byte[] ext(int[] id, boolean critical, byte[] value) {
        return critical
                ? seq(oid(id), tlv(0x01, new byte[]{(byte) 0xFF}), tlv(0x04, value))
                : seq(oid(id), tlv(0x04, value));
    }

    private static byte[] extensions(byte[]... exts) {
        return tlv(0xA3, seq(exts));
    }

    private static X509Certificate sign(byte[] subject, byte[] issuer, PublicKey pub, PrivateKey signer,
                                        long from, long to, byte[] exts) throws Exception {
        byte[] sha256Rsa = seq(oid(new int[]{1, 2, 840, 113549, 1, 1, 11}), new byte[]{0x05, 0x00});
        byte[] validity = seq(time(new Date(from)), time(new Date(to)));
        byte[] serial = tlv(0x02, new BigInteger(63, new SecureRandom()).add(BigInteger.ONE).toByteArray());
        byte[] version = tlv(0xA0, tlv(0x02, new byte[]{2}));
        byte[] tbs = seq(version, serial, sha256Rsa, issuer, validity, subject, pub.getEncoded(), exts);
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(signer);
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        byte[] der = seq(tbs, sha256Rsa, tlv(0x03, bits));
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    private static byte[] time(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(d).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] oid(int[] parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(parts[0] * 40 + parts[1]);
        for (int i = 2; i < parts.length; i++) {
            int v = parts[i];
            byte[] tmp = new byte[5];
            int n = 0;
            do {
                tmp[n++] = (byte) (v & 0x7F);
                v >>>= 7;
            } while (v != 0);
            for (int j = n - 1; j >= 0; j--) {
                b.write(tmp[j] | (j > 0 ? 0x80 : 0));
            }
        }
        return tlv(0x06, b.toByteArray());
    }

    private static byte[] seq(byte[]... items) {
        return tlv(0x30, concat(items));
    }

    private static byte[] set(byte[]... items) {
        return tlv(0x31, concat(items));
    }

    private static byte[] concat(byte[]... items) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] i : items) {
            b.write(i, 0, i.length);
        }
        return b.toByteArray();
    }

    private static void write(ByteArrayOutputStream b, byte[] v) {
        b.write(v, 0, v.length);
    }

    private static byte[] tlv(int tag, byte[] v) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(tag);
        int len = v.length;
        if (len < 0x80) {
            b.write(len);
        } else {
            int n = len > 0xFFFF ? 3 : len > 0xFF ? 2 : 1;
            b.write(0x80 | n);
            for (int i = n - 1; i >= 0; i--) {
                b.write((len >> (8 * i)) & 0xFF);
            }
        }
        b.write(v, 0, v.length);
        return b.toByteArray();
    }
}
