"""开发者后台核心逻辑的回归测试，不需要网络或第三方依赖。"""

import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

from server import server as app_server


class PasswordTests(unittest.TestCase):
    def test_pbkdf2_password_round_trip(self):
        encoded = app_server.encode_password("一个足够长的测试密码", iterations=1_000)
        self.assertTrue(app_server.verify_password("一个足够长的测试密码", encoded))
        self.assertFalse(app_server.verify_password("错误密码", encoded))
        self.assertFalse(app_server.verify_password("任意密码", "损坏的哈希"))

    def test_password_changed_notice_is_escaped_and_visible(self):
        page = app_server.login_page(notice="密码已修改 <成功>")
        self.assertIn("密码已修改 &lt;成功&gt;", page)


class AnalyticsStoreTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.database = Path(self.temp_dir.name) / "analytics.sqlite3"
        self.store = app_server.AnalyticsStore(self.database, b"test-telemetry-key")

    def test_heartbeat_is_anonymous_and_deduplicated_per_day(self):
        install_id = "6dbb4120-3748-4a8c-b5cf-cfa6f508f996"
        self.store.record_heartbeat(install_id, 21, "0.14.0")
        self.store.record_heartbeat(install_id, 22, "0.15.0")

        stats = self.store.dashboard_stats(22)
        self.assertEqual(1, stats["total_users"])
        self.assertEqual(1, stats["today_active"])
        self.assertEqual(1, stats["latest_installed"])

        # 数据库不得出现客户端发送来的原始 UUID。
        self.assertNotIn(install_id, self.database.read_bytes().decode("latin-1"))

    def test_stable_device_digest_is_deduplicated_and_download_is_not_a_user(self):
        device_digest = "a" * 64
        self.store.record_download(22, device_digest, "ignored")
        before_open = self.store.dashboard_stats(22)
        self.assertEqual(0, before_open["total_users"])
        self.assertEqual(0, before_open["today_active"])

        self.store.record_heartbeat(device_digest, 22, "0.15.0")
        self.store.record_heartbeat(device_digest, 22, "0.15.0")
        after_open = self.store.dashboard_stats(22)
        self.assertEqual(1, after_open["total_users"])
        self.assertEqual(1, after_open["today_active"])

    def test_download_tracks_requests_and_unique_installations_separately(self):
        first = "6dbb4120-3748-4a8c-b5cf-cfa6f508f996"
        second = "49416366-ef0b-431f-8135-965ca036e212"
        self.store.record_download(22, first, "ignored")
        self.store.record_download(22, first, "ignored")
        self.store.record_download(22, second, "ignored")

        stats = self.store.dashboard_stats(22)
        self.assertEqual(2, stats["latest_download_unique"])
        self.assertEqual(3, stats["latest_download_requests"])


class ServiceTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        root = Path(self.temp_dir.name)
        download_dir = root / "downloads"
        download_dir.mkdir()
        apk = download_dir / "electricity-reminder-0.15.0.apk"
        apk.write_bytes(b"fake apk")
        manifest = root / "update.json"
        manifest.write_text(
            json.dumps(
                {
                    "versionCode": 22,
                    "versionName": "0.15.0",
                    "apkUrl": "https://electricity.example.com/downloads/"
                    "electricity-reminder-0.15.0.apk",
                    "sha256": "0" * 64,
                }
            ),
            encoding="utf-8",
        )
        self.settings = SimpleNamespace(
            database_path=root / "analytics.sqlite3",
            telemetry_key=b"telemetry-key",
            manifest_path=manifest,
            download_dir=download_dir,
            password_hash=app_server.encode_password("test", iterations=1_000),
            password_hash_path=root / "admin_password_hash",
            session_secret=b"session-secret",
        )

    def test_manifest_selects_only_current_apk(self):
        service = app_server.ElecService(self.settings)
        filename, version_code, path = service.current_download()
        self.assertEqual("electricity-reminder-0.15.0.apk", filename)
        self.assertEqual(22, version_code)
        self.assertTrue(path.is_file())

    def test_old_apk_remains_downloadable_without_polluting_latest_count(self):
        old_apk = self.settings.download_dir / "electricity-reminder-0.14.0.apk"
        old_apk.write_bytes(b"old fake apk")
        service = app_server.ElecService(self.settings)
        filename, version_code, path = service.available_download(old_apk.name)
        self.assertEqual(old_apk.name, filename)
        self.assertEqual(0, version_code)
        self.assertEqual(old_apk, path)

    def test_signed_session_rejects_tampering_and_expiry(self):
        service = app_server.ElecService(self.settings)
        token = service.create_session()
        self.assertTrue(service.verify_session(token))
        self.assertFalse(service.verify_session(token + "x"))

        with mock.patch("server.server.time.time", return_value=0):
            old_token = service.create_session()
        self.assertFalse(service.verify_session(old_token))

    def test_password_change_persists_and_invalidates_existing_sessions(self):
        service = app_server.ElecService(self.settings)
        old_token = service.create_session()
        service.change_password("这是一个新的后台测试密码")

        self.assertFalse(service.verify_session(old_token))
        saved = self.settings.password_hash_path.read_text(encoding="utf-8").strip()
        self.assertTrue(app_server.verify_password("这是一个新的后台测试密码", saved))
        self.assertEqual(saved, self.settings.password_hash)

    def test_only_latest_three_release_links_are_listed(self):
        for version in ("0.13.0", "0.16.0", "0.16.1", "0.17.0"):
            (self.settings.download_dir / f"electricity-reminder-{version}.apk").write_bytes(
                version.encode("ascii")
            )
        service = app_server.ElecService(self.settings)
        releases = service.available_releases()
        self.assertEqual(
            ["0.17.0", "0.16.1", "0.16.0"],
            [item["version"] for item in releases],
        )


if __name__ == "__main__":
    unittest.main()
