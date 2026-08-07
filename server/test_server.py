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

    def test_public_history_rate_limit_is_memory_only(self):
        limiter = app_server.PublicReadLimiter(maximum=2, window_seconds=60)
        self.assertTrue(limiter.allow("test-client"))
        self.assertTrue(limiter.allow("test-client"))
        self.assertFalse(limiter.allow("test-client"))
        # 其他来源不被同一个客户端的请求连带限制。
        self.assertTrue(limiter.allow("other-client"))


class CollectorAdminParameterTests(unittest.TestCase):
    def test_event_query_parameters_are_whitelisted(self):
        parameters = app_server._collector_parameters(
            "/admin/collector/events?date=2026-07-31&buildingCode=001001001"
            "&roomCode=001001001003305&eventType=%E5%85%85%E5%80%BC"
            "&sort=recharge_amount_desc&pageSize=500&page=3&snapshot=1200"
            "&intervalEnd=24"
        )
        self.assertEqual("2026-07-31", parameters["day"])
        self.assertEqual("001001001", parameters["building_code"])
        self.assertEqual("001001001003305", parameters["room_code"])
        self.assertEqual("充值", parameters["event_type"])
        self.assertEqual(500, parameters["events"]["page_size"])
        self.assertEqual(3, parameters["events"]["page"])
        self.assertEqual(1200, parameters["events"]["snapshot_id"])
        self.assertEqual(24, parameters["interval_end_hour"])

    def test_invalid_sort_page_size_and_codes_fall_back_safely(self):
        parameters = app_server._collector_parameters(
            "/admin/collector/events?buildingCode=1%20OR%201%3D1"
            "&roomCode=not-a-room&sort=current_query_time%20DROP%20TABLE"
            "&pageSize=99999&intervalEnd=25"
        )
        self.assertEqual("", parameters["building_code"])
        self.assertEqual("", parameters["room_code"])
        self.assertEqual("time_desc", parameters["events"]["event_sort"])
        self.assertEqual(100, parameters["events"]["page_size"])
        self.assertEqual(0, parameters["interval_end_hour"])

    def test_inline_admin_script_gets_exact_csp_hash(self):
        handler = object.__new__(app_server.Handler)
        captured = {}

        def capture(status, body, content_type, **kwargs):
            captured.update(kwargs)

        handler._send_bytes = capture
        handler._send_html(app_server.HTTPStatus.OK, "<script>safe()</script>")
        hashes = captured["script_hashes"]
        self.assertEqual(1, len(hashes))
        self.assertTrue(hashes[0].startswith("'sha256-"))

    def test_distribution_keeps_only_counts_and_event_categories(self):
        intervals = [
            {
                "start_hour": (end_hour - 1) % 24,
                "end_hour": 24 if end_hour == 0 else end_hour,
                "total_count": 600 if hour == 17 else 0,
                "recharge_count": 0,
                "consumption_count": 600 if hour == 17 else 0,
                "abnormal_count": 0,
                "total_delta_kwh": -123.4,
                "total_delta_amount": -74.04,
            }
            for end_hour in range(24)
            for hour in [(end_hour - 1) % 24]
        ]
        fragment = app_server.collector_distribution_fragment(
            {
                "day": "2026-07-31", "total": 600,
                "recharge": 0, "consumption": 600, "abnormal": 0,
                "intervals": intervals,
            }
        )
        self.assertIn("17:00–18:00", fragment)
        self.assertIn("23:00–00:00", fragment)
        self.assertIn("消耗 600", fragment)
        self.assertIn("充值 0", fragment)
        self.assertNotIn("-123.4", fragment)
        self.assertNotIn("-74.04", fragment)

    def test_task_metrics_separate_retained_and_current_round_scope(self):
        fragment = app_server.collector_task_fragment({
            "collection_enabled": False,
            "latest_job": {
                "total_rooms": 1412, "processed_rooms": 1412,
                "success_count": 1412, "failure_count": 0,
                "duration_seconds": 400.0, "status": "completed",
                "slot_time": "2026-07-31T18:00:00+08:00",
                "started_at": "2026-07-31T18:00:01+08:00",
                "finished_at": "2026-07-31T18:06:41+08:00",
            },
            "current_round": 11, "round_total": 13,
            "total_samples": 14120, "total_failure_samples": 7,
            "database_bytes": 1024,
            "current_building": "本轮已完成", "no_meter_count": 0,
            "covered_buildings": 12, "valid_rooms": 1412,
            "buildings": [], "failures": [],
        }, "csrf-test")
        self.assertIn("总采集数", fragment)
        self.assertIn("总失败数", fragment)
        self.assertIn("本轮成功", fragment)
        self.assertIn("14120", fragment)
        self.assertIn("统计范围为服务器最近30天", fragment)
        self.assertNotIn("样本总数", fragment)
        self.assertIn("自动采集已暂停", fragment)
        self.assertIn("重新开启", fragment)
        self.assertIn('name="csrf" value="csrf-test"', fragment)


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

    def test_stale_heartbeat_updates_version_without_polluting_today_active(self):
        device_digest = "b" * 64
        self.store.record_heartbeat(
            device_digest, 32, "1.0.0", event_day="2000-01-01", historical=True
        )
        stats = self.store.dashboard_stats(32)
        self.assertEqual(1, stats["total_users"])
        self.assertEqual(1, stats["latest_installed"])
        self.assertEqual(0, stats["today_active"])

    def test_download_tracks_requests_and_unique_installations_separately(self):
        first = "6dbb4120-3748-4a8c-b5cf-cfa6f508f996"
        second = "49416366-ef0b-431f-8135-965ca036e212"
        self.store.record_download(22, first, "ignored")
        self.store.record_download(22, first, "ignored")
        self.store.record_download(22, second, "ignored")

        stats = self.store.dashboard_stats(22)
        self.assertEqual(2, stats["latest_download_unique"])
        self.assertEqual(3, stats["latest_download_requests"])

    def test_announcement_delivery_and_read_are_idempotent(self):
        first_device = "a" * 64
        second_device = "b" * 64
        self.store.record_heartbeat(first_device, 50, "2.0.0")
        self.store.record_heartbeat(second_device, 50, "2.0.0")
        announcement_id = self.store.create_announcement("停机通知", "今晚服务维护")

        first = self.store.deliver_announcements(first_device, 0)
        self.store.deliver_announcements(first_device, 0)
        self.assertEqual(announcement_id, first["announcements"][0]["id"])
        self.assertTrue(self.store.mark_announcement_read(announcement_id, first_device))
        self.assertTrue(self.store.mark_announcement_read(announcement_id, first_device))

        item = self.store.list_announcements()[0]
        self.assertEqual(2, item["target_count"])
        self.assertEqual(1, item["delivered_count"])
        self.assertEqual(1, item["read_count"])

    def test_announcement_delivery_uses_server_receipt_not_client_cursor(self):
        device = "c" * 64
        first_id = self.store.create_announcement("第一条", "内容一")
        second_id = self.store.create_announcement("第二条", "内容二")
        items = self.store.deliver_announcements(device, first_id)
        self.assertEqual(
            [first_id, second_id],
            [item["id"] for item in items["announcements"]],
        )
        self.assertFalse(self.store.mark_announcement_read(999_999, device))

    def test_announcement_only_delivers_inside_configured_window(self):
        device = "e" * 64
        with mock.patch(
            "server.server.iso_utc_now", return_value="2026-08-07T00:00:00+00:00"
        ):
            announcement_id = self.store.create_announcement(
                "限时公告", "仅在上午推送",
                "2026-08-07T01:00:00+00:00",
                "2026-08-07T03:00:00+00:00",
            )

        with mock.patch(
            "server.server.iso_utc_now", return_value="2026-08-07T00:30:00+00:00"
        ):
            self.assertEqual([], self.store.deliver_announcements(device, 0)["announcements"])

        with mock.patch(
            "server.server.iso_utc_now", return_value="2026-08-07T02:00:00+00:00"
        ):
            delivered = self.store.deliver_announcements(device, 0)
            self.assertEqual(announcement_id, delivered["announcements"][0]["id"])

        # 过期后不再向新设备投递；已送达设备会复用现有撤回列表，让旧版客户端
        # 也能清除系统通知和本地待读弹窗，无需升级协议。
        with mock.patch(
            "server.server.iso_utc_now", return_value="2026-08-07T03:00:00+00:00"
        ):
            expired = self.store.deliver_announcements(device, 0)
            self.assertEqual([], expired["announcements"])
            self.assertEqual([announcement_id], expired["withdrawn_ids"])
            self.assertEqual(
                [], self.store.deliver_announcements("f" * 64, 0)["announcements"]
            )

    def test_announcement_local_time_is_interpreted_as_shanghai(self):
        self.assertEqual(
            "2026-08-07T04:31:00+00:00",
            app_server._announcement_time_from_local("2026-08-07T12:31"),
        )
        with self.assertRaises(ValueError):
            app_server._announcement_time_from_local("2026/08/07 12:31")

    def test_close_reopen_and_withdraw_have_distinct_behavior(self):
        device = "d" * 64
        announcement_id = self.store.create_announcement("测试公告", "状态测试")
        self.assertTrue(self.store.change_announcement_state(announcement_id, "close"))
        closed = self.store.deliver_announcements(device, 0)
        self.assertEqual([], closed["announcements"])

        self.assertTrue(self.store.change_announcement_state(announcement_id, "reopen"))
        opened = self.store.deliver_announcements(device, 0)
        self.assertEqual(announcement_id, opened["announcements"][0]["id"])

        self.assertTrue(self.store.change_announcement_state(announcement_id, "withdraw"))
        withdrawn = self.store.deliver_announcements(device, 0)
        self.assertEqual([announcement_id], withdrawn["withdrawn_ids"])
        self.assertFalse(self.store.mark_announcement_read(announcement_id, device))

    def test_withdrawn_announcement_is_irreversible(self):
        announcement_id = self.store.create_announcement("撤回", "不能恢复")
        self.assertTrue(self.store.change_announcement_state(announcement_id, "withdraw"))
        self.assertFalse(self.store.change_announcement_state(announcement_id, "reopen"))
        item = self.store.list_announcements()[0]
        self.assertEqual(1, item["withdrawn"])
        self.assertEqual(0, item["active"])

    def test_announcement_admin_page_escapes_user_visible_content(self):
        announcement_id = self.store.create_announcement(
            "<标题>", "正文 <script>",
            "2026-08-07T04:00:00+00:00", "2026-08-07T06:00:00+00:00",
        )
        page = app_server.announcements_page(
            self.store.list_announcements(), "safe-csrf"
        )
        self.assertIn(f"公告 #{announcement_id}", page)
        self.assertIn("&lt;标题&gt;", page)
        self.assertIn("正文 &lt;script&gt;", page)
        self.assertNotIn("正文 <script>", page)
        self.assertIn('name="startsAt" type="datetime-local"', page)
        self.assertIn('name="expiresAt" type="datetime-local"', page)
        self.assertIn("2026-08-07 12:00 — 2026-08-07 14:00", page)


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

    def test_signed_feedback_is_stored_and_escaped_in_admin_page(self):
        service = app_server.ElecService(self.settings)
        feedback_id = service.store.record_feedback("测试同学", "很好用 <script>", "1.6.0")
        items = service.store.list_feedback()
        self.assertEqual(feedback_id, items[0]["id"])
        self.assertEqual("测试同学", items[0]["signature"])
        page = app_server.feedback_page(items)
        self.assertIn("测试同学", page)
        self.assertIn("&lt;script&gt;", page)
        self.assertNotIn("很好用 <script>", page)

    def test_old_apk_remains_downloadable_without_polluting_latest_count(self):
        old_apk = self.settings.download_dir / "electricity-reminder-0.14.0.apk"
        old_apk.write_bytes(b"old fake apk")
        service = app_server.ElecService(self.settings)
        filename, version_code, path = service.available_download(old_apk.name)
        self.assertEqual(old_apk.name, filename)
        self.assertEqual(0, version_code)
        self.assertEqual(old_apk, path)

    def test_stable_latest_download_always_resolves_current_manifest(self):
        service = app_server.ElecService(self.settings)
        filename, version_code, path = service.available_download("latest.apk")
        self.assertEqual("electricity-reminder-0.15.0.apk", filename)
        self.assertEqual(22, version_code)
        self.assertTrue(path.is_file())

    def test_product_page_uses_stable_download_link(self):
        page = app_server.product_page({"versionName": "1.3.0"})
        self.assertIn("江理电小侠", page)
        self.assertIn("/assets/mascot-app-icon.png", page)
        self.assertIn("/downloads/latest.apk", page)
        self.assertIn("赣ICP备2026010766号-2A", page)
        self.assertIn("https://beian.miit.gov.cn/", page)
        # 产品页文案直接回应学生常见的断电与充值入口难找问题，避免后续改版时退回功能罗列。
        self.assertIn("别等突然停电", page)
        self.assertIn("想充值，却想不起入口在哪", page)
        self.assertNotIn("electricity-reminder-1.3.0.apk", page)
        self.assertIn("1.3.0", page)

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

    def test_formal_jiangli_apk_is_in_release_history(self):
        formal = self.settings.download_dir / "jiangli-electricity-2.1.0.apk"
        formal.write_bytes(b"formal apk")
        service = app_server.ElecService(self.settings)
        releases = service.available_releases()
        self.assertEqual("2.1.0", releases[0]["version"])
        self.assertEqual(formal.name, releases[0]["filename"])


if __name__ == "__main__":
    unittest.main()
