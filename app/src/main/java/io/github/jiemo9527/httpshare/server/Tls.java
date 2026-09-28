package io.github.jiemo9527.httpshare.server;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * HTTPS 加密传输：首次使用时生成 RSA-2048 自签名证书（手写最小 DER 编码，无第三方依赖），
 * 保存为应用私有目录下的 PKCS12。
 */
public final class Tls {
    private static final char[] PASS = "httpshare".toCharArray();
    private static final String ALIAS = "httpshare";

    private Tls() {
    }

    public static SSLContext context(File dir) throws Exception {
        KeyStore ks = load(new File(dir, "tls.p12"));
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASS);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        return ctx;
    }

    /** 证书 SHA-256 指纹，供浏览器警告时人工核对 */
    public static String fingerprint(File dir) {
        try {
            KeyStore ks = load(new File(dir, "tls.p12"));
            byte[] d = MessageDigest.getInstance("SHA-256").digest(ks.getCertificate(ALIAS).getEncoded());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) {
                if (i > 0) {
                    sb.append(i % 16 == 0 ? "\n" : ":");
                }
                sb.append(String.format("%02X", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "（生成失败：" + e.getMessage() + "）";
        }
    }

    private static synchronized KeyStore load(File f) throws Exception {
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
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        X509Certificate cert = selfSign(kp);
        ks.load(null, null);
        ks.setKeyEntry(ALIAS, kp.getPrivate(), PASS, new Certificate[]{cert});
        try (FileOutputStream out = new FileOutputStream(f)) {
            ks.store(out, PASS);
        }
        return ks;
    }

    // ------------------------------------------------------------------ DER

    private static X509Certificate selfSign(KeyPair kp) throws Exception {
        byte[] sha256Rsa = seq(oid(new int[]{1, 2, 840, 113549, 1, 1, 11}), new byte[]{0x05, 0x00});
        byte[] name = seq(set(seq(oid(new int[]{2, 5, 4, 3}),
                tlv(0x0C, "HttpShare".getBytes(StandardCharsets.UTF_8)))));
        long now = System.currentTimeMillis();
        byte[] validity = seq(time(new Date(now - 86_400_000L)),
                time(new Date(now + 3650L * 86_400_000L)));
        byte[] serial = tlv(0x02, new BigInteger(63, new SecureRandom()).add(BigInteger.ONE).toByteArray());
        byte[] version = tlv(0xA0, tlv(0x02, new byte[]{2}));
        byte[] tbs = seq(version, serial, sha256Rsa, name, validity, name, kp.getPublic().getEncoded());

        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
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
