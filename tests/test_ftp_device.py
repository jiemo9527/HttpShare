"""Real-device plaintext-FTP smoke and security regression probe.

Usage (with the app already running in FTP mode):
  python tests/test_ftps_device.py --host 127.0.0.1 --port 12121 --password "$env:HS_FTP_PASSWORD"

The probe creates only ``--root`` and removes its own files. It never logs the password.
"""
import argparse
import ftplib
import hashlib
import io
import socket
import sys
import time
import unittest


OPTIONS = argparse.ArgumentParser(add_help=False)
OPTIONS.add_argument("--host")
OPTIONS.add_argument("--port", type=int)
OPTIONS.add_argument("--password")
OPTIONS.add_argument("--root", default="agent-ftp-测试")
OPTIONS.add_argument("--no-modify", action="store_true")
OPTIONS, unittest_args = OPTIONS.parse_known_args()
sys.argv = [sys.argv[0]] + unittest_args


class FtpDeviceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not (OPTIONS.host and OPTIONS.port and OPTIONS.password):
            raise unittest.SkipTest("requires --host, --port, and --password for a real device")
        cls.args = OPTIONS
        cls.payload = ("unicode-上传-✓\n".encode("utf-8")) * 4096

    def ftp(self):
        c = ftplib.FTP(timeout=15)
        c.connect(self.args.host, self.args.port)
        c.login("HttpShare-device-probe", self.args.password)
        return c

    def test_plain_login_and_tls_commands_are_refused(self):
        s = socket.create_connection((self.args.host, self.args.port), timeout=15)
        try:
            self.assertTrue(s.recv(512).startswith(b"220"))
            s.sendall(b"AUTH TLS\r\n")
            self.assertTrue(s.recv(512).startswith(b"502"))
            s.sendall(b"USER arbitrary-user\r\n")
            self.assertTrue(s.recv(512).startswith(b"331"))
            s.sendall(("PASS " + self.args.password + "\r\n").encode("utf-8"))
            self.assertTrue(s.recv(512).startswith(b"230"))
            s.sendall(b"PBSZ 0\r\n")
            self.assertTrue(s.recv(512).startswith(b"502"))
            s.sendall(b"PROT P\r\n")
            self.assertTrue(s.recv(512).startswith(b"502"))
        finally:
            s.close()

    def test_pasv_epsv_listing_unicode_lifecycle_and_path_safety(self):
        if self.args.no_modify:
            self.skipTest("lifecycle needs modify permission")
        c = self.ftp()
        root = self.args.root
        uploaded = root + "/unicode-上传-✓.bin"
        moved = root + "/renamed.bin"
        try:
            c.sendcmd("PASV")
            self.assertIsInstance(list(c.mlsd()), list)
            self.assertIsInstance(c.nlst(), list)
            epsv = c.sendcmd("EPSV")
            data_port = int(epsv.rsplit("|", 2)[1])
            with socket.create_connection((self.args.host, data_port), timeout=15) as data:
                c.putcmd("NLST")
                self.assertTrue(c.getresp().startswith("150"))
                data.recv(4096)  # An empty dedicated directory is a valid zero-byte data transfer.
            self.assertTrue(c.getresp().startswith("226"))
            c.mkd(root)
            c.storbinary("STOR " + uploaded, io.BytesIO(self.payload))
            got = bytearray()
            c.retrbinary("RETR " + uploaded, got.extend)
            self.assertEqual(hashlib.sha256(got).digest(), hashlib.sha256(self.payload).digest())
            tail = bytearray()
            c.retrbinary("RETR " + uploaded, tail.extend, rest=17)
            self.assertEqual(bytes(tail), self.payload[17:])
            c.rename(uploaded, moved)
            with self.assertRaises(ftplib.error_perm) as e:
                c.rmd("/")
            self.assertTrue(str(e.exception).startswith("550"))
            c.cwd("/")
            c.cwd("../../../../")
            self.assertEqual(c.pwd(), "/")
            c.delete(moved)
            c.rmd(root)
        finally:
            try:
                c.quit()
            except (OSError, EOFError, ftplib.Error):
                c.close()

    def test_control_disconnect_releases_pending_passive_socket(self):
        c = self.ftp()
        c.sendcmd("PASV")
        c.close()
        time.sleep(0.2)
        check = self.ftp()
        try:
            self.assertTrue(check.sendcmd("PASV").startswith("227"))
        finally:
            check.quit()

    def test_overwrite_and_rename_need_modify_permission(self):
        if not self.args.no_modify:
            self.skipTest("run this check with --no-modify against a probe-owned existing file")
        c = self.ftp()
        try:
            before = bytearray()
            c.retrbinary("RETR " + self.args.root + "/existing.bin", before.extend)
            with self.assertRaises(ftplib.error_perm) as e:
                c.storbinary("STOR " + self.args.root + "/existing.bin", io.BytesIO(b"must-not-overwrite"))
            self.assertTrue(str(e.exception).startswith("553"))
            after = bytearray()
            c.retrbinary("RETR " + self.args.root + "/existing.bin", after.extend)
            self.assertEqual(bytes(after), bytes(before))
            with self.assertRaises(ftplib.error_perm) as e:
                c.sendcmd("RNFR " + self.args.root + "/existing.bin")
            self.assertTrue(str(e.exception).startswith("550"))
        finally:
            c.quit()


if __name__ == "__main__":
    unittest.main()
