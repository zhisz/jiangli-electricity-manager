"""南昌校区公共房间目录、整点采样和 30 天历史存储。

该模块与匿名使用统计使用不同 SQLite 文件。它只保存校付宝公共房间采样，不接收 App
里的别名、阈值、充值记录或其他用户配置。采集器发生任何异常都只结束当前房间或当前
轮次，不参与更新下载、后台登录和 App 实时查询。
"""

from __future__ import annotations

import datetime as dt
import hashlib
import json
import math
import socket
import sqlite3
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo


SHANGHAI = ZoneInfo("Asia/Shanghai")
AREA_ID = "1902181751257031"
PLATFORM = "WECHAT_H5"
DIRECTORY_BASE_URL = "https://application.xiaofubao.com/app/electric/"

# 采集范围必须由 buildingCode 精确决定。名称仅用于显示，绝不参与筛选。
TARGET_BUILDINGS: dict[str, str] = {
    "001001001": "第一公寓",
    "001001002": "第二公寓",
    "001001003": "第四公寓",
    "001001004": "第五公寓",
    "001001005": "第六公寓",
    "001001006": "第七公寓",
    "001001007": "第八公寓",
    "001001008": "第九公寓",
    "001001014": "一号家属楼",
    "001001015": "二号家属楼",
    "001001016": "三号家属楼",
    "001001017": "四号家属楼",
}
ROOM_CODE_PATTERN = r"\d{15}"
RETENTION_DAYS = 30
DATA_VERSION = 2
LARGE_NEGATIVE_CHANGE_KWH = 100.0
COLLECTION_START_HOUR = 8
COLLECTION_END_HOUR = 20
COLLECTION_ROUND_TOTAL = COLLECTION_END_HOUR - COLLECTION_START_HOUR + 1

# 后台排序参数只能映射到这里的固定 SQL，绝不直接拼接用户输入。
EVENT_SORT_SQL = {
    "time_desc": "ce.current_query_time DESC, ce.id DESC",
    "time_asc": "ce.current_query_time ASC, ce.id ASC",
    "building_asc": (
        "ce.building_code ASC, floor_name ASC, room_name ASC, "
        "ce.current_query_time DESC, ce.id DESC"
    ),
    "building_desc": (
        "ce.building_code DESC, floor_name DESC, room_name DESC, "
        "ce.current_query_time DESC, ce.id DESC"
    ),
    "recharge_amount_desc": (
        "COALESCE(s.amount_yuan - ps.amount_yuan, 0) DESC, "
        "ce.current_query_time DESC, ce.id DESC"
    ),
    "consumption_amount_desc": (
        "COALESCE(ps.amount_yuan - s.amount_yuan, 0) DESC, "
        "ce.current_query_time DESC, ce.id DESC"
    ),
    "balance_desc": (
        "ABS(ce.delta_balance) DESC, ce.current_query_time DESC, ce.id DESC"
    ),
}
EVENT_PAGE_SIZES = {100, 200, 500}
EVENT_TYPES = {"充值", "用电消耗", "待确认"}


def shanghai_now() -> dt.datetime:
    return dt.datetime.now(SHANGHAI)


def iso_shanghai(value: dt.datetime | None = None) -> str:
    current = value or shanghai_now()
    return current.astimezone(SHANGHAI).isoformat(timespec="seconds")


def parse_iso(value: str) -> dt.datetime:
    parsed = dt.datetime.fromisoformat(value)
    if parsed.tzinfo is None:
        raise ValueError("时间必须包含时区")
    return parsed.astimezone(SHANGHAI)


def _day_bounds(day: str) -> tuple[str, str]:
    """返回上海自然日的半开区间；ISO 文本可直接使用事件时间索引比较。"""

    parsed = dt.date.fromisoformat(day)
    start = dt.datetime.combine(parsed, dt.time.min, tzinfo=SHANGHAI)
    end = start + dt.timedelta(days=1)
    return iso_shanghai(start), iso_shanghai(end)


@dataclass(frozen=True)
class RoomEntry:
    room_code: str
    building_code: str
    building_name: str
    floor_code: str
    floor_name: str
    room_name: str


@dataclass(frozen=True)
class QueryResult:
    success: bool
    balance_kwh: float | None
    amount_yuan: float | None
    error_type: str | None = None


class RemoteError(Exception):
    """分类后的第三方错误；message 不会写入数据库，以免保存原始响应。"""

    def __init__(self, error_type: str) -> None:
        super().__init__(error_type)
        self.error_type = error_type


