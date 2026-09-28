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

    def test_icon_alias_and_auto_hide(self):
        manifest = read(MAIN / "AndroidManifest.xml")
        self.assertRegex(manifest, r'activity-alias[^>]*\.Launcher')
        self.assertIn("MODULE_SETTINGS", manifest)
        src = read(JAVA / "MainActivity.java")
        self.assertIn("ModuleStatus.isActive()", src)
        self.assertIn("autoHideIcon()", src)
        self.assertIn('ModuleStatus.class.getName()', read(JAVA / "XposedInit.java"))

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
