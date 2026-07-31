"""公共房间目录、30 天采样和任务幂等回归测试；不访问真实校付宝。"""

import datetime as dt
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from server import public_history as history


class PublicHistoryStoreTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.database = Path(self.temp_dir.name) / "public_history.sqlite3"
        self.store = history.PublicHistoryStore(self.database)
        self.room = history.RoomEntry(
            "001001001001001",
            "001001001",
            "第一公寓",
            "001001001001",
            "1楼",
            "101",
        )
        self.store.replace_building_directory(
            self.room.building_code,
            [self.room],
            "2026-07-31T08:00:00+08:00",
        )

    def count(self, table):
        with sqlite3.connect(self.database) as connection:
            return connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]

    def test_same_room_and_slot_is_idempotent(self):
        slot = "2026-07-31T08:00:00+08:00"
        result = history.QueryResult(True, 80.0, 48.0)
        self.assertTrue(self.store.record_sample(slot, self.room, result))
        self.assertFalse(self.store.record_sample(slot, self.room, result))
        self.assertEqual(1, self.count("samples"))
        self.assertEqual(0, self.count("change_events"))

    def test_failed_slot_can_be_repaired_without_duplicate(self):
        slot = "2026-07-31T08:00:00+08:00"
        self.store.record_sample(
            slot, self.room, history.QueryResult(False, None, None, "timeout")
        )
        self.assertTrue(
            self.store.record_sample(
                slot, self.room, history.QueryResult(True, 79.0, 47.4)
            )
        )
        with sqlite3.connect(self.database) as connection:
            row = connection.execute(
                "SELECT query_result, error_type FROM samples"
            ).fetchone()
        self.assertEqual(("success", None), row)
        self.assertEqual(1, self.count("samples"))

    def test_change_event_uses_interval_and_is_idempotent(self):
        first = "2026-07-31T08:00:00+08:00"
        second = "2026-07-31T09:00:00+08:00"
        with mock.patch(
            "server.public_history.iso_shanghai",
            side_effect=[
                "2026-07-31T08:02:00+08:00",
                "2026-07-31T09:03:00+08:00",
            ],
        ):
            self.store.record_sample(
                first, self.room, history.QueryResult(True, 80.0, 48.0)
            )
            self.store.record_sample(
                second, self.room, history.QueryResult(True, 78.5, 47.1)
            )
        self.store.record_sample(
            second, self.room, history.QueryResult(True, 78.5, 47.1)
        )
        with sqlite3.connect(self.database) as connection:
            row = connection.execute(
                """
                SELECT previous_query_time, current_query_time,
                       delta_balance, inferred_type FROM change_events
                """
            ).fetchone()
        self.assertEqual(1, self.count("change_events"))
        self.assertNotEqual(row[0], row[1])
        self.assertAlmostEqual(-1.5, row[2])
        self.assertEqual("用电消耗", row[3])

    def test_public_history_paginates_and_never_returns_credentials(self):
        for hour in (8, 9, 10):
            self.store.record_sample(
                f"2026-07-31T{hour:02d}:00:00+08:00",
                self.room,
                history.QueryResult(True, 90.0 - hour, 50.0 - hour),
            )
        first = self.store.public_history(
            self.room.room_code,
            "2026-07-01T00:00:00+08:00",
            "2026-08-01T00:00:00+08:00",
            0,
            2,
        )
        second = self.store.public_history(
            self.room.room_code,
            "2026-07-01T00:00:00+08:00",
            "2026-08-01T00:00:00+08:00",
            first["nextCursor"],
            2,
        )
        self.assertTrue(first["hasMore"])
        self.assertFalse(second["hasMore"])
        self.assertEqual(3, len(first["records"]) + len(second["records"]))
        serialized = json.dumps(first, ensure_ascii=False).lower()
        self.assertNotIn("cookie", serialized)
        self.assertNotIn("shiro", serialized)
        self.assertNotIn("raw", serialized)

    def test_cleanup_removes_only_older_than_30_days(self):
        self.store.record_sample(
            "2026-06-01T08:00:00+08:00",
            self.room,
            history.QueryResult(True, 90.0, 54.0),
        )
        self.store.record_sample(
            "2026-07-31T08:00:00+08:00",
            self.room,
            history.QueryResult(True, 80.0, 48.0),
        )
        result = self.store.cleanup(
            dt.datetime(2026, 7, 31, 12, tzinfo=history.SHANGHAI)
        )
        self.assertEqual(1, result["samples"])
        self.assertEqual(1, self.count("samples"))


class FakeDirectoryClient:
    def query_buildings(self):
        return [
            {"code": code, "name": name}
            for code, name in history.TARGET_BUILDINGS.items()
        ] + [{"code": "001001999", "name": "第一公寓复制项"}]

    def query_floors(self, building_code):
        return [{"code": building_code + "001", "name": "1楼"}]

    def query_rooms(self, building_code, floor_code):
        return [
            {"code": floor_code + "001", "name": "101"},
            # 重复项应按 roomCode 去重。
            {"code": floor_code + "001", "name": "101重复"},
            # 跨楼栋脏数据必须丢弃。
            {"code": "001001999001001", "name": "错误房间"},
        ]


class DirectoryScopeTests(unittest.TestCase):
    def test_directory_scope_uses_exact_twelve_building_codes(self):
        with tempfile.TemporaryDirectory() as root:
            store = history.PublicHistoryStore(Path(root) / "history.sqlite3")
            collector = history.PublicHistoryCollector(store, FakeDirectoryClient())
            result = collector.sync_directory()
            rooms = store.active_rooms()
        self.assertEqual(set(history.TARGET_BUILDINGS), set(result["success"]))
        self.assertEqual({}, result["failures"])
        self.assertEqual(12, len(rooms))
        self.assertEqual(set(history.TARGET_BUILDINGS), {room.building_code for room in rooms})
        self.assertNotIn("001001999", {room.building_code for room in rooms})

    def test_change_type_labels_are_conservative(self):
        self.assertEqual("用电消耗", history.infer_change_type(-1))
        self.assertEqual("疑似充值或平台修正", history.infer_change_type(20))
        self.assertEqual("待确认", history.infer_change_type(100))


if __name__ == "__main__":
    unittest.main()