class PublicHistoryStore:
    """公共采样独立数据库；WAL 允许后台页面读取与采集写入并行进行。"""

    def __init__(self, path: Path) -> None:
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._initialize()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.path, timeout=10)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA busy_timeout = 10000")
        return connection

    def _initialize(self) -> None:
        with self._connect() as connection:
            connection.execute("PRAGMA journal_mode = WAL")
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS rooms (
                    room_code TEXT PRIMARY KEY,
                    building_code TEXT NOT NULL,
                    building_name TEXT NOT NULL,
                    floor_code TEXT NOT NULL,
                    floor_name TEXT NOT NULL,
                    room_name TEXT NOT NULL,
                    active INTEGER NOT NULL DEFAULT 1,
                    meter_available INTEGER NOT NULL DEFAULT 1,
                    last_directory_sync TEXT NOT NULL,
                    last_error_type TEXT
                );

                CREATE INDEX IF NOT EXISTS idx_rooms_building_active
                    ON rooms(building_code, active);

                CREATE TABLE IF NOT EXISTS collection_jobs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    slot_time TEXT NOT NULL UNIQUE,
                    started_at TEXT NOT NULL,
                    finished_at TEXT,
                    status TEXT NOT NULL,
                    total_rooms INTEGER NOT NULL DEFAULT 0,
                    processed_rooms INTEGER NOT NULL DEFAULT 0,
                    success_count INTEGER NOT NULL DEFAULT 0,
                    failure_count INTEGER NOT NULL DEFAULT 0,
                    duration_seconds REAL,
                    last_error_type TEXT
                );

                CREATE TABLE IF NOT EXISTS job_buildings (
                    job_id INTEGER NOT NULL REFERENCES collection_jobs(id) ON DELETE CASCADE,
                    building_code TEXT NOT NULL,
                    building_name TEXT NOT NULL,
                    room_count INTEGER NOT NULL,
                    success_count INTEGER NOT NULL,
                    failure_count INTEGER NOT NULL,
                    PRIMARY KEY(job_id, building_code)
                );

                CREATE TABLE IF NOT EXISTS samples (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    room_code TEXT NOT NULL,
                    building_code TEXT NOT NULL,
                    building_name TEXT NOT NULL,
                    floor_code TEXT NOT NULL,
                    floor_name TEXT NOT NULL,
                    room_name TEXT NOT NULL,
                    slot_time TEXT NOT NULL,
                    queried_at TEXT NOT NULL,
                    balance_kwh REAL,
                    amount_yuan REAL,
                    query_result TEXT NOT NULL,
                    error_type TEXT,
                    data_source TEXT NOT NULL,
                    UNIQUE(room_code, slot_time)
                );

                CREATE INDEX IF NOT EXISTS idx_samples_room_time
                    ON samples(room_code, slot_time);
                CREATE INDEX IF NOT EXISTS idx_samples_building_time
                    ON samples(building_code, slot_time);
                CREATE INDEX IF NOT EXISTS idx_samples_result_time
                    ON samples(query_result, slot_time);

                CREATE TABLE IF NOT EXISTS change_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    room_code TEXT NOT NULL,
                    building_code TEXT NOT NULL,
                    building_name TEXT NOT NULL,
                    previous_sample_id INTEGER NOT NULL,
                    current_sample_id INTEGER NOT NULL UNIQUE,
                    previous_query_time TEXT NOT NULL,
                    current_query_time TEXT NOT NULL,
                    before_balance REAL NOT NULL,
                    after_balance REAL NOT NULL,
                    delta_balance REAL NOT NULL,
                    inferred_type TEXT NOT NULL
                );

                CREATE INDEX IF NOT EXISTS idx_events_building_time
                    ON change_events(building_code, current_query_time);
                CREATE INDEX IF NOT EXISTS idx_events_time_id
                    ON change_events(current_query_time, id);
                CREATE INDEX IF NOT EXISTS idx_events_room_time_id
                    ON change_events(room_code, current_query_time, id);
                CREATE INDEX IF NOT EXISTS idx_events_type_time_id
                    ON change_events(inferred_type, current_query_time, id);

                CREATE TABLE IF NOT EXISTS collector_metadata (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                );
                PRAGMA user_version = 1;
                """
            )
            # 新分类规则中，余额正向变化统一视为充值。迁移只改显示分类，不改房间码、
            # 原始采样值、变化量或任务记录，因此已有 App 历史数据仍可安全合并。
            connection.execute(
                """
                UPDATE change_events
                SET inferred_type = '充值'
                WHERE delta_balance > 0
                  AND inferred_type != '充值'
                """
            )

    def metadata(self, key: str, fallback: str = "") -> str:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT value FROM collector_metadata WHERE key = ?", (key,)
            ).fetchone()
        return str(row["value"]) if row else fallback

    def set_metadata(self, key: str, value: str) -> None:
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO collector_metadata(key, value) VALUES (?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
                """,
                (key, value),
            )

    def replace_building_directory(
        self, building_code: str, entries: list[RoomEntry], synced_at: str
    ) -> None:
        """只有整栋目录完整读取成功后才调用，临时失败不会误删已有房间。"""

        unique = {entry.room_code: entry for entry in entries}
        with self._connect() as connection:
            # 同一事务先暂停旧目录，再把本轮实际存在的房间重新激活；新增、删除和重复
            # 返回都能得到确定结果，外部读取不会看到半完成状态。
            connection.execute(
                "UPDATE rooms SET active = 0 WHERE building_code = ?",
                (building_code,),
            )
            for entry in unique.values():
                connection.execute(
                    """
                    INSERT INTO rooms(
                        room_code, building_code, building_name,
                        floor_code, floor_name, room_name,
                        active, meter_available, last_directory_sync, last_error_type
                    ) VALUES (?, ?, ?, ?, ?, ?, 1, 1, ?, NULL)
                    ON CONFLICT(room_code) DO UPDATE SET
                        building_code = excluded.building_code,
                        building_name = excluded.building_name,
                        floor_code = excluded.floor_code,
                        floor_name = excluded.floor_name,
                        room_name = excluded.room_name,
                        active = 1,
                        last_directory_sync = excluded.last_directory_sync
                    """,
                    (
                        entry.room_code,
                        entry.building_code,
                        entry.building_name,
                        entry.floor_code,
                        entry.floor_name,
                        entry.room_name,
                        synced_at,
                    ),
                )

    def active_rooms(self) -> list[RoomEntry]:
        with self._connect() as connection:
            rows = connection.execute(
                """
                SELECT room_code, building_code, building_name,
                       floor_code, floor_name, room_name
                FROM rooms WHERE active = 1
                ORDER BY building_code, floor_code, room_code
                """
            ).fetchall()
        return [
            RoomEntry(
                row["room_code"],
                row["building_code"],
                row["building_name"],
                row["floor_code"],
                row["floor_name"],
                row["room_name"],
            )
            for row in rows
        ]

    def mark_room_error(self, room_code: str, error_type: str | None) -> None:
        with self._connect() as connection:
            connection.execute(
                """
                UPDATE rooms SET
                    meter_available = CASE
                        WHEN ? = 'no_meter' THEN 0
                        WHEN ? IS NULL THEN 1
                        ELSE meter_available
                    END,
                    last_error_type = ?
                WHERE room_code = ?
                """,
                (error_type, error_type, error_type, room_code),
            )

    def start_job(self, slot_time: str, total_rooms: int) -> int:
        now = iso_shanghai()
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO collection_jobs(
                    slot_time, started_at, status, total_rooms
                ) VALUES (?, ?, 'running', ?)
                ON CONFLICT(slot_time) DO UPDATE SET
                    started_at = excluded.started_at,
                    finished_at = NULL,
                    status = 'running',
                    total_rooms = excluded.total_rooms,
                    processed_rooms = 0,
                    success_count = 0,
                    failure_count = 0,
                    duration_seconds = NULL,
                    last_error_type = NULL
                """,
                (slot_time, now, total_rooms),
            )
            row = connection.execute(
                "SELECT id FROM collection_jobs WHERE slot_time = ?", (slot_time,)
            ).fetchone()
        return int(row["id"])

    def job_status(self, slot_time: str) -> str | None:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT status FROM collection_jobs WHERE slot_time = ?", (slot_time,)
            ).fetchone()
        return str(row["status"]) if row else None

    def update_job(
        self, job_id: int, processed: int, success: int, failure: int
    ) -> None:
        with self._connect() as connection:
            connection.execute(
                """
                UPDATE collection_jobs SET processed_rooms = ?,
                    success_count = ?, failure_count = ?
                WHERE id = ?
                """,
                (processed, success, failure, job_id),
            )

    def finish_job(
        self,
        job_id: int,
        status: str,
        started_monotonic: float,
        processed: int,
        success: int,
        failure: int,
        building_stats: dict[str, Counter[str]],
        last_error_type: str | None = None,
    ) -> None:
        with self._connect() as connection:
            connection.execute(
                """
                UPDATE collection_jobs SET finished_at = ?, status = ?,
                    processed_rooms = ?, success_count = ?, failure_count = ?,
                    duration_seconds = ?, last_error_type = ?
                WHERE id = ?
                """,
                (
                    iso_shanghai(),
                    status,
                    processed,
                    success,
                    failure,
                    max(0.0, time.monotonic() - started_monotonic),
                    last_error_type,
                    job_id,
                ),
            )
            connection.execute("DELETE FROM job_buildings WHERE job_id = ?", (job_id,))
            for code, counts in building_stats.items():
                connection.execute(
                    """
                    INSERT INTO job_buildings(
                        job_id, building_code, building_name,
                        room_count, success_count, failure_count
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    (
                        job_id,
                        code,
                        TARGET_BUILDINGS.get(code, code),
                        counts["total"],
                        counts["success"],
                        counts["failure"],
                    ),
                )

    def record_sample(
        self, slot_time: str, room: RoomEntry, result: QueryResult
    ) -> bool:
        """写入一个房间的轮次结果；同房间同整点重跑不会重复生成样本或事件。"""

        queried_at = iso_shanghai()
        query_result = "success" if result.success else "failure"
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            inserted = connection.execute(
                """
                INSERT OR IGNORE INTO samples(
                    room_code, building_code, building_name,
                    floor_code, floor_name, room_name,
                    slot_time, queried_at, balance_kwh, amount_yuan,
                    query_result, error_type, data_source
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'xiaofubao_hourly')
                """,
                (
                    room.room_code,
                    room.building_code,
                    room.building_name,
                    room.floor_code,
                    room.floor_name,
                    room.room_name,
                    slot_time,
                    queried_at,
                    result.balance_kwh,
                    result.amount_yuan,
                    query_result,
                    result.error_type,
                ),
            ).rowcount == 1
            sample = connection.execute(
                """
                SELECT * FROM samples WHERE room_code = ? AND slot_time = ?
                """,
                (room.room_code, slot_time),
            ).fetchone()

            # 进程中断后重跑同一轮次时，允许成功结果修复先前失败占位；已成功样本不改写。
            if not inserted and sample["query_result"] != "success" and result.success:
                connection.execute(
                    """
                    UPDATE samples SET queried_at = ?, balance_kwh = ?, amount_yuan = ?,
                        query_result = 'success', error_type = NULL
                    WHERE id = ?
                    """,
                    (queried_at, result.balance_kwh, result.amount_yuan, sample["id"]),
                )
                sample = connection.execute(
                    "SELECT * FROM samples WHERE id = ?", (sample["id"],)
                ).fetchone()
                inserted = True

            if inserted and sample["query_result"] == "success":
                previous = connection.execute(
                    """
                    SELECT id, queried_at, balance_kwh
                    FROM samples
                    WHERE room_code = ? AND query_result = 'success'
                      AND slot_time < ?
                    ORDER BY slot_time DESC LIMIT 1
                    """,
                    (room.room_code, slot_time),
                ).fetchone()
                if previous is not None:
                    before = float(previous["balance_kwh"])
                    after = float(sample["balance_kwh"])
                    delta = round(after - before, 4)
                    if abs(delta) >= 0.0001:
                        connection.execute(
                            """
                            INSERT OR IGNORE INTO change_events(
                                room_code, building_code, building_name,
                                previous_sample_id, current_sample_id,
                                previous_query_time, current_query_time,
                                before_balance, after_balance, delta_balance,
                                inferred_type
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                            (
                                room.room_code,
                                room.building_code,
                                room.building_name,
                                previous["id"],
                                sample["id"],
                                previous["queried_at"],
                                sample["queried_at"],
                                before,
                                after,
                                delta,
                                infer_change_type(delta),
                            ),
                        )
            return inserted

    def cleanup(self, now: dt.datetime | None = None) -> dict[str, int]:
        """在单个事务内清理过期明细；采集器只在一轮结束后调用。"""

        cutoff = iso_shanghai((now or shanghai_now()) - dt.timedelta(days=RETENTION_DAYS))
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            events = connection.execute(
                "DELETE FROM change_events WHERE current_query_time < ?", (cutoff,)
            ).rowcount
            samples = connection.execute(
                "DELETE FROM samples WHERE slot_time < ?", (cutoff,)
            ).rowcount
            jobs = connection.execute(
                "DELETE FROM collection_jobs WHERE slot_time < ?", (cutoff,)
            ).rowcount
        return {"samples": samples, "events": events, "jobs": jobs}

    def public_history(
        self,
        room_code: str,
        since: str,
        until: str,
        cursor: int,
        limit: int,
    ) -> dict[str, Any]:
        with self._connect() as connection:
            rows = connection.execute(
                """
                SELECT s.*,
                       ce.previous_query_time AS change_start_time,
                       ce.current_query_time AS change_end_time,
                       ce.delta_balance AS change_delta,
                       ce.inferred_type AS change_type
                FROM samples s
                LEFT JOIN change_events ce ON ce.current_sample_id = s.id
                WHERE s.room_code = ? AND s.slot_time >= ?
                      AND s.slot_time <= ? AND s.id > ?
                ORDER BY s.id ASC LIMIT ?
                """,
                (room_code, since, until, cursor, limit + 1),
            ).fetchall()
        has_more = len(rows) > limit
        page = rows[:limit]
        records = []
        for row in page:
            records.append(
                {
                    "sampleKey": hashlib.sha256(
                        f'{row["room_code"]}|{row["slot_time"]}'.encode("utf-8")
                    ).hexdigest(),
                    "roomCode": row["room_code"],
                    "buildingCode": row["building_code"],
                    "buildingName": row["building_name"],
                    "floorCode": row["floor_code"],
                    "floorName": row["floor_name"],
                    "roomName": row["room_name"],
                    "balanceKwh": row["balance_kwh"],
                    "amountYuan": row["amount_yuan"],
                    "queriedAt": row["queried_at"],
                    "queryResult": row["query_result"],
                    "errorType": row["error_type"],
                    "source": row["data_source"],
                    # 变化时间只能表达“发生区间”，不能被客户端描述为准确充值时刻。
                    "changeStartAt": row["change_start_time"],
                    "changeEndAt": row["change_end_time"],
                    "changeDeltaKwh": row["change_delta"],
                    "changeType": row["change_type"],
                }
            )
        return {
            "dataVersion": DATA_VERSION,
            "serverTime": iso_shanghai(),
            "timezone": "Asia/Shanghai",
            "roomCode": room_code,
            "records": records,
            "nextCursor": int(page[-1]["id"]) if page else cursor,
            "hasMore": has_more,
        }

    def collector_task_overview(self) -> dict[str, Any]:
        """读取实时任务总览；不接受分析日期，避免历史筛选污染当前任务状态。"""

        with self._connect() as connection:
            latest_job = connection.execute(
                "SELECT * FROM collection_jobs ORDER BY slot_time DESC LIMIT 1"
            ).fetchone()
            slot_time = str(latest_job["slot_time"]) if latest_job else ""
            building_rows = connection.execute(
                """
                WITH directory AS (
                    SELECT building_code, building_name,
                           SUM(CASE WHEN active = 1 THEN 1 ELSE 0 END) AS room_count,
                           SUM(CASE WHEN active = 1 AND meter_available = 0 THEN 1 ELSE 0 END)
                               AS no_meter_count
                    FROM rooms GROUP BY building_code, building_name
                ), current_round AS (
                    SELECT building_code, COUNT(*) AS processed_count,
                           SUM(CASE WHEN query_result = 'success' THEN 1 ELSE 0 END)
                               AS success_count,
                           SUM(CASE WHEN query_result != 'success' THEN 1 ELSE 0 END)
                               AS failure_count
                    FROM samples WHERE slot_time = ? GROUP BY building_code
                )
                SELECT d.*, COALESCE(c.processed_count, 0) AS processed_count,
                       COALESCE(c.success_count, 0) AS success_count,
                       COALESCE(c.failure_count, 0) AS failure_count
                FROM directory d LEFT JOIN current_round c USING(building_code)
                ORDER BY d.building_code
                """,
                (slot_time,),
            ).fetchall()
            failures = connection.execute(
                """
                SELECT COALESCE(error_type, 'unknown') AS error_type,
                       building_code, building_name, COUNT(*) AS count
                FROM samples
                WHERE slot_time = ? AND query_result != 'success'
                GROUP BY error_type, building_code, building_name
                ORDER BY count DESC, building_code
                """,
                (slot_time,),
            ).fetchall() if slot_time else []
            total_samples = int(connection.execute(
                "SELECT COUNT(*) FROM samples"
            ).fetchone()[0])

        building_list = [dict(row) for row in building_rows]
        valid_rooms = sum(int(row["room_count"] or 0) for row in building_list)
        no_meter = sum(int(row["no_meter_count"] or 0) for row in building_list)
        current_building = "—"
        if latest_job:
            if str(latest_job["status"]) == "running":
                processed = int(latest_job["processed_rooms"] or 0)
                cumulative = 0
                for row in building_list:
                    cumulative += int(row["room_count"] or 0)
                    if processed < cumulative:
                        current_building = str(row["building_name"])
                        break
            else:
                current_building = "本轮已完成"
        return {
            "latest_job": dict(latest_job) if latest_job else None,
            "buildings": building_list,
            "covered_buildings": sum(1 for row in building_list if row["room_count"]),
            "valid_rooms": valid_rooms,
            "no_meter_count": no_meter,
            "current_building": current_building,
            "total_samples": total_samples,
            "failures": [dict(row) for row in failures],
            "current_round": collection_round_number(slot_time),
            "round_total": COLLECTION_ROUND_TOTAL,
            "database_bytes": self.path.stat().st_size if self.path.exists() else 0,
        }

    def resolve_analysis_day(self, requested_day: str = "") -> str:
        """默认今天；今天无变化事件时回退到最近一个存在事件的上海自然日。"""

        today = shanghai_now().date().isoformat()
        if requested_day:
            return requested_day
        with self._connect() as connection:
            today_exists = connection.execute(
                """
                SELECT 1 FROM change_events
                WHERE current_query_time >= ? AND current_query_time < ? LIMIT 1
                """,
                _day_bounds(today),
            ).fetchone()
            if today_exists:
                return today
            latest = connection.execute(
                "SELECT MAX(substr(current_query_time, 1, 10)) FROM change_events"
            ).fetchone()[0]
        return str(latest or today)

    @staticmethod
    def _event_filters(
        day: str,
        building_code: str,
        room_code: str,
        event_type: str,
        interval_end_hour: int,
        snapshot_id: int,
    ) -> tuple[str, list[Any]]:
        conditions: list[str] = []
        values: list[Any] = []
        if day:
            start, end = _day_bounds(day)
            conditions.extend([
                "ce.current_query_time >= ?", "ce.current_query_time < ?"
            ])
            values.extend([start, end])
        if building_code:
            conditions.append("ce.building_code = ?")
            values.append(building_code)
        if room_code:
            conditions.append("ce.room_code = ?")
            values.append(room_code)
        if event_type in EVENT_TYPES:
            conditions.append("ce.inferred_type = ?")
            values.append(event_type)
        if 9 <= interval_end_hour <= 20:
            conditions.append("substr(ce.current_query_time, 12, 2) = ?")
            values.append(f"{interval_end_hour:02d}")
        if snapshot_id > 0:
            conditions.append("ce.id <= ?")
            values.append(snapshot_id)
        where = " WHERE " + " AND ".join(conditions) if conditions else ""
        return where, values

    def collector_events(
        self,
        *,
        day: str = "",
        building_code: str = "",
        room_code: str = "",
        event_type: str = "",
        interval_end_hour: int = 0,
        event_sort: str = "time_desc",
        page: int = 1,
        page_size: int = 100,
        snapshot_id: int = 0,
    ) -> dict[str, Any]:
        """稳定分页读取事件；snapshot_id 隔离浏览期间新插入的采集事件。"""

        safe_sort = event_sort if event_sort in EVENT_SORT_SQL else "time_desc"
        safe_size = page_size if page_size in EVENT_PAGE_SIZES else 100
        # 两种金额排序本身就限定业务方向，避免与事件类型筛选组合出混合或矛盾结果。
        effective_type = event_type if event_type in EVENT_TYPES else ""
        if safe_sort == "recharge_amount_desc":
            effective_type = "充值"
        elif safe_sort == "consumption_amount_desc":
            effective_type = "用电消耗"

        base_where, base_values = self._event_filters(
            day, building_code, room_code, effective_type,
            interval_end_hour, 0,
        )
        with self._connect() as connection:
            if snapshot_id <= 0:
                snapshot_id = int(connection.execute(
                    f"SELECT COALESCE(MAX(ce.id), 0) FROM change_events ce{base_where}",
                    base_values,
                ).fetchone()[0])
            where, values = self._event_filters(
                day, building_code, room_code, effective_type,
                interval_end_hour, snapshot_id,
            )
            total = int(connection.execute(
                f"SELECT COUNT(*) FROM change_events ce{where}", values
            ).fetchone()[0])
            total_pages = max(1, math.ceil(total / safe_size))
            safe_page = min(max(1, page), total_pages)
            offset = (safe_page - 1) * safe_size
            rows = connection.execute(
                f"""
                SELECT ce.*,
                       COALESCE(NULLIF(s.floor_name, ''), NULLIF(r.floor_name, ''), '')
                           AS floor_name,
                       COALESCE(NULLIF(s.room_name, ''), NULLIF(r.room_name, ''), '')
                           AS room_name,
                       s.amount_yuan AS after_amount,
                       ps.amount_yuan AS before_amount,
                       (s.amount_yuan - ps.amount_yuan) AS delta_amount
                FROM change_events ce
                LEFT JOIN samples s ON s.id = ce.current_sample_id
                LEFT JOIN samples ps ON ps.id = ce.previous_sample_id
                LEFT JOIN rooms r ON r.room_code = ce.room_code
                {where}
                ORDER BY {EVENT_SORT_SQL[safe_sort]}
                LIMIT ? OFFSET ?
                """,
                [*values, safe_size, offset],
            ).fetchall()
        return {
            "events": [dict(row) for row in rows],
            "total": total,
            "page": safe_page,
            "page_size": safe_size,
            "total_pages": total_pages,
            "snapshot_id": snapshot_id,
            "event_type": effective_type,
            "sort": safe_sort,
        }

    def collector_distribution(
        self,
        day: str = "",
        building_code: str = "",
        room_code: str = "",
    ) -> dict[str, Any]:
        """按后一次采集小时聚合 08:00～20:00 的相邻轮次变化。"""

        resolved_day = self.resolve_analysis_day(day)
        where, values = self._event_filters(
            resolved_day, building_code, room_code, "", 0, 0
        )
        with self._connect() as connection:
            summary = connection.execute(
                f"""
                SELECT COUNT(*) AS total_count,
                       SUM(CASE WHEN ce.inferred_type = '充值' THEN 1 ELSE 0 END)
                           AS recharge_count,
                       SUM(CASE WHEN ce.inferred_type = '用电消耗' THEN 1 ELSE 0 END)
                           AS consumption_count,
                       SUM(CASE WHEN ce.inferred_type NOT IN ('充值', '用电消耗')
                           THEN 1 ELSE 0 END) AS abnormal_count
                FROM change_events ce{where}
                """,
                values,
            ).fetchone()
            rows = connection.execute(
                f"""
                SELECT CAST(substr(ce.current_query_time, 12, 2) AS INTEGER) AS end_hour,
                       COUNT(*) AS total_count,
                       SUM(CASE WHEN ce.inferred_type = '充值' THEN 1 ELSE 0 END)
                           AS recharge_count,
                       SUM(CASE WHEN ce.inferred_type = '用电消耗' THEN 1 ELSE 0 END)
                           AS consumption_count,
                       SUM(CASE WHEN ce.inferred_type NOT IN ('充值', '用电消耗')
                           THEN 1 ELSE 0 END) AS abnormal_count,
                       COALESCE(SUM(ce.delta_balance), 0) AS total_delta_kwh,
                       COALESCE(SUM(s.amount_yuan - ps.amount_yuan), 0)
                           AS total_delta_amount
                FROM change_events ce
                LEFT JOIN samples s ON s.id = ce.current_sample_id
                LEFT JOIN samples ps ON ps.id = ce.previous_sample_id
                {where}
                GROUP BY end_hour ORDER BY end_hour
                """,
                values,
            ).fetchall()
        lookup = {int(row["end_hour"]): dict(row) for row in rows}
        intervals = []
        for start_hour in range(COLLECTION_START_HOUR, COLLECTION_END_HOUR):
            end_hour = start_hour + 1
            row = lookup.get(end_hour, {})
            intervals.append({
                "start_hour": start_hour,
                "end_hour": end_hour,
                "total_count": int(row.get("total_count") or 0),
                "recharge_count": int(row.get("recharge_count") or 0),
                "consumption_count": int(row.get("consumption_count") or 0),
                "abnormal_count": int(row.get("abnormal_count") or 0),
                "total_delta_kwh": float(row.get("total_delta_kwh") or 0),
                "total_delta_amount": float(row.get("total_delta_amount") or 0),
            })
        return {
            "day": resolved_day,
            "intervals": intervals,
            "total": int(summary["total_count"] or 0),
            "recharge": int(summary["recharge_count"] or 0),
            "consumption": int(summary["consumption_count"] or 0),
            "abnormal": int(summary["abnormal_count"] or 0),
        }

    def collector_overview(
        self,
        day: str = "",
        building_code: str = "",
        room_code: str = "",
        event_sort: str = "time_desc",
    ) -> dict[str, Any]:
        """兼容内部旧调用的组合视图；新页面使用三个独立查询避免相互拖累。"""

        resolved_day = self.resolve_analysis_day(day)
        result = self.collector_task_overview()
        events = self.collector_events(
            day=resolved_day, building_code=building_code,
            room_code=room_code, event_sort=event_sort,
        )
        distribution = self.collector_distribution(
            resolved_day, building_code, room_code
        )
        result.update(events)
        result["distribution"] = distribution["intervals"]
        result["analysis_day"] = resolved_day
        return result


def infer_change_type(delta: float) -> str:
    if delta > 0:
        # 校付宝余额只有发生充值后才会上涨，正向变化直接使用用户可理解的确定分类。
        return "充值"
    # 一小时下降 100 度以上更可能是平台修正、表号变动或异常值，继续要求人工确认。
    if abs(delta) >= LARGE_NEGATIVE_CHANGE_KWH:
        return "待确认"
    return "用电消耗"


def collection_round_number(slot_time: str) -> int:
    """把 08:00～20:00 的任务时刻映射为第 1～13 轮，异常数据安全返回 0。"""

    if not slot_time:
        return 0
    try:
        hour = parse_iso(slot_time).hour
    except (TypeError, ValueError):
        return 0
    if hour < COLLECTION_START_HOUR or hour > COLLECTION_END_HOUR:
        return 0
    return hour - COLLECTION_START_HOUR + 1


class XiaofubaoClient:
    """限速、有限重试的校付宝只读客户端；单实例同一时刻最多一个请求。"""

    def __init__(
        self,
        shiro_jid: str,
        *,
        request_interval: float = 0.25,
        timeout: float = 12.0,
        retries: int = 2,
    ) -> None:
        self.cookie = "shiroJID=" + shiro_jid.strip()
        self.request_interval = max(0.05, request_interval)
        self.timeout = max(2.0, timeout)
        self.retries = min(4, max(0, retries))
        self._rate_lock = threading.Lock()
        self._last_request = 0.0

    def query_buildings(self) -> list[dict[str, str]]:
        return self._directory("queryBuilding", {}, "buildingCode", "buildingName")

    def query_floors(self, building_code: str) -> list[dict[str, str]]:
        return self._directory(
            "queryFloor", {"buildingCode": building_code}, "floorCode", "floorName"
        )

    def query_rooms(
        self, building_code: str, floor_code: str
    ) -> list[dict[str, str]]:
        return self._directory(
            "queryRoom",
            {"buildingCode": building_code, "floorCode": floor_code},
            "roomCode",
            "roomName",
        )

    def query_balance(self, room: RoomEntry) -> QueryResult:
        try:
            root = self._request(
                "queryRoomSurplus",
                {
                    "buildingCode": room.building_code,
                    "floorCode": room.floor_code,
                    "roomCode": room.room_code,
                },
            )
            data = root.get("data")
            if not isinstance(data, dict) or "surplus" not in data or "amount" not in data:
                raise RemoteError("invalid_response")
            surplus = float(data["surplus"])
            amount = float(data["amount"])
            # 校付宝会用负数表示已经欠费，Android 实时查询也一直允许负余额。
            # 这里只拒绝 NaN/Infinity，不能把真实欠费房间误标成接口异常。
            if not math.isfinite(surplus) or not math.isfinite(amount):
                raise RemoteError("invalid_response")
            return QueryResult(True, surplus, amount)
        except RemoteError as exception:
            return QueryResult(False, None, None, exception.error_type)

    def _directory(
        self,
        endpoint: str,
        fields: dict[str, str],
        code_field: str,
        name_field: str,
    ) -> list[dict[str, str]]:
        root = self._request(endpoint, fields)
        rows = root.get("rows")
        if not isinstance(rows, list):
            raise RemoteError("invalid_response")
        result: dict[str, dict[str, str]] = {}
        for item in rows:
            if not isinstance(item, dict):
                continue
            code = str(item.get(code_field, "")).strip()
            name = str(item.get(name_field, "")).strip()
            if code and name:
                result[code] = {"code": code, "name": name}
        if not result:
            raise RemoteError("empty_directory")
        return list(result.values())

    def _request(self, endpoint: str, fields: dict[str, str]) -> dict[str, Any]:
        last_error = "network"
        for attempt in range(self.retries + 1):
            try:
                return self._request_once(endpoint, fields)
            except RemoteError as exception:
                last_error = exception.error_type
                # 授权和明确无电表错误重试没有意义；超时、5xx 和临时网络错误才退避。
                if last_error in {"auth", "no_meter", "invalid_response"}:
                    raise
            if attempt < self.retries:
                time.sleep(min(4.0, 0.5 * (2 ** attempt)))
        raise RemoteError(last_error)

    def _request_once(self, endpoint: str, fields: dict[str, str]) -> dict[str, Any]:
        payload = {"areaId": AREA_ID, "platform": PLATFORM}
        payload.update(fields)
        body = urllib.parse.urlencode(payload).encode("utf-8")
        request = urllib.request.Request(
            DIRECTORY_BASE_URL + endpoint,
            data=body,
            method="POST",
            headers={
                "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
                "X-Requested-With": "XMLHttpRequest",
                "Origin": "https://application.xiaofubao.com",
                "Referer": "https://application.xiaofubao.com/",
                "Cookie": self.cookie,
                "User-Agent": "JiangliElectricityCollector/1.0",
            },
        )
        self._wait_for_rate_limit()
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read(1_000_000)
        except urllib.error.HTTPError as exception:
            if exception.code in (401, 403):
                raise RemoteError("auth") from exception
            raise RemoteError("http_error") from exception
        except (urllib.error.URLError, socket.timeout, TimeoutError) as exception:
            reason = getattr(exception, "reason", None)
            error_type = "timeout" if isinstance(reason, socket.timeout) else "network"
            raise RemoteError(error_type) from exception
        try:
            root = json.loads(raw)
        except (json.JSONDecodeError, UnicodeError) as exception:
            raise RemoteError("invalid_response") from exception
        if not isinstance(root, dict):
            raise RemoteError("invalid_response")
        if not root.get("success") or int(root.get("statusCode", -1)) != 0:
            message = str(root.get("message", ""))
            if any(word in message for word in ("登录", "授权", "会话")):
                raise RemoteError("auth")
            if any(word in message for word in ("电表", "未绑定", "不存在")):
                raise RemoteError("no_meter")
            raise RemoteError("remote_rejected")
        return root

    def _wait_for_rate_limit(self) -> None:
        with self._rate_lock:
            remaining = self.request_interval - (time.monotonic() - self._last_request)
            if remaining > 0:
                time.sleep(remaining)
            self._last_request = time.monotonic()


class PublicHistoryCollector:
    """完整目录同步和单轮采集；非阻塞锁保证同进程内任务绝不叠加。"""

    def __init__(self, store: PublicHistoryStore, client: XiaofubaoClient) -> None:
        self.store = store
        self.client = client
        self._round_lock = threading.Lock()

    def sync_directory(self) -> dict[str, Any]:
        synced_at = iso_shanghai()
        result: dict[str, Any] = {"success": {}, "failures": {}}
        buildings = {
            item["code"]: item for item in self.client.query_buildings()
        }
        for building_code, configured_name in TARGET_BUILDINGS.items():
            if building_code not in buildings:
                result["failures"][building_code] = "building_missing"
                continue
            try:
                entries: dict[str, RoomEntry] = {}
                floors = self.client.query_floors(building_code)
                for floor in floors:
                    for room in self.client.query_rooms(building_code, floor["code"]):
                        room_code = room["code"]
                        # 精确校验代码层级，第三方脏数据不能跨楼栋进入采集范围。
                        if (
                            len(room_code) != 15
                            or not room_code.isdigit()
                            or not room_code.startswith(building_code)
                            or not floor["code"].startswith(building_code)
                            or not room_code.startswith(floor["code"])
                        ):
                            continue
                        entries[room_code] = RoomEntry(
                            room_code,
                            building_code,
                            configured_name,
                            floor["code"],
                            floor["name"],
                            room["name"],
                        )
                if not entries:
                    raise RemoteError("empty_directory")
                self.store.replace_building_directory(
                    building_code, list(entries.values()), synced_at
                )
                result["success"][building_code] = len(entries)
            except RemoteError as exception:
                result["failures"][building_code] = exception.error_type
        self.store.set_metadata(
            "last_directory_sync",
            json.dumps(
                {
                    "day": shanghai_now().date().isoformat(),
                    "finishedAt": iso_shanghai(),
                    **result,
                },
                ensure_ascii=False,
                separators=(",", ":"),
            ),
        )
        return result

    def directory_sync_due(self) -> bool:
        try:
            saved = json.loads(self.store.metadata("last_directory_sync", "{}"))
            return saved.get("day") != shanghai_now().date().isoformat()
        except json.JSONDecodeError:
            return True

    def run_round(self, slot: dt.datetime) -> bool:
        if not self._round_lock.acquire(blocking=False):
            return False
        started = time.monotonic()
        job_id: int | None = None
        processed = success = failure = 0
        building_stats: dict[str, Counter[str]] = defaultdict(Counter)
        slot_time = iso_shanghai(slot.replace(minute=0, second=0, microsecond=0))
        try:
            if self.directory_sync_due() or not self.store.active_rooms():
                self.sync_directory()
            rooms = self.store.active_rooms()
            job_id = self.store.start_job(slot_time, len(rooms))
            if not rooms:
                self.store.finish_job(
                    job_id, "failed", started, 0, 0, 0, building_stats, "empty_directory"
                )
                return False
            for room in rooms:
                result = self.client.query_balance(room)
                self.store.record_sample(slot_time, room, result)
                self.store.mark_room_error(room.room_code, result.error_type)
                processed += 1
                building_stats[room.building_code]["total"] += 1
                if result.success:
                    success += 1
                    building_stats[room.building_code]["success"] += 1
                else:
                    failure += 1
                    building_stats[room.building_code]["failure"] += 1
                if processed % 10 == 0 or processed == len(rooms):
                    self.store.update_job(job_id, processed, success, failure)
            self.store.finish_job(
                job_id,
                "completed",
                started,
                processed,
                success,
                failure,
                building_stats,
            )
            return True
        except Exception as exception:
            error_type = (
                exception.error_type
                if isinstance(exception, RemoteError)
                else type(exception).__name__.lower()
            )
            # 即使每日目录同步在创建房间任务前失败，也写入一个可见的失败轮次，
            # 让开发者后台能区分“调度没启动”和“第三方目录失败”。
            if job_id is None:
                job_id = self.store.start_job(slot_time, 0)
            self.store.finish_job(
                job_id,
                "failed",
                started,
                processed,
                success,
                failure,
                building_stats,
                error_type,
            )
            return False
        finally:
            try:
                # 成功或失败轮次结束后都执行安全批量清理；此时没有当前采集事务。
                self.store.cleanup()
            except sqlite3.Error:
                pass
            self._round_lock.release()


class CollectorScheduler:
    """北京时间 08:00–20:00 整点调度；重启后十分钟内可补跑当前轮次。"""

    def __init__(self, collector: PublicHistoryCollector) -> None:
        self.collector = collector
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._last_dispatched = ""

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._thread = threading.Thread(
            target=self._loop, name="public-history-scheduler", daemon=True
        )
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=5)

    def _loop(self) -> None:
        while not self._stop.wait(15):
            now = shanghai_now()
            if not (8 <= now.hour <= 20 and now.minute < 10):
                continue
            slot = now.replace(minute=0, second=0, microsecond=0)
            slot_key = iso_shanghai(slot)
            if slot_key == self._last_dispatched:
                continue
            status = self.collector.store.job_status(slot_key)
            if status == "completed":
                self._last_dispatched = slot_key
                continue
            self._last_dispatched = slot_key
            threading.Thread(
                target=self.collector.run_round,
                args=(slot,),
                name=f"public-history-{now.hour:02d}",
                daemon=True,
            ).start()
