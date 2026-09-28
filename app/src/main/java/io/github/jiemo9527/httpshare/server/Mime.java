package io.github.jiemo9527.httpshare.server;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class Mime {
    private static final Map<String, String> M = new HashMap<>();

    static {
        String[][] t = {
                {"txt", "text/plain; charset=utf-8"}, {"log", "text/plain; charset=utf-8"},
                {"md", "text/plain; charset=utf-8"}, {"json", "application/json"},
                {"xml", "text/xml"}, {"prop", "text/plain; charset=utf-8"},
                {"conf", "text/plain; charset=utf-8"}, {"ini", "text/plain; charset=utf-8"},
                {"html", "text/html; charset=utf-8"}, {"htm", "text/html; charset=utf-8"},
                {"css", "text/css"}, {"js", "text/javascript"},
                {"jpg", "image/jpeg"}, {"jpeg", "image/jpeg"}, {"png", "image/png"},
                {"gif", "image/gif"}, {"webp", "image/webp"}, {"bmp", "image/bmp"},
                {"heic", "image/heic"}, {"svg", "image/svg+xml"},
                {"mp4", "video/mp4"}, {"mkv", "video/x-matroska"}, {"webm", "video/webm"},
                {"mov", "video/quicktime"}, {"3gp", "video/3gpp"},
                {"mp3", "audio/mpeg"}, {"m4a", "audio/mp4"}, {"aac", "audio/aac"},
                {"flac", "audio/flac"}, {"wav", "audio/wav"}, {"ogg", "audio/ogg"},
                {"opus", "audio/ogg"}, {"amr", "audio/amr"},
                {"pdf", "application/pdf"}, {"zip", "application/zip"},
                {"apk", "application/vnd.android.package-archive"},
        };
        for (String[] p : t) {
            M.put(p[0], p[1]);
        }
    }

    static String of(String name) {
        int d = name.lastIndexOf('.');
        if (d < 0) {
            return "application/octet-stream";
        }
        String v = M.get(name.substring(d + 1).toLowerCase(Locale.ROOT));
        return v == null ? "application/octet-stream" : v;
    }

    private Mime() {
    }
}
