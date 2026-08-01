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

        # queried_at 也固定在同一测试日，避免测试运行日期跨月后“最近一天”筛选只看见
        # 新增事件、误把此前的消耗事件判断成丢失。
        with mock.patch(
            "server.public_history.iso_shanghai",
            return_value="2026-07-31T10:03:00+08:00",
        ):
            self.store.record_sample(
                "2026-07-31T10:00:00+08:00",
                self.room,
                history.QueryResult(True, 100.0, 60.0),
            )

        # 后台展示从当前样本/房间目录补齐可读名称，筛选关联仍然只使用稳定 roomCode。
        overview = self.store.collector_overview(event_sort="time_desc")
        event = overview["events"][0]
        self.assertEqual("第一公寓", event["building_name"])
        self.assertEqual("1楼", event["floor_name"])
        self.assertEqual("101", event["room_name"])
        self.assertEqual(self.room.room_code, event["room_code"])
        morning_interval = next(
            row for row in overview["distribution"] if row["end_hour"] == 9
        )
        self.assertEqual(8, morning_interval["start_hour"])
        self.assertEqual(9, morning_interval["end_hour"])

        # 充值金额与消耗金额属于相反方向，后台必须分别筛选后再排序，不能按绝对值混排。
        recharge_events = self.store.collector_overview(
            event_sort="recharge_amount_desc"
        )["events"]
        consumption_events = self.store.collector_overview(
            event_sort="consumption_amount_desc"
        )["events"]
        self.assertEqual(1, len(recharge_events))
        self.assertEqual("充值", recharge_events[0]["inferred_type"])
        self.assertAlmostEqual(12.9, recharge_events[0]["delta_amount"])
        self.assertEqual(1, len(consumption_events))
        self.assertEqual("用电消耗", consumption_events[0]["inferred_type"])
        self.assertAlmostEqual(-0.9, consumption_events[0]["delta_amount"])

        # 每个前端排序值都必须走服务端白名单；未知值安全回退为按最新时间排序。
        for event_sort in history.EVENT_SORT_SQL:
            self.assertEqual(
                self.room.room_code,
                self.store.collector_overview(event_sort=event_sort)["events"][0][
                    "room_code"
                ],
            )
        self.assertEqual(
            self.room.room_code,
            self.store.collector_overview(event_sort="DROP TABLE samples")[
                "events"
            ][0]["room_code"],
        )

    def test_public_history_paginates_and_never_returns_credentials(self):
        for hour in (8, 9, 10):
            self.store.record_sample(
                f"2026-07-31T{hour:02d}:00:00+08:00",
                self.room,
                history.QueryResult(True, 90.0 + hour, 50.0 + hour),
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
        combined = first["records"] + second["records"]
        positive_events = [
            item for item in combined
            if item.get("changeType") == "充值"
        ]
        self.assertTrue(positive_events)
        self.assertTrue(positive_events[0]["changeStartAt"])
        self.assertGreater(positive_events[0]["changeDeltaKwh"], 0)
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


class CollectorEventQueryTests(unittest.TestCase):
    """后台事件查询回归：覆盖大数据分页、筛选、快照和稳定次级排序。"""

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.database = Path(self.temp_dir.name) / "collector.sqlite3"
        self.store = history.PublicHistoryStore(self.database)
        self._insert_events(1_250)

    def _insert_events(self, count, *, first_id=1):
        rows = []
        for offset in range(count):
            event_id = first_id + offset
            building_index = event_id % 2
            building_code = f"00100100{building_index + 1}"
            room_code = f"{building_code}001{event_id % 999:03d}"
            end_hour = 9 + event_id % 12
            event_type = "充值" if event_id % 3 == 0 else "用电消耗"
            delta = 10.0 if event_type == "充值" else -1.0
            rows.append((
                event_id, room_code, building_code,
                "第一公寓" if building_index == 0 else "第二公寓",
                event_id * 2, event_id * 2 + 1,
                f"2026-07-31T{end_hour - 1:02d}:02:00+08:00",
                f"2026-07-31T{end_hour:02d}:03:00+08:00",
                50.0, 50.0 + delta, delta, event_type,
            ))
        with self.store._connect() as connection:
            connection.executemany(
                """
                INSERT INTO change_events(
                    id, room_code, building_code, building_name,
                    previous_sample_id, current_sample_id,
                    previous_query_time, current_query_time,
                    before_balance, after_balance, delta_balance, inferred_type
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                rows,
            )

    def test_more_than_200_events_are_available_through_stable_pages(self):
        pages = []
        first = self.store.collector_events(
            day="2026-07-31", page=1, page_size=500
        )
        self.assertEqual(1_250, first["total"])
        self.assertEqual(3, first["total_pages"])
        self.assertEqual(500, len(first["events"]))
        pages.extend(first["events"])
        for page_number in (2, 3):
            page = self.store.collector_events(
                day="2026-07-31", page=page_number, page_size=500,
                snapshot_id=first["snapshot_id"],
            )
            pages.extend(page["events"])
        ids = [row["id"] for row in pages]
        self.assertEqual(1_250, len(ids))
        self.assertEqual(1_250, len(set(ids)))
        with self.store._connect() as connection:
            expected = [row[0] for row in connection.execute(
                """
                SELECT id FROM change_events
                ORDER BY current_query_time DESC, id DESC
                """
            )]
        self.assertEqual(expected, ids)

    def test_snapshot_prevents_new_insert_from_shifting_later_pages(self):
        first = self.store.collector_events(
            day="2026-07-31", page=1, page_size=100
        )
        first_ids = {row["id"] for row in first["events"]}
        self._insert_events(1, first_id=2_000)
        second = self.store.collector_events(
            day="2026-07-31", page=2, page_size=100,
            snapshot_id=first["snapshot_id"],
        )
        self.assertEqual(1_250, second["total"])
        self.assertTrue(first_ids.isdisjoint({row["id"] for row in second["events"]}))
        self.assertNotIn(2_000, {row["id"] for row in second["events"]})

    def test_date_building_room_type_and_interval_filters_run_in_database(self):
        sample = self.store.collector_events(
            day="2026-07-31", page_size=100
        )["events"][0]
        building = self.store.collector_events(
            day="2026-07-31", building_code=sample["building_code"]
        )
        room = self.store.collector_events(
            day="2026-07-31", room_code=sample["room_code"]
        )
        recharge = self.store.collector_events(
            day="2026-07-31", event_type="充值"
        )
        interval = self.store.collector_events(
            day="2026-07-31", interval_end_hour=9
        )
        self.assertTrue(building["total"] < 1_250)
        self.assertEqual(1, room["total"])
        self.assertTrue(all(row["inferred_type"] == "充值" for row in recharge["events"]))
        self.assertTrue(all(
            row["current_query_time"][11:13] == "09" for row in interval["events"]
        ))

    def test_midnight_interval_uses_24_as_nonzero_filter_token(self):
        with self.store._connect() as connection:
            connection.execute(
                """
                INSERT INTO change_events(
                    id, room_code, building_code, building_name,
                    previous_sample_id, current_sample_id,
                    previous_query_time, current_query_time,
                    before_balance, after_balance, delta_balance, inferred_type
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    3_000, "001001001001001", "001001001", "第一公寓",
                    6_000, 6_001,
                    "2026-07-30T23:03:00+08:00",
                    "2026-07-31T00:03:00+08:00",
                    50.0, 49.0, -1.0, "用电消耗",
                ),
            )
        midnight = self.store.collector_events(
            day="2026-07-31", interval_end_hour=24
        )
        self.assertEqual(1, midnight["total"])
        self.assertEqual("00", midnight["events"][0]["current_query_time"][11:13])

    def test_amount_sorting_strictly_forces_matching_event_type(self):
        recharge = self.store.collector_events(
            day="2026-07-31", event_type="用电消耗",
            event_sort="recharge_amount_desc",
        )
        consumption = self.store.collector_events(
            day="2026-07-31", event_type="充值",
            event_sort="consumption_amount_desc",
        )
        self.assertEqual("充值", recharge["event_type"])
        self.assertEqual("用电消耗", consumption["event_type"])
        self.assertTrue(all(row["inferred_type"] == "充值" for row in recharge["events"]))
        self.assertTrue(all(
            row["inferred_type"] == "用电消耗" for row in consumption["events"]
        ))

    def test_distribution_has_twenty_four_hourly_intervals_and_summary(self):
        result = self.store.collector_distribution("2026-07-31")
        self.assertEqual(24, len(result["intervals"]))
        self.assertEqual((23, 24), (
            result["intervals"][0]["start_hour"],
            result["intervals"][0]["end_hour"],
        ))
        self.assertEqual((22, 23), (
            result["intervals"][-1]["start_hour"],
            result["intervals"][-1]["end_hour"],
        ))
        self.assertEqual(1_250, result["total"])

    def test_required_event_indexes_exist(self):
        with self.store._connect() as connection:
            indexes = {
                row[1] for row in connection.execute("PRAGMA index_list(change_events)")
            }
        self.assertTrue({
            "idx_events_time_id", "idx_events_room_time_id",
            "idx_events_type_time_id", "idx_events_building_time",
        }.issubset(indexes))


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

    def test_change_type_labels_treat_every_increase_as_recharge(self):
        self.assertEqual("用电消耗", history.infer_change_type(-1))
        self.assertEqual("充值", history.infer_change_type(20))
        self.assertEqual("充值", history.infer_change_type(192.15))
        self.assertEqual("充值", history.infer_change_type(1_000))
        self.assertEqual("待确认", history.infer_change_type(-100))

    def test_collection_round_maps_full_day_into_twenty_four_rounds(self):
        self.assertEqual(
            1, history.collection_round_number("2026-07-31T00:00:00+08:00")
        )
        self.assertEqual(
            24, history.collection_round_number("2026-07-31T23:00:00+08:00")
        )
        self.assertEqual(0, history.collection_round_number("not-a-time"))

    def test_every_hour_is_inside_dispatch_window(self):
        for hour in range(24):
            self.assertTrue(history.is_collection_dispatch_time(
                dt.datetime(
                    2026, 7, 31, hour, 0, tzinfo=history.SHANGHAI
                )
            ))
        self.assertFalse(history.is_collection_dispatch_time(
            dt.datetime(
                2026, 7, 31, 23, 10, tzinfo=history.SHANGHAI
            )
        ))

    def test_negative_balance_is_valid_arrears(self):
        client = object.__new__(history.XiaofubaoClient)
        client._request = lambda _endpoint, _fields: {
            "data": {"surplus": -0.5, "amount": -0.31}
        }
        result = client.query_balance(
            history.RoomEntry(
                "001001001001008",
                "001001001",
                "第一公寓",
                "001001001001",
                "1楼",
                "108",
            )
        )
        self.assertTrue(result.success)
        self.assertEqual(-0.5, result.balance_kwh)


if __name__ == "__main__":
    unittest.main()
