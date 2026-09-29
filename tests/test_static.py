"""Static checks for wiring that has no Android unit-test harness."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main"
JAVA = MAIN / "java/io/github/jiemo9527/httpshare"


def read(p):
    return Path(p).read_text(encoding="utf-8")


class StaticTest(unittest.TestCase):
    def test_about_opens_github(self):
        self.assertIn('https://github.com/jiemo9527/HttpShare', read(JAVA / "Prefs.java"))
        src = read(JAVA / "MainActivity.java")
        self.assertIn('section(page, "关于")', src)
        self.assertIn("Intent.ACTION_VIEW", src)
        self.assertIn("ActivityNotFoundException", src)

    def test_icon_alias_no_auto_hide(self):
        manifest = read(MAIN / "AndroidManifest.xml")
        self.assertRegex(manifest, r'activity-alias[^>]*\.Launcher')
        self.assertIn("MODULE_SETTINGS", manifest)
        src = read(JAVA / "MainActivity.java")
        self.assertIn("ModuleStatus.isActive()", src)
        # 0.3: icon is only hidden manually; old auto-hide is gone and restored once
        self.assertNotIn("autoHideIcon", src + read(JAVA / "Prefs.java"))
        self.assertIn("restoreIconOnce()", src)
        self.assertIn('ModuleStatus.class.getName()', read(JAVA / "XposedInit.java"))

    def test_split_permissions_and_lockout(self):
        srv = read(JAVA / "server/HttpServer.java")
        self.assertIn("allowUpload()", srv)
        self.assertIn("allowModify()", srv)
        self.assertNotIn("allowWrite", srv + read(JAVA / "Prefs.java"))
        self.assertRegex(srv, r"MAX_FAILS\s*=\s*3\b")
        self.assertRegex(srv, r"LOCK_MS\s*=\s*2L\s*\*\s*3600\s*\*\s*1000\s*;")
        self.assertRegex(read(JAVA / "ShareService.java"), r"MAX_LOG\s*=\s*3000\b")

    def test_showme_uses_polling(self):
        # Cloudflare quick tunnels buffer text/event-stream; must stay request/response
        page = read(MAIN / "assets/showme.html")
        self.assertNotIn("EventSource", page)
        self.assertIn("/api/showme?v=", page)
        self.assertNotIn('"Content-Type: text/event-stream', read(JAVA / "server/ShowMe.java") + read(JAVA / "server/HttpServer.java"))

    def test_single_share_and_password(self):
        prefs = read(JAVA / "Prefs.java")
        self.assertIn("TYPE_INTERNAL", prefs)
        self.assertIn("TYPE_SYSTEM", prefs)
        self.assertIn("migrateShares()", prefs)
        # 9-12 chars, upper/lower/digit, plaintext only via Android Keystore
        self.assertIn("9 + r.nextInt(4)", prefs)
        self.assertIn("AndroidKeyStore", prefs)
        self.assertIn("AES/GCM/NoPadding", prefs)
        main = read(JAVA / "MainActivity.java")
        self.assertNotIn("editShare", main)
        self.assertIn("IS_SENSITIVE", main)

    def test_zip_and_webdav(self):
        srv = read(JAVA / "server/HttpServer.java")
        self.assertIn('case "/api/zip":', srv)
        self.assertIn("e.special || (e.link && (!rootShare || e.dir))", srv)
        # WebDAV must only accept Basic auth (never the web cookie) -> no CSRF via cookies
        dav = srv[srv.index("private boolean dav(Req r"):srv.index("private static String hrefOf")]
        self.assertIn("basicAuth(r)", dav)
        self.assertNotIn("authed(r)", dav)
        self.assertIn("requireModify()", dav)
        self.assertIn("requireUpload()", dav)
        self.assertIn("SIG_END64", read(JAVA / "server/Zip.java"))
        web = read(MAIN / "assets/web.html")
        self.assertIn("/api/zip?p=", web)

    def test_windows_mount_script(self):
        srv = read(JAVA / "server/HttpServer.java")
        self.assertIn('case "/api/mount.bat":', srv)
        # Host header goes into the script: must be validated
        self.assertIn("SAFE_HOST.matcher(host).matches()", srv)
        raw = (MAIN / "assets/mount.bat").read_bytes()
        marker = raw.rindex(b"##PS-BEGIN")
        # cmd.exe mis-parses multi-byte lines: the batch part must be pure ASCII
        self.assertTrue(all(b < 128 for b in raw[:marker]))
        self.assertIn(b"LastIndexOf", raw[:marker])
        bat = raw.decode("utf-8")
        for ph in ("{URL}", "{NEED_BASIC}", "{UNC}", "{PERSIST}", "{NOTE}"):
            self.assertIn(ph, bat)
        self.assertIn("Read-Host", bat)              # password typed at runtime, never embedded
        self.assertIn("TrustFailure", bat)           # untrusted HTTPS explained, not a wrong password
        # LAN HTTPS: CA is installed only after its thumbprint matches the one baked into the script
        self.assertIn("{CA_THUMB}", bat)
        self.assertIn("$ca.Thumbprint -ne $CaThumb", bat)
        self.assertIn("-Verb RunAs", bat)
        # repeated wrong password from auto-retrying clients counts once
        self.assertIn("f[2] == digest", srv)

    def test_tls_name_constrained_ca(self):
        tls = read(JAVA / "server/Tls.java")
        # CA must carry critical Name Constraints limited to private ranges
        self.assertIn("ext(new int[]{2, 5, 29, 30}, true, nameConstraints())", tls)
        self.assertIn("{192, 168, 0, 0, 16}", tls)
        self.assertIn("ext(new int[]{2, 5, 29, 17}, true", tls)   # SAN critical, empty subject
        svc = read(JAVA / "ShareService.java")
        self.assertIn("Tls.context(getFilesDir(), allAddresses())", svc)
        self.assertIn('sp.getBoolean("https", true)', read(JAVA / "Prefs.java"))

    def test_log_persisted(self):
        svc = read(JAVA / "ShareService.java")
        self.assertIn('"log.txt"', svc)
        self.assertRegex(svc, r"MAX_LOG\s*=\s*3000\b")
        # only the explicit clear button empties the log
        main = read(JAVA / "MainActivity.java")
        self.assertEqual(main.count("ShareService.clearLogs()"), 1)
        self.assertEqual(svc.count("LOG.clear()"), 1)

    def test_xposed_entry(self):
        self.assertEqual(read(MAIN / "assets/xposed_init").strip(),
                         "io.github.jiemo9527.httpshare.XposedInit")

    def test_root_paths_are_quoted(self):
        src = read(JAVA / "server/RootBackend.java")
        # every su command that takes a path must go through q()
        for m in re.finditer(r'"(?:exec )?(?:cat|tail -c \+[^"]*|mkdir -p|rm -rf --|rm -f|stat -L -c " \+ FMT \+ ")\s*"\s*\+\s*(\w+)', src):
            self.assertTrue(m.group(1) == "q", m.group(0))

    def test_mutations_require_header(self):
        src = read(JAVA / "server/HttpServer.java")
        self.assertIn('"1".equals(r.h("x-hs"))', src)
        self.assertIn("SameSite=Strict", src)
        self.assertIn("Content-Security-Policy: sandbox", src)


if __name__ == "__main__":
    unittest.main()
