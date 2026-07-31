#!/usr/bin/env python3
"""江理电费管家更新、匿名统计与公共房间历史服务。

只使用 Python 标准库，监听 127.0.0.1，由 Nginx 反向代理。匿名统计库不会存储 Android
ID、房间配置、手机号或设备硬件标识；客户端摘要还会再经过服务器 HMAC。独立公共历史库
只保存白名单楼栋的公共目录和余额采样，不接收用户备注、提醒阈值或充值记录。
"""

from __future__ import annotations

import base64
import datetime as dt
import gzip
import hashlib
import hmac
import html
import json
import os
import re
import secrets
import sqlite3
import threading
import time
import urllib.parse
from collections import defaultdict
from http import HTTPStatus
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

try:
    # 作为 package 执行单元测试时使用相对导入；生产环境直接运行 server.py 时使用同目录导入。
    from .public_history import (
        CollectorScheduler,
        EVENT_PAGE_SIZES,
        EVENT_SORT_SQL,
        EVENT_TYPES,
        PublicHistoryCollector,
        PublicHistoryStore,
        XiaofubaoClient,
        parse_iso,
    )
except ImportError:  # pragma: no cover - 生产脚本入口
    from public_history import (
        CollectorScheduler,
        EVENT_PAGE_SIZES,
        EVENT_SORT_SQL,
        EVENT_TYPES,
        PublicHistoryCollector,
        PublicHistoryStore,
        XiaofubaoClient,
        parse_iso,
    )


SHANGHAI = ZoneInfo("Asia/Shanghai")
IDENTITY_PATTERN = re.compile(
    r"^(?:[0-9a-f]{64}|"
    r"[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})$",
    re.IGNORECASE,
)
APK_NAME_PATTERN = re.compile(r"^[A-Za-z0-9._-]+\.apk$")
RELEASE_APK_PATTERN = re.compile(
    r"^electricity-reminder-(\d+)\.(\d+)\.(\d+)\.apk$"
)
MAX_BODY_BYTES = 8 * 1024
SESSION_SECONDS = 8 * 60 * 60
PBKDF2_ALGORITHM = "pbkdf2_sha256"


class Settings:
    """从进程环境加载配置，敏感值不写入项目或日志。"""

    def __init__(self) -> None:
        self.host = os.environ.get("APP_HOST", "127.0.0.1")
        self.port = int(os.environ.get("APP_PORT", "8080"))
        self.database_path = Path(
            os.environ.get("DATABASE_PATH", "./data/analytics.sqlite3")
        )
        self.manifest_path = Path(
            os.environ.get(
                "UPDATE_MANIFEST_PATH", "./public/update.json"
            )
        )
        self.download_dir = Path(
            os.environ.get(
                "DOWNLOAD_DIRECTORY", "./public/downloads"
            )
        )
        configured_password_hash = _required_env("ADMIN_PASSWORD_HASH")
        self.password_hash_path = Path(
            os.environ.get(
                "ADMIN_PASSWORD_HASH_PATH", "./data/admin_password_hash"
            )
        )
        # 后台修改密码后会把新摘要写入可持久化目录。环境变量继续作为首次部署和
        # 文件损坏时的安全回退，不需要让 Web 进程拥有 /etc 写权限。
        self.password_hash = configured_password_hash
        if self.password_hash_path.is_file():
            saved_hash = self.password_hash_path.read_text(encoding="utf-8").strip()
            if saved_hash.startswith(PBKDF2_ALGORITHM + "$"):
                self.password_hash = saved_hash
        self.session_secret = _required_env("SESSION_SECRET").encode("utf-8")
        self.telemetry_key = _required_env("TELEMETRY_HMAC_KEY").encode("utf-8")
        # 公共采样与匿名统计使用不同数据库，便于独立备份、限权和容量审计。
        self.public_history_database_path = Path(
            os.environ.get(
                "PUBLIC_HISTORY_DATABASE_PATH",
                str(self.database_path.with_name("public_history.sqlite3")),
            )
        )
        self.collector_enabled = os.environ.get(
            "PUBLIC_HISTORY_COLLECTOR_ENABLED", "false"
        ).strip().lower() in {"1", "true", "yes", "on"}
        # JID 只从服务器环境读取，不写入源码、数据库、后台页面或日志。
        self.xiaofubao_shiro_jid = os.environ.get(
            "XIAOFUBAO_SHIRO_JID", ""
        ).strip()
        self.collector_request_interval = float(
            os.environ.get("COLLECTOR_REQUEST_INTERVAL_SECONDS", "0.25")
        )
        self.collector_timeout = float(
            os.environ.get("COLLECTOR_REQUEST_TIMEOUT_SECONDS", "12")
        )


def _required_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"缺少必要环境变量 {name}")
    return value


def utc_now() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


def iso_utc_now() -> str:
    return utc_now().isoformat(timespec="seconds")


def shanghai_day() -> str:
    return dt.datetime.now(SHANGHAI).date().isoformat()


def hash_identity(key: bytes, category: str, value: str) -> str:
    """加入类别前缀，避免同一输入在不同统计用途下产生相同摘要。"""

    message = f"{category}:{value}".encode("utf-8")
    return hmac.new(key, message, hashlib.sha256).hexdigest()


def verify_password(password: str, encoded_hash: str) -> bool:
    """验证 PBKDF2 密码；统一返回 False，不向登录页泄露配置格式细节。"""

    try:
        algorithm, iterations_text, salt_hex, expected_hex = encoded_hash.split("$", 3)
        if algorithm != PBKDF2_ALGORITHM:
            return False
        iterations = int(iterations_text)
        salt = bytes.fromhex(salt_hex)
        expected = bytes.fromhex(expected_hex)
        actual = hashlib.pbkdf2_hmac(
            "sha256", password.encode("utf-8"), salt, iterations, dklen=len(expected)
        )
        return hmac.compare_digest(actual, expected)
    except (ValueError, TypeError):
        return False


def encode_password(password: str, *, iterations: int = 310_000) -> str:
    """供部署脚本或测试生成环境变量；服务器只保存返回值，不保存原密码。"""

    salt = secrets.token_bytes(16)
    derived = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt, iterations, dklen=32
    )
    return f"{PBKDF2_ALGORITHM}${iterations}${salt.hex()}${derived.hex()}"


class AnalyticsStore:
    """SQLite 聚合存储；每次请求独立连接，WAL 模式适合轻量并发读写。"""

    def __init__(self, path: Path, telemetry_key: bytes) -> None:
        self.path = path
        self.telemetry_key = telemetry_key
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._initialize()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.path, timeout=5)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        return connection

    def _initialize(self) -> None:
        with self._connect() as connection:
            connection.execute("PRAGMA journal_mode = WAL")
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS installations (
                    install_hash TEXT PRIMARY KEY,
                    first_seen TEXT NOT NULL,
                    last_seen TEXT NOT NULL,
                    current_version_code INTEGER NOT NULL,
                    current_version_name TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS daily_active (
                    day TEXT NOT NULL,
                    install_hash TEXT NOT NULL,
                    version_code INTEGER NOT NULL,
                    last_seen TEXT NOT NULL,
                    PRIMARY KEY (day, install_hash)
                );

                CREATE TABLE IF NOT EXISTS downloads (
                    version_code INTEGER NOT NULL,
                    identity_hash TEXT NOT NULL,
                    first_seen TEXT NOT NULL,
                    last_seen TEXT NOT NULL,
                    request_count INTEGER NOT NULL DEFAULT 1,
                    PRIMARY KEY (version_code, identity_hash)
                );

                CREATE INDEX IF NOT EXISTS idx_daily_active_day
                    ON daily_active(day);
                CREATE INDEX IF NOT EXISTS idx_installations_version
                    ON installations(current_version_code);
                CREATE INDEX IF NOT EXISTS idx_downloads_version
                    ON downloads(version_code);
                """
            )

    def record_heartbeat(
        self,
        install_id: str,
        version_code: int,
        version_name: str,
        event_day: str = "",
        historical: bool = False,
    ) -> None:
        install_hash = hash_identity(
            self.telemetry_key, "installation", install_id.lower()
        )
        now = iso_utc_now()
        day = shanghai_day()
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO installations(
                    install_hash, first_seen, last_seen,
                    current_version_code, current_version_name
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(install_hash) DO UPDATE SET
                    last_seen = excluded.last_seen,
                    current_version_code = excluded.current_version_code,
                    current_version_name = excluded.current_version_name
                """,
                (install_hash, now, now, version_code, version_name),
            )
            # eventDay 是辅助审计字段，绝不用于决定服务器的统计日期。只有客户端明确标记
            # 为本地失败队列中的历史补发时才跳过今日 DAU；正常心跳始终使用服务器上海日，
            # 因而用户修改手机日期也不会造成重复或漏计。
            if historical:
                return
            connection.execute(
                """
                INSERT INTO daily_active(day, install_hash, version_code, last_seen)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(day, install_hash) DO UPDATE SET
                    version_code = excluded.version_code,
                    last_seen = excluded.last_seen
                """,
                (day, install_hash, version_code, now),
            )

    def record_download(
        self,
        version_code: int,
        install_id: str | None,
        network_fingerprint: str,
    ) -> None:
        if install_id and IDENTITY_PATTERN.fullmatch(install_id):
            identity_hash = hash_identity(
                self.telemetry_key, "download-install", install_id.lower()
            )
        else:
            # 旧版 App 没有发送匿名安装 ID，只能用 IP + UA 的 HMAC 作近似去重。
            # 原始 IP 和 User-Agent 不进入数据库。
            identity_hash = hash_identity(
                self.telemetry_key, "download-network", network_fingerprint
            )
        now = iso_utc_now()
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO downloads(
                    version_code, identity_hash, first_seen, last_seen, request_count
                ) VALUES (?, ?, ?, ?, 1)
                ON CONFLICT(version_code, identity_hash) DO UPDATE SET
                    last_seen = excluded.last_seen,
                    request_count = downloads.request_count + 1
                """,
                (version_code, identity_hash, now, now),
            )

    def dashboard_stats(self, latest_version_code: int) -> dict[str, Any]:
        today = dt.datetime.now(SHANGHAI).date()
        first_day = today - dt.timedelta(days=13)
        active_7_start = today - dt.timedelta(days=6)
        with self._connect() as connection:
            total_users = connection.execute(
                "SELECT COUNT(*) FROM installations"
            ).fetchone()[0]
            today_active = connection.execute(
                "SELECT COUNT(*) FROM daily_active WHERE day = ?", (today.isoformat(),)
            ).fetchone()[0]
            active_7 = connection.execute(
                """
                SELECT COUNT(DISTINCT install_hash)
                FROM daily_active WHERE day >= ?
                """,
                (active_7_start.isoformat(),),
            ).fetchone()[0]
            latest_installed = connection.execute(
                """
                SELECT COUNT(*) FROM installations
                WHERE current_version_code = ?
                """,
                (latest_version_code,),
            ).fetchone()[0]
            download_row = connection.execute(
                """
                SELECT COUNT(*) AS unique_count,
                       COALESCE(SUM(request_count), 0) AS request_count
                FROM downloads WHERE version_code = ?
                """,
                (latest_version_code,),
            ).fetchone()
            daily_rows = connection.execute(
                """
                SELECT day, COUNT(*) AS count
                FROM daily_active
                WHERE day >= ?
                GROUP BY day ORDER BY day
                """,
                (first_day.isoformat(),),
            ).fetchall()
            version_rows = connection.execute(
                """
                SELECT current_version_code, current_version_name, COUNT(*) AS count
                FROM installations
                GROUP BY current_version_code, current_version_name
                ORDER BY current_version_code DESC
                """
            ).fetchall()
            last_seen = connection.execute(
                "SELECT MAX(last_seen) FROM installations"
            ).fetchone()[0]

        daily_lookup = {row["day"]: row["count"] for row in daily_rows}
        daily = []
        for offset in range(14):
            day = first_day + dt.timedelta(days=offset)
            daily.append((day.isoformat(), daily_lookup.get(day.isoformat(), 0)))
        versions = [
            (row["current_version_code"], row["current_version_name"], row["count"])
            for row in version_rows
        ]
        return {
            "total_users": total_users,
            "today_active": today_active,
            "active_7": active_7,
            "latest_installed": latest_installed,
            "latest_download_unique": download_row["unique_count"],
            "latest_download_requests": download_row["request_count"],
            "daily": daily,
            "versions": versions,
            "last_seen": last_seen,
            "database_bytes": self.path.stat().st_size if self.path.exists() else 0,
        }


class LoginLimiter:
    """仅在内存中保存近期失败次数；服务重启会自动清空，不持久化 IP。"""

    def __init__(self) -> None:
        self._failures: dict[str, list[float]] = defaultdict(list)
        self._blocked_until: dict[str, float] = {}
        self._lock = threading.Lock()

    def is_blocked(self, key: str) -> bool:
        now = time.monotonic()
        with self._lock:
            until = self._blocked_until.get(key, 0)
            if until <= now:
                self._blocked_until.pop(key, None)
                return False
            return True

    def failure(self, key: str) -> None:
        now = time.monotonic()
        with self._lock:
            recent = [value for value in self._failures[key] if now - value < 600]
            recent.append(now)
            self._failures[key] = recent
            if len(recent) >= 5:
                self._blocked_until[key] = now + 900
                self._failures[key] = []

    def success(self, key: str) -> None:
        with self._lock:
            self._failures.pop(key, None)
            self._blocked_until.pop(key, None)


class PublicReadLimiter:
    """公共历史按来源 IP 做内存限速；不持久化 IP，也不影响其他 API。"""

    def __init__(self, maximum: int = 120, window_seconds: float = 60.0) -> None:
        self.maximum = maximum
        self.window_seconds = window_seconds
        self._requests: dict[str, list[float]] = defaultdict(list)
        self._lock = threading.Lock()

    def allow(self, key: str) -> bool:
        now = time.monotonic()
        with self._lock:
            recent = [
                value for value in self._requests[key]
                if now - value < self.window_seconds
            ]
            if len(recent) >= self.maximum:
                self._requests[key] = recent
                return False
            recent.append(now)
            self._requests[key] = recent
            return True


class ElecService:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.store = AnalyticsStore(settings.database_path, settings.telemetry_key)
        self.login_limiter = LoginLimiter()
        self.public_read_limiter = PublicReadLimiter()
        self.started_at = utc_now()
        self._password_lock = threading.Lock()
        # 会话代次不落盘：修改密码或重启服务都会让旧 Cookie 立即失效。
        self._session_generation = secrets.token_urlsafe(16)
        public_history_path = getattr(
            settings,
            "public_history_database_path",
            settings.database_path.with_name("public_history.sqlite3"),
        )
        self.public_history_store: PublicHistoryStore | None
        try:
            self.public_history_store = PublicHistoryStore(public_history_path)
        except (OSError, sqlite3.Error):
            # 公共历史是附加能力。独立数据库损坏或目录只读时，更新、下载、匿名统计和
            # 后台登录仍必须正常启动；公共接口单独返回 503。
            self.public_history_store = None
        self.collector_scheduler: CollectorScheduler | None = None
        if (
            self.public_history_store is not None
            and getattr(settings, "collector_enabled", False)
            and getattr(settings, "xiaofubao_shiro_jid", "")
        ):
            collector_client = XiaofubaoClient(
                settings.xiaofubao_shiro_jid,
                request_interval=getattr(
                    settings, "collector_request_interval", 0.25
                ),
                timeout=getattr(settings, "collector_timeout", 12.0),
                retries=2,
            )
            collector = PublicHistoryCollector(
                self.public_history_store, collector_client
            )
            self.collector_scheduler = CollectorScheduler(collector)
            self.collector_scheduler.start()

    def load_manifest(self) -> dict[str, Any]:
        with self.settings.manifest_path.open("r", encoding="utf-8") as stream:
            data = json.load(stream)
        return data

    def current_download(self) -> tuple[str, int, Path]:
        manifest = self.load_manifest()
        apk_url = urllib.parse.urlsplit(str(manifest["apkUrl"]))
        filename = Path(apk_url.path).name
        if not APK_NAME_PATTERN.fullmatch(filename):
            raise ValueError("清单中的 APK 文件名无效")
        file_path = self.settings.download_dir / filename
        if not file_path.is_file():
            raise FileNotFoundError(filename)
        return filename, int(manifest["versionCode"]), file_path

    def available_download(self, requested: str) -> tuple[str, int, Path]:
        """允许仍持有旧清单的客户端完成旧版下载，同时只统计当前最新版指标。"""

        current_name, current_code, current_path = self.current_download()
        if requested == current_name:
            return current_name, current_code, current_path
        old_path = self.settings.download_dir / requested
        if old_path.is_file():
            # 旧文件没有可靠的 versionCode 映射，统一记入 0，不污染“最新版下载”指标。
            return requested, 0, old_path
        raise FileNotFoundError(requested)

    def available_releases(self) -> list[dict[str, Any]]:
        """列出服务器实际保留的版本，供后台提供最近三版直接下载入口。"""

        releases = []
        for path in self.settings.download_dir.glob("*.apk"):
            match = RELEASE_APK_PATTERN.fullmatch(path.name)
            if not match or not path.is_file():
                continue
            version = tuple(int(value) for value in match.groups())
            releases.append(
                {
                    "filename": path.name,
                    "version": ".".join(str(value) for value in version),
                    "sort": version,
                    "bytes": path.stat().st_size,
                    "url": "/downloads/" + urllib.parse.quote(path.name),
                }
            )
        releases.sort(key=lambda item: item["sort"], reverse=True)
        return releases[:3]

    def create_session(self) -> str:
        expiry = int(time.time()) + SESSION_SECONDS
        payload = (
            f"{expiry}:{self._session_generation}:{secrets.token_urlsafe(24)}"
        )
        encoded = _b64encode(payload.encode("utf-8"))
        signature = hmac.new(
            self.settings.session_secret, encoded.encode("ascii"), hashlib.sha256
        ).hexdigest()
        return f"{encoded}.{signature}"

    def verify_session(self, token: str) -> bool:
        try:
            encoded, signature = token.split(".", 1)
            expected = hmac.new(
                self.settings.session_secret, encoded.encode("ascii"), hashlib.sha256
            ).hexdigest()
            if not hmac.compare_digest(signature, expected):
                return False
            payload = _b64decode(encoded).decode("utf-8")
            expiry_text, generation, _nonce = payload.split(":", 2)
            return generation == self._session_generation \
                and int(expiry_text) >= int(time.time())
        except (ValueError, UnicodeError):
            return False

    def change_password(self, new_password: str) -> None:
        """原子保存新 PBKDF2 摘要，并立即废止所有已经签发的后台会话。"""

        encoded = encode_password(new_password)
        with self._password_lock:
            password_path = getattr(self.settings, "password_hash_path", None)
            if password_path is not None:
                password_path.parent.mkdir(parents=True, exist_ok=True)
                temporary = password_path.with_name(password_path.name + ".tmp")
                temporary.write_text(encoded + "\n", encoding="utf-8")
                os.chmod(temporary, 0o600)
                os.replace(temporary, password_path)
            self.settings.password_hash = encoded
            self._session_generation = secrets.token_urlsafe(16)

    def csrf_token(self, session_token: str) -> str:
        return hmac.new(
            self.settings.session_secret,
            f"csrf:{session_token}".encode("utf-8"),
            hashlib.sha256,
        ).hexdigest()

    def shutdown(self) -> None:
        if self.collector_scheduler is not None:
            self.collector_scheduler.stop()


def _b64encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def _b64decode(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


class ElecHttpServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


class Handler(BaseHTTPRequestHandler):
    server_version = "ElecAdmin/1.0"
    sys_version = ""
    protocol_version = "HTTP/1.1"
    service: ElecService

    def do_HEAD(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        path = urllib.parse.urlsplit(self.path).path
        if path.startswith("/downloads/"):
            self._download(path, count=False)
        elif path == "/healthz":
            self._send_bytes(HTTPStatus.OK, b"", "text/plain; charset=utf-8")
        else:
            self._send_bytes(HTTPStatus.NOT_FOUND, b"", "text/plain; charset=utf-8")

    def do_GET(self) -> None:  # noqa: N802
        path = urllib.parse.urlsplit(self.path).path
        if path == "/healthz":
            self._send_json(HTTPStatus.OK, {"status": "ok"})
        elif path == "/api/v1/public-history":
            self._public_history()
        elif path == "/admin":
            self._redirect("/admin/")
        elif path == "/admin/login":
            if self._session_token():
                self._redirect("/admin/")
            else:
                query = urllib.parse.parse_qs(
                    urllib.parse.urlsplit(self.path).query
                )
                notice = (
                    "后台密码已修改，请使用新密码重新登录。"
                    if query.get("passwordChanged") == ["1"] else ""
                )
                self._send_html(HTTPStatus.OK, login_page(notice=notice))
        elif path == "/admin/":
            session = self._session_token()
            if not session:
                self._redirect("/admin/login")
                return
            try:
                manifest = self.service.load_manifest()
                stats = self.service.store.dashboard_stats(int(manifest["versionCode"]))
                releases = self.service.available_releases()
                self._send_html(
                    HTTPStatus.OK,
                    dashboard_page(
                        manifest,
                        stats,
                        releases,
                        self.service.csrf_token(session),
                        self.service.started_at,
                    ),
                )
            except (OSError, ValueError, KeyError, json.JSONDecodeError) as exception:
                self._send_html(
                    HTTPStatus.INTERNAL_SERVER_ERROR,
                    error_page("后台数据读取失败", str(exception)),
                )
        elif path == "/admin/password":
            session = self._session_token()
            if not session:
                self._redirect("/admin/login")
            else:
                self._send_html(
                    HTTPStatus.OK,
                    password_page(self.service.csrf_token(session)),
                )
        elif path in {
            "/admin/collector",
            "/admin/collector/events",
            "/admin/collector/distribution",
        }:
            session = self._session_token()
            if not session:
                self._redirect("/admin/login")
                return
            try:
                if self.service.public_history_store is None:
                    raise sqlite3.OperationalError("公共历史数据库暂不可用")
                parameters = _collector_parameters(self.path)
                store = self.service.public_history_store
                if path == "/admin/collector/events":
                    event_page = store.collector_events(**parameters["events"])
                    self._send_json(HTTPStatus.OK, {
                        "html": collector_events_fragment(event_page),
                        "page": event_page["page"],
                        "pageSize": event_page["page_size"],
                        "total": event_page["total"],
                        "totalPages": event_page["total_pages"],
                        "snapshotId": event_page["snapshot_id"],
                        "eventType": event_page["event_type"],
                    })
                    return
                if path == "/admin/collector/distribution":
                    distribution = store.collector_distribution(
                        parameters["day"], parameters["building_code"],
                        parameters["room_code"],
                    )
                    self._send_json(HTTPStatus.OK, {
                        "html": collector_distribution_fragment(
                            distribution, parameters["interval_end_hour"]
                        ),
                        "date": distribution["day"],
                    })
                    return

                task = store.collector_task_overview()
                distribution = store.collector_distribution(
                    parameters["day"], parameters["building_code"],
                    parameters["room_code"],
                )
                # 首次进入没有指定日期时，事件列表与分布统一使用已解析出的有效日期。
                if not parameters["day"]:
                    parameters["day"] = distribution["day"]
                    parameters["events"]["day"] = distribution["day"]
                event_page = store.collector_events(**parameters["events"])
                self._send_html(HTTPStatus.OK, collector_page(
                    task, event_page, distribution, parameters
                ))
            except (sqlite3.Error, ValueError) as exception:
                self._send_html(
                    HTTPStatus.INTERNAL_SERVER_ERROR,
                    error_page("云端采集数据读取失败", str(exception)),
                )
        elif path.startswith("/downloads/"):
            self._download(path, count=True)
        else:
            self._send_bytes(
                HTTPStatus.NOT_FOUND, b"not found\n", "text/plain; charset=utf-8"
            )

    def do_POST(self) -> None:  # noqa: N802
        path = urllib.parse.urlsplit(self.path).path
        if path == "/api/v1/heartbeat":
            self._heartbeat()
        elif path == "/admin/login":
            self._login()
        elif path == "/admin/logout":
            self._logout()
        elif path == "/admin/password":
            self._change_password()
        else:
            self._send_bytes(
                HTTPStatus.NOT_FOUND, b"not found\n", "text/plain; charset=utf-8"
            )

    def _heartbeat(self) -> None:
        try:
            payload = self._read_json()
            install_id = str(payload.get("installId", "")).strip()
            version_code = int(payload.get("versionCode", 0))
            version_name = str(payload.get("versionName", "")).strip()[:40]
            event_day = str(payload.get("eventDay", "")).strip()
            historical = payload.get("historical", False)
            source = str(payload.get("source", "foreground")).strip()
            if (
                not IDENTITY_PATTERN.fullmatch(install_id)
                or version_code <= 0
                or not version_name
                or (event_day and not re.fullmatch(r"\d{4}-\d{2}-\d{2}", event_day))
                or not isinstance(historical, bool)
                or source not in {"foreground", "monitor"}
            ):
                raise ValueError("invalid heartbeat")
            self.service.store.record_heartbeat(
                install_id, version_code, version_name, event_day, historical
            )
            self._send_bytes(HTTPStatus.NO_CONTENT, b"", None)
        except (ValueError, json.JSONDecodeError):
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid request"})
        except sqlite3.Error:
            self._send_json(HTTPStatus.SERVICE_UNAVAILABLE, {"error": "temporarily unavailable"})

    def _public_history(self) -> None:
        """公共只读接口：仅按精确 roomCode 返回最近 30 天采样，不需要或暴露凭据。"""

        try:
            if not self.service.public_read_limiter.allow(self._client_ip()):
                self._send_json(
                    HTTPStatus.TOO_MANY_REQUESTS,
                    {"error": "rate limit exceeded"},
                )
                return
            if self.service.public_history_store is None:
                self._send_json(
                    HTTPStatus.SERVICE_UNAVAILABLE,
                    {"error": "temporarily unavailable"},
                )
                return
            query = urllib.parse.parse_qs(
                urllib.parse.urlsplit(self.path).query, keep_blank_values=True
            )
            room_code = query.get("roomCode", [""])[0].strip()
            if not re.fullmatch(r"\d{15}", room_code):
                raise ValueError("invalid room code")
            now = dt.datetime.now(SHANGHAI)
            earliest = now - dt.timedelta(days=30)
            since_millis = int(query.get("sinceMillis", ["0"])[0] or 0)
            until_millis = int(query.get("untilMillis", ["0"])[0] or 0)
            cursor = max(0, int(query.get("cursor", ["0"])[0] or 0))
            limit = min(500, max(1, int(query.get("limit", ["200"])[0] or 200)))
            if since_millis > 0:
                requested_since = dt.datetime.fromtimestamp(
                    since_millis / 1000, tz=dt.timezone.utc
                ).astimezone(SHANGHAI)
                since = max(earliest, requested_since)
            else:
                since = earliest
            if until_millis > 0:
                requested_until = dt.datetime.fromtimestamp(
                    until_millis / 1000, tz=dt.timezone.utc
                ).astimezone(SHANGHAI)
                until = min(now, requested_until)
            else:
                until = now
            if since > until:
                raise ValueError("invalid range")
            result = self.service.public_history_store.public_history(
                room_code,
                since.isoformat(timespec="seconds"),
                until.isoformat(timespec="seconds"),
                cursor,
                limit,
            )
            self._send_json(HTTPStatus.OK, result)
        except (ValueError, OverflowError, OSError):
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid request"})
        except sqlite3.Error:
            # 历史库不可用时只影响补充数据；App 会静默继续使用本地数据库。
            self._send_json(
                HTTPStatus.SERVICE_UNAVAILABLE,
                {"error": "temporarily unavailable"},
            )

    def _download(self, path: str, *, count: bool) -> None:
        requested = urllib.parse.unquote(path.removeprefix("/downloads/"))
        if not APK_NAME_PATTERN.fullmatch(requested):
            self._send_bytes(HTTPStatus.NOT_FOUND, b"", None)
            return
        try:
            filename, version_code, _file_path = self.service.available_download(requested)
            if count:
                install_id = self.headers.get("X-Elec-Install-ID", "").strip()
                if not IDENTITY_PATTERN.fullmatch(install_id):
                    install_id = None
                fingerprint = f"{self._client_ip()}\0{self.headers.get('User-Agent', '')[:300]}"
                self.service.store.record_download(
                    version_code, install_id, fingerprint
                )
            # 文件由 Nginx internal location 零拷贝发送，Python 不读取 3MB+ APK。
            self.send_response(HTTPStatus.OK)
            self.send_header(
                "X-Accel-Redirect",
                "/_protected_downloads/" + urllib.parse.quote(filename),
            )
            self.send_header(
                "Content-Type", "application/vnd.android.package-archive"
            )
            self.send_header("Cache-Control", "public, max-age=31536000, immutable")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            # 直连开发端口测试时没有 Nginx 消费 X-Accel-Redirect，主动关闭连接可避免
            # HTTP/1.1 客户端继续等待一个并不存在的 Python 响应体。
            self.close_connection = True
        except FileNotFoundError:
            # 已按保留策略清理的旧版属于“资源不存在”，不能误报成服务故障。
            self._send_bytes(
                HTTPStatus.NOT_FOUND, b"not found\n", "text/plain; charset=utf-8"
            )
        except (OSError, ValueError, KeyError, json.JSONDecodeError, sqlite3.Error):
            self._send_bytes(
                HTTPStatus.SERVICE_UNAVAILABLE,
                b"download temporarily unavailable\n",
                "text/plain; charset=utf-8",
            )

    def _login(self) -> None:
        client_key = self._client_ip()
        if self.service.login_limiter.is_blocked(client_key):
            self._send_html(
                HTTPStatus.TOO_MANY_REQUESTS,
                login_page("尝试次数过多，请 15 分钟后再试。"),
            )
            return
        try:
            form = urllib.parse.parse_qs(
                self._read_body().decode("utf-8"), keep_blank_values=True
            )
            password = form.get("password", [""])[0]
        except (ValueError, UnicodeError):
            password = ""
        if not verify_password(password, self.service.settings.password_hash):
            self.service.login_limiter.failure(client_key)
            # 固定延迟降低高频猜测效率，同时不会影响其他请求线程。
            time.sleep(0.35)
            self._send_html(
                HTTPStatus.UNAUTHORIZED, login_page("密码错误，请重新输入。")
            )
            return
        self.service.login_limiter.success(client_key)
        token = self.service.create_session()
        self.send_response(HTTPStatus.SEE_OTHER)
        self._security_headers(admin=True)
        self.send_header("Location", "/admin/")
        self.send_header(
            "Set-Cookie",
            f"elec_admin={token}; Path=/admin; Max-Age={SESSION_SECONDS}; "
            "Secure; HttpOnly; SameSite=Strict",
        )
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _logout(self) -> None:
        session = self._session_token()
        try:
            form = urllib.parse.parse_qs(
                self._read_body().decode("utf-8"), keep_blank_values=True
            )
            csrf = form.get("csrf", [""])[0]
        except (ValueError, UnicodeError):
            csrf = ""
        if not session or not hmac.compare_digest(
            csrf, self.service.csrf_token(session)
        ):
            self._send_bytes(HTTPStatus.FORBIDDEN, b"forbidden\n", "text/plain")
            return
        self.send_response(HTTPStatus.SEE_OTHER)
        self._security_headers(admin=True)
        self.send_header("Location", "/admin/login")
        self.send_header(
            "Set-Cookie",
            "elec_admin=; Path=/admin; Max-Age=0; Secure; HttpOnly; SameSite=Strict",
        )
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _change_password(self) -> None:
        session = self._session_token()
        if not session:
            self._redirect("/admin/login")
            return
        try:
            form = urllib.parse.parse_qs(
                self._read_body().decode("utf-8"), keep_blank_values=True
            )
            csrf = form.get("csrf", [""])[0]
            current_password = form.get("currentPassword", [""])[0]
            new_password = form.get("newPassword", [""])[0]
            confirmation = form.get("confirmation", [""])[0]
        except (ValueError, UnicodeError):
            self._send_html(
                HTTPStatus.BAD_REQUEST,
                password_page(self.service.csrf_token(session), "提交内容无效。"),
            )
            return
        if not hmac.compare_digest(csrf, self.service.csrf_token(session)):
            self._send_bytes(HTTPStatus.FORBIDDEN, b"forbidden\n", "text/plain")
            return
        error = ""
        if not verify_password(
            current_password, self.service.settings.password_hash
        ):
            error = "当前密码不正确。"
        elif len(new_password) < 12 or len(new_password) > 128:
            error = "新密码长度必须为 12–128 个字符。"
        elif new_password != confirmation:
            error = "两次输入的新密码不一致。"
        elif hmac.compare_digest(current_password, new_password):
            error = "新密码不能与当前密码相同。"
        if error:
            self._send_html(
                HTTPStatus.BAD_REQUEST,
                password_page(self.service.csrf_token(session), error),
            )
            return
        try:
            self.service.change_password(new_password)
        except OSError:
            self._send_html(
                HTTPStatus.INTERNAL_SERVER_ERROR,
                password_page(
                    self.service.csrf_token(session),
                    "密码保存失败，请检查服务器数据目录权限。",
                ),
            )
            return

        # change_password 已让当前 session 失效；清空浏览器 Cookie 并要求使用新密码登录。
        self.send_response(HTTPStatus.SEE_OTHER)
        self._security_headers(admin=True)
        self.send_header("Location", "/admin/login?passwordChanged=1")
        self.send_header(
            "Set-Cookie",
            "elec_admin=; Path=/admin; Max-Age=0; Secure; HttpOnly; SameSite=Strict",
        )
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _session_token(self) -> str | None:
        cookie_header = self.headers.get("Cookie", "")
        try:
            cookie = SimpleCookie()
            cookie.load(cookie_header)
            token = cookie["elec_admin"].value
        except (KeyError, ValueError):
            return None
        return token if self.service.verify_session(token) else None

    def _read_json(self) -> dict[str, Any]:
        if "application/json" not in self.headers.get("Content-Type", ""):
            raise ValueError("content type")
        value = json.loads(self._read_body())
        if not isinstance(value, dict):
            raise ValueError("object required")
        return value

    def _read_body(self) -> bytes:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError as exception:
            raise ValueError("invalid content length") from exception
        if length <= 0 or length > MAX_BODY_BYTES:
            raise ValueError("invalid body size")
        return self.rfile.read(length)

    def _client_ip(self) -> str:
        # 服务只监听 loopback，X-Real-IP 只能由本机 Nginx 写入。
        forwarded = self.headers.get("X-Real-IP", "").strip()
        return forwarded or self.client_address[0]

    def _redirect(self, location: str) -> None:
        self.send_response(HTTPStatus.SEE_OTHER)
        self._security_headers(admin=location.startswith("/admin"))
        self.send_header("Location", location)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _send_json(self, status: HTTPStatus, value: Any) -> None:
        body = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode(
            "utf-8"
        )
        accepts_gzip = "gzip" in self.headers.get("Accept-Encoding", "").lower()
        if accepts_gzip and len(body) >= 512:
            body = gzip.compress(body, compresslevel=5)
            self._send_bytes(
                status,
                body,
                "application/json; charset=utf-8",
                content_encoding="gzip",
            )
        else:
            self._send_bytes(status, body, "application/json; charset=utf-8")

    def _send_html(self, status: HTTPStatus, body: str) -> None:
        # 后台默认禁止脚本。云端采集页需要少量原生脚本完成局部分页与筛选，
        # 因此只把当前响应中实际存在的内联脚本摘要加入 CSP 白名单；不开放
        # unsafe-inline，也不允许第三方脚本来源。
        script_hashes = tuple(
            "'sha256-" + base64.b64encode(
                hashlib.sha256(script.encode("utf-8")).digest()
            ).decode("ascii") + "'"
            for script in re.findall(r"<script>(.*?)</script>", body, re.DOTALL)
        )
        self._send_bytes(
            status, body.encode("utf-8"), "text/html; charset=utf-8", admin=True,
            script_hashes=script_hashes,
        )

    def _send_bytes(
        self,
        status: HTTPStatus,
        body: bytes,
        content_type: str | None,
        *,
        admin: bool = False,
        content_encoding: str | None = None,
        script_hashes: tuple[str, ...] = (),
    ) -> None:
        self.send_response(status)
        self._security_headers(admin=admin, script_hashes=script_hashes)
        if content_type:
            self.send_header("Content-Type", content_type)
        if content_encoding:
            self.send_header("Content-Encoding", content_encoding)
            self.send_header("Vary", "Accept-Encoding")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD" and body:
            self.wfile.write(body)

    def _security_headers(
        self, *, admin: bool, script_hashes: tuple[str, ...] = ()
    ) -> None:
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Frame-Options", "DENY")
        if admin:
            self.send_header("Cache-Control", "no-store")
            script_policy = (
                " script-src " + " ".join(script_hashes) + ";"
                if script_hashes else ""
            )
            self.send_header(
                "Content-Security-Policy",
                "default-src 'none'; style-src 'unsafe-inline'; "
                f"form-action 'self'; connect-src 'self';{script_policy} base-uri 'none'; "
                "frame-ancestors 'none'",
            )

    def log_message(self, format_text: str, *args: Any) -> None:
        # 不记录 Cookie、请求体或原始安装 ID；路径中也不携带安装 ID。
        message = format_text % args
        print(
            f"{iso_utc_now()} {self.command} "
            f"{urllib.parse.urlsplit(self.path).path} {message}",
            flush=True,
        )


def login_page(error: str = "", notice: str = "") -> str:
    error_html = (
        f'<div class="error">{html.escape(error)}</div>' if error else ""
    )
    notice_html = (
        f'<div class="notice">{html.escape(notice)}</div>' if notice else ""
    )
    return page_shell(
        "开发者登录",
        f"""
        <main class="login-wrap">
          <section class="login-card">
            <div class="eyebrow">江理电费管家</div>
            <h1>开发者后台</h1>
            <p class="muted">请输入服务器管理员密码。连续失败会触发临时限制。</p>
            {notice_html}
            {error_html}
            <form method="post" action="/admin/login">
              <label for="password">后台密码</label>
              <input id="password" name="password" type="password"
                     autocomplete="current-password" required autofocus>
              <button type="submit">进入后台</button>
            </form>
          </section>
        </main>
        """,
    )


def password_page(csrf: str, error: str = "") -> str:
    """后台密码修改页；改密成功后所有已签发会话都会立即失效。"""

    error_html = (
        f'<div class="error">{html.escape(error)}</div>' if error else ""
    )
    return page_shell(
        "修改后台密码",
        f"""
        <main class="login-wrap">
          <section class="login-card">
            <div class="eyebrow">SECURITY</div>
            <h1>修改后台密码</h1>
            <p class="muted">新密码需为 12–128 个字符。修改成功后，所有已登录设备都需使用新密码重新登录。</p>
            {error_html}
            <form method="post" action="/admin/password">
              <input type="hidden" name="csrf" value="{html.escape(csrf)}">
              <label for="currentPassword">当前密码</label>
              <input id="currentPassword" name="currentPassword" type="password"
                     autocomplete="current-password" required autofocus>
              <label for="newPassword">新密码</label>
              <input id="newPassword" name="newPassword" type="password"
                     autocomplete="new-password" minlength="12" maxlength="128" required>
              <label for="confirmation">再次输入新密码</label>
              <input id="confirmation" name="confirmation" type="password"
                     autocomplete="new-password" minlength="12" maxlength="128" required>
              <button type="submit">保存新密码</button>
            </form>
            <a class="back-link" href="/admin/">返回开发者后台</a>
          </section>
        </main>
        """,
    )


def dashboard_page(
    manifest: dict[str, Any],
    stats: dict[str, Any],
    releases: list[dict[str, Any]],
    csrf: str,
    started_at: dt.datetime,
) -> str:
    daily = stats["daily"]
    max_daily = max((count for _day, count in daily), default=1) or 1
    bars = "".join(
        f"""
        <div class="bar-row">
          <span>{html.escape(day[5:])}</span>
          <div class="bar-track"><div class="bar" style="width:{count / max_daily * 100:.1f}%"></div></div>
          <strong>{count}</strong>
        </div>
        """
        for day, count in daily
    )
    version_rows = "".join(
        f"<tr><td>{code}</td><td>{html.escape(name)}</td><td>{count}</td></tr>"
        for code, name, count in stats["versions"]
    ) or '<tr><td colspan="3" class="muted">尚无匿名使用数据</td></tr>'
    release_notes = html.escape(str(manifest.get("releaseNotes", ""))).replace(
        "\n", "<br>"
    )
    release_rows = "".join(
        f"""
        <tr>
          <td>{html.escape(str(item["version"]))}</td>
          <td>{_format_bytes(int(item["bytes"]))}</td>
          <td><a class="download-link" href="{html.escape(str(item["url"]))}">下载 APK</a></td>
        </tr>
        """
        for item in releases
    ) or '<tr><td colspan="3" class="muted">服务器暂无可下载版本</td></tr>'
    mandatory_text = (
        "必须更新" if bool(manifest.get("forceUpdate")) else "允许跳过"
    )
    uptime = utc_now() - started_at
    uptime_text = str(uptime).split(".", 1)[0]
    last_seen = stats["last_seen"] or "尚无数据"
    db_size = _format_bytes(int(stats["database_bytes"]))
    return page_shell(
        "开发者后台",
        f"""
        <header class="topbar">
          <div>
            <div class="eyebrow">ELEC CONSOLE</div>
            <h1>江理电费管家</h1>
            <p class="muted">匿名使用概览 · 中国标准时间</p>
          </div>
          <div class="header-actions">
            <a class="secondary" href="/admin/collector">云端采集</a>
            <a class="secondary" href="/admin/password">修改密码</a>
            <a class="secondary" href="/admin/">刷新数据</a>
            <form method="post" action="/admin/logout">
              <input type="hidden" name="csrf" value="{html.escape(csrf)}">
              <button class="secondary" type="submit">退出</button>
            </form>
          </div>
        </header>

        <main>
          <section class="metric-grid">
            {_metric("累计实际使用设备", stats["total_users"], "仅 App 启动心跳；下载不计入")}
            {_metric("今日日活", stats["today_active"], "今日实际打开 App 的设备数")}
            {_metric("近 7 日活跃", stats["active_7"], "近 7 天实际使用设备去重")}
            {_metric("最新版已使用", stats["latest_installed"], f'已启动版本 {html.escape(str(manifest["versionName"]))}')}
            {_metric("最新版独立下载", stats["latest_download_unique"], "匿名 ID；旧版按网络特征估算")}
            {_metric("最新版下载请求", stats["latest_download_requests"], "含断点续传或重复请求")}
          </section>

          <section class="two-column">
            <article class="panel">
              <div class="panel-title">
                <div><span class="eyebrow">ACTIVITY</span><h2>近 14 日日活</h2></div>
              </div>
              <div class="bars">{bars}</div>
            </article>

            <article class="panel">
              <div class="panel-title">
                <div><span class="eyebrow">RELEASE</span><h2>当前发布参数</h2></div>
                <span class="pill">{mandatory_text}</span>
              </div>
              <dl>
                <dt>最新版本</dt><dd>{html.escape(str(manifest["versionName"]))}（{int(manifest["versionCode"])}）</dd>
                <dt>最低支持版本</dt><dd>{int(manifest.get("minSupportedVersionCode", 0))}</dd>
                <dt>更新服务</dt><dd>{"已启用" if manifest.get("enabled", True) else "已停用"}</dd>
                <dt>APK 地址</dt><dd class="break">{html.escape(str(manifest["apkUrl"]))}</dd>
                <dt>SHA-256</dt><dd class="break mono">{html.escape(str(manifest["sha256"]))}</dd>
              </dl>
              <div class="notes"><strong>更新说明</strong><p>{release_notes}</p></div>
            </article>
          </section>

          <section class="two-column">
            <article class="panel">
              <div class="panel-title">
                <div><span class="eyebrow">DOWNLOADS</span><h2>服务器保留版本</h2></div>
                <span class="pill">最近 3 版</span>
              </div>
              <p class="muted panel-help">仅保留最近三个发布版本；下载不会计入实际用户或日活。</p>
              <div class="table-wrap">
                <table>
                  <thead><tr><th>版本</th><th>文件大小</th><th>安装包</th></tr></thead>
                  <tbody>{release_rows}</tbody>
                </table>
              </div>
            </article>
            <article class="panel">
              <div class="panel-title"><div><span class="eyebrow">VERSIONS</span><h2>版本分布</h2></div></div>
              <div class="table-wrap">
                <table><thead><tr><th>versionCode</th><th>版本名</th><th>用户数</th></tr></thead>
                <tbody>{version_rows}</tbody></table>
              </div>
            </article>
            <article class="panel">
              <div class="panel-title"><div><span class="eyebrow">SYSTEM</span><h2>服务基本信息</h2></div></div>
              <dl>
                <dt>服务运行时间</dt><dd>{html.escape(uptime_text)}</dd>
                <dt>最后一次心跳</dt><dd>{html.escape(str(last_seen))}</dd>
                <dt>统计数据库</dt><dd>{db_size}</dd>
                <dt>隐私策略</dt><dd>匿名统计不保存 Android ID 或硬件标识；公共采样与用户配置隔离</dd>
                <dt>故障隔离</dt><dd>遥测失败不会阻塞 App 功能</dd>
              </dl>
            </article>
          </section>
        </main>
        <footer>江理电费管家开发者后台 · 版本维护、匿名统计与公共房间采样</footer>
        """,
    )


def _metric(label: str, value: Any, detail: str) -> str:
    return (
        '<article class="metric">'
        f'<span>{html.escape(label)}</span><strong>{html.escape(str(value))}</strong>'
        f'<small>{html.escape(detail)}</small></article>'
    )


def _collector_parameters(path_with_query: str) -> dict[str, Any]:
    """统一解析后台筛选参数；所有可进入 SQL 的枚举仍由固定白名单控制。"""

    query = urllib.parse.parse_qs(urllib.parse.urlsplit(path_with_query).query)
    day = query.get("date", [""])[0].strip()
    if day:
        try:
            dt.date.fromisoformat(day)
        except ValueError:
            day = ""
    building_code = query.get("buildingCode", [""])[0].strip()
    room_code = query.get("roomCode", [""])[0].strip()
    if building_code and not re.fullmatch(r"\d{9}", building_code):
        building_code = ""
    if room_code and not re.fullmatch(r"\d{15}", room_code):
        room_code = ""
    event_type = query.get("eventType", [""])[0].strip()
    if event_type not in EVENT_TYPES:
        event_type = ""
    event_sort = query.get("sort", ["time_desc"])[0].strip()
    if event_sort not in EVENT_SORT_SQL:
        event_sort = "time_desc"
    # 页大小只接受界面公开的三个档位。不能先把任意大数截断为 500，
    # 否则非法输入会被误认为合法的 500 条选项。
    page_size = _bounded_int(query.get("pageSize", ["100"])[0], 100, 1, 1_000_000)
    if page_size not in EVENT_PAGE_SIZES:
        page_size = 100
    interval_end = _bounded_int(query.get("intervalEnd", ["0"])[0], 0, 0, 20)
    if interval_end not in range(9, 21):
        interval_end = 0
    page = _bounded_int(query.get("page", ["1"])[0], 1, 1, 1_000_000)
    snapshot_id = _bounded_int(
        query.get("snapshot", ["0"])[0], 0, 0, 2_147_483_647
    )
    return {
        "day": day,
        "building_code": building_code,
        "room_code": room_code,
        "event_type": event_type,
        "event_sort": event_sort,
        "page_size": page_size,
        "interval_end_hour": interval_end,
        "events": {
            "day": day,
            "building_code": building_code,
            "room_code": room_code,
            "event_type": event_type,
            "interval_end_hour": interval_end,
            "event_sort": event_sort,
            "page": page,
            "page_size": page_size,
            "snapshot_id": snapshot_id,
        },
    }


def _bounded_int(value: Any, fallback: int, minimum: int, maximum: int) -> int:
    try:
        return min(maximum, max(minimum, int(value)))
    except (TypeError, ValueError):
        return fallback


def collector_page(
    task: dict[str, Any],
    event_page: dict[str, Any],
    distribution: dict[str, Any],
    parameters: dict[str, Any],
) -> str:
    """单页采集控制台：实时任务、可分页事件和按日时段分析相互独立。"""

    building_options = '<option value="">全部楼栋</option>' + "".join(
        f'<option value="{html.escape(str(row["building_code"]))}"'
        f'{" selected" if parameters["building_code"] == row["building_code"] else ""}>'
        f'{html.escape(str(row["building_name"]))}</option>'
        for row in task["buildings"]
    )
    event_type_options = "".join(
        f'<option value="{html.escape(value)}"'
        f'{" selected" if event_page["event_type"] == value else ""}>'
        f'{html.escape(label)}</option>'
        for value, label in (
            ("", "全部类型"), ("充值", "充值"),
            ("用电消耗", "用电消耗"), ("待确认", "待确认"),
        )
    )
    sort_options = "".join(
        f'<option value="{value}"'
        f'{" selected" if event_page["sort"] == value else ""}>'
        f'{html.escape(label)}</option>'
        for value, label in (
            ("time_desc", "时间：最新优先"),
            ("time_asc", "时间：最早优先"),
            ("building_asc", "楼栋：正序"),
            ("building_desc", "楼栋：倒序"),
            ("recharge_amount_desc", "充值金额：由高到低（仅充值）"),
            ("consumption_amount_desc", "消耗金额：由高到低（仅消耗）"),
            ("balance_desc", "电量变化：由大到小"),
        )
    )
    page_size_options = "".join(
        f'<option value="{size}"'
        f'{" selected" if event_page["page_size"] == size else ""}>{size} 条</option>'
        for size in (100, 200, 500)
    )
    return page_shell(
        "云端采集",
        f"""
        <header class="topbar" id="page-top">
          <div><div class="eyebrow">PUBLIC HISTORY</div><h1>云端采集</h1>
          <p class="muted">公共房间采样 · Asia/Shanghai · 最近 30 天</p></div>
          <div class="header-actions"><a class="secondary" href="/admin/">返回概览</a></div>
        </header>
        <main class="collector-main">
          {collector_task_fragment(task)}
          <section class="panel events-panel" id="events-section">
            <div class="section-heading"><div><div class="eyebrow">CHANGE EVENTS</div>
              <h2>最近变化事件</h2><p class="muted">完整查询最近 30 天数据，页面分批渲染。</p></div></div>
            <form id="event-filter-form" class="filters event-filters">
              <label>日期<input id="event-date" name="date" type="date"
                value="{html.escape(distribution["day"])}"></label>
              <label>楼栋<select name="buildingCode">{building_options}</select></label>
              <label>房间码<input name="roomCode" inputmode="numeric" maxlength="15"
                value="{html.escape(parameters["room_code"])}" placeholder="15 位房间码"></label>
              <label>事件类型<select name="eventType">{event_type_options}</select></label>
              <label>排序<select name="sort">{sort_options}</select></label>
              <label>每页<select name="pageSize">{page_size_options}</select></label>
              <input name="intervalEnd" type="hidden" value="{parameters["interval_end_hour"]}">
              <input name="snapshot" type="hidden" value="{event_page["snapshot_id"]}">
              <input name="page" type="hidden" value="{event_page["page"]}">
              <button type="submit">应用筛选</button>
              <button class="secondary" id="clear-event-filters" type="button">清除</button>
            </form>
            <div id="event-loading" class="local-loading" hidden>正在加载变化事件…</div>
            <div id="events-results" aria-live="polite">
              {collector_events_fragment(event_page)}
            </div>
          </section>
          <section class="panel distribution-panel" id="distribution-section">
            <div id="distribution-results">
              {collector_distribution_fragment(
                  distribution, parameters["interval_end_hour"]
              )}
            </div>
          </section>
          <section class="data-note">
            <strong>数据说明</strong>
            <span>服务器只保留最近 30 天公共房间采样；数据库当前占用
              {_format_bytes(int(task["database_bytes"]))}。用户备注、提醒和充值记录仍只保存在手机。</span>
          </section>
          <button class="back-to-top secondary" id="back-to-top" type="button">回到顶部</button>
        </main>
        <footer>江理电费管家 · 云端公共采样控制台</footer>
        {_collector_script()}
        """,
    )


def collector_task_fragment(task: dict[str, Any]) -> str:
    latest = task["latest_job"]
    if not latest:
        return '<section class="panel task-card"><p class="muted">尚无采集任务</p></section>'
    total = int(latest["total_rooms"] or 0)
    processed = int(latest["processed_rooms"] or 0)
    success = int(latest["success_count"] or 0)
    failure = int(latest["failure_count"] or 0)
    progress_percent = min(100.0, processed / total * 100 if total else 0.0)
    success_rate = success / processed * 100 if processed else 0.0
    duration = (
        f'{float(latest["duration_seconds"]):.1f} 秒'
        if latest["duration_seconds"] is not None else "执行中"
    )
    status = {"running": "采集中", "completed": "已完成", "failed": "异常结束"}.get(
        str(latest["status"]), str(latest["status"])
    )
    status_class = "status-ok" if failure == 0 else "status-warning"
    statistics = "".join(
        f'<div class="stat-item"><span>{html.escape(label)}</span>'
        f'<strong>{html.escape(str(value))}</strong></div>'
        for label, value in (
            ("当前轮数", f'{task["current_round"] or "—"} / {task["round_total"]}'),
            ("样本总数", task["total_samples"]),
            ("成功样本", success), ("失败样本", failure),
            ("成功率", f"{success_rate:.2f}%"),
            ("数据库占用", _format_bytes(int(task["database_bytes"]))),
            ("本轮耗时", duration),
        )
    )
    show_no_meter = int(task["no_meter_count"]) > 0
    building_rows = "".join(
        f"""
        <tr><td><strong>{html.escape(str(row["building_name"]))}</strong></td>
        <td class="mono">{html.escape(str(row["building_code"]))}</td>
        <td>{int(row["room_count"] or 0)}</td>
        <td>{int(row["success_count"] or 0)}</td>
        <td>{int(row["failure_count"] or 0)}</td>
        <td>{int(row["processed_count"] or 0)} / {int(row["room_count"] or 0)}</td>
        {f'<td>{int(row["no_meter_count"] or 0)}</td>' if show_no_meter else ''}</tr>
        """ for row in task["buildings"]
    ) or '<tr><td colspan="6" class="muted">尚未同步房间目录</td></tr>'
    failure_html = ""
    if task["failures"]:
        failure_rows = "".join(
            f'<tr><td>{html.escape(str(row["error_type"]))}</td>'
            f'<td>{html.escape(str(row["building_name"]))}</td>'
            f'<td>{int(row["count"])}</td></tr>' for row in task["failures"]
        )
        failure_html = f"""
        <details class="expandable failure-expand">
          <summary class="failure-summary">
            <div><strong>本轮出现 {failure} 次失败</strong><span>失败详情仅在有异常时显示。</span></div>
            <span class="summary-action"><span class="when-closed">查看失败详情</span><span class="when-open">收起失败详情</span></span>
          </summary>
          <div class="expandable-content">
          <table><thead><tr><th>失败类型</th><th>楼栋</th><th>数量</th></tr></thead>
          <tbody>{failure_rows}</tbody></table>
          </div>
        </details>"""
    finished_at = latest["finished_at"] or latest["started_at"]
    return f"""
    <section class="panel task-card">
      <div class="task-head">
        <div><div class="eyebrow">CURRENT COLLECTION</div><h2>当前采集任务</h2>
          <p class="muted">任务时间 {html.escape(_compact_time(str(latest["slot_time"])))}</p></div>
        <span class="status-label {status_class}">{html.escape(status)}</span>
      </div>
      <div class="task-primary">
        <div class="round-display"><span>当前轮次</span>
          <strong>第 {task["current_round"] or "—"} / {task["round_total"]} 轮</strong></div>
        <div class="task-meta"><span>开始 / 最近完成</span>
          <strong>{html.escape(_compact_time(str(finished_at)))}</strong>
          <small>当前处理：{html.escape(str(task["current_building"]))}</small></div>
      </div>
      <div class="progress-summary"><div><span>本轮进度</span><strong>{processed} / {total}</strong></div>
        <div class="progress-track"><span style="width:{progress_percent:.2f}%"></span></div>
      </div>
      <div class="stats-strip">{statistics}</div>
      <details class="expandable coverage-expand">
        <summary class="coverage-summary">
          <div><strong>已覆盖 {task["covered_buildings"]} 栋楼</strong>
            <span>{task["valid_rooms"]} 个有效房间 · 已处理 {processed} 个</span></div>
          <span class="summary-action"><span class="when-closed">展开楼栋详情</span><span class="when-open">收起楼栋详情</span></span>
        </summary>
        <div class="expandable-content table-wrap">
          <table class="coverage-table"><thead><tr><th>楼栋</th><th>代码</th><th>有效房间</th>
            <th>本轮成功</th><th>本轮失败</th><th>当前进度</th>
            {'<th>无电表</th>' if show_no_meter else ''}</tr></thead><tbody>{building_rows}</tbody></table>
        </div>
      </details>
      {failure_html}
    </section>"""


def collector_events_fragment(event_page: dict[str, Any]) -> str:
    rows = "".join(_event_row_html(row) for row in event_page["events"])
    if not rows:
        rows = '<tr><td colspan="8"><div class="empty-state">当前条件下暂无变化事件</div></td></tr>'
    previous_disabled = " disabled" if event_page["page"] <= 1 else ""
    next_disabled = " disabled" if event_page["page"] >= event_page["total_pages"] else ""
    return f"""
      <div class="events-meta"><strong>共 {event_page["total"]} 条</strong>
        <span>第 {event_page["page"]} / {event_page["total_pages"]} 页</span></div>
      <div class="table-wrap"><table class="event-table"><thead><tr>
        <th>房间</th><th>采集日期</th><th>变化区间</th><th>变化前</th><th>变化后</th>
        <th>电量变化</th><th>金额变化</th><th>类型</th></tr></thead><tbody>{rows}</tbody></table></div>
      <nav class="pagination" aria-label="事件分页">
        <button class="secondary" type="button" data-event-page="{event_page["page"] - 1}"{previous_disabled}>上一页</button>
        <span>第 {event_page["page"]} 页 · 每页 {event_page["page_size"]} 条</span>
        <button class="secondary" type="button" data-event-page="{event_page["page"] + 1}"{next_disabled}>下一页</button>
      </nav>"""


def _event_row_html(row: dict[str, Any]) -> str:
    room_name = " · ".join(part for part in (
        str(row.get("building_name") or ""), str(row.get("floor_name") or ""),
        str(row.get("room_name") or ""),
    ) if part) or str(row["room_code"])
    date_text, interval_text = _event_date_and_interval(
        str(row["previous_query_time"]), str(row["current_query_time"])
    )
    return f"""
      <tr><td><strong>{html.escape(room_name)}</strong><br>
        <span class="muted mono">{html.escape(str(row["room_code"]))}</span></td>
      <td>{html.escape(date_text)}</td><td>{html.escape(interval_text)}</td>
      <td>{float(row["before_balance"]):.2f} 度</td>
      <td>{float(row["after_balance"]):.2f} 度</td>
      <td><strong>{float(row["delta_balance"]):+.2f} 度</strong></td>
      <td>{_format_delta_amount(row.get("delta_amount"))}</td>
      <td><span class="event-type">{html.escape(str(row["inferred_type"]))}</span></td></tr>"""


def collector_distribution_fragment(
    distribution: dict[str, Any], selected_end_hour: int = 0
) -> str:
    max_count = max(
        (int(row["total_count"]) for row in distribution["intervals"]), default=0
    )
    interval_rows = "".join(
        _distribution_interval_html(row, max_count, selected_end_hour)
        for row in distribution["intervals"]
    )
    return f"""
      <div class="section-heading distribution-heading"><div>
        <div class="eyebrow">DAILY DISTRIBUTION</div><h2>变化时段分布</h2>
        <p class="muted">13 轮整点采集形成 12 个相邻分析区间。</p></div>
        <div class="date-nav"><button class="secondary" type="button" data-day-step="-1">上一天</button>
          <input id="distribution-date" type="date" value="{html.escape(distribution["day"])}">
          <button class="secondary" type="button" data-day-step="1">下一天</button>
          <button class="secondary" id="distribution-today" type="button">今天</button></div>
      </div>
      <div class="distribution-summary">
        <span><strong>{distribution["total"]}</strong> 总变化</span>
        <span><strong>{distribution["recharge"]}</strong> 充值</span>
        <span><strong>{distribution["consumption"]}</strong> 用电消耗</span>
        <span><strong>{distribution["abnormal"]}</strong> 异常</span>
      </div>
      <div class="interval-grid">{interval_rows}</div>"""


def _distribution_interval_html(
    row: dict[str, Any], max_count: int, selected_end_hour: int
) -> str:
    count = int(row["total_count"])
    width = count / max_count * 100 if max_count else 0
    selected = " selected" if selected_end_hour == int(row["end_hour"]) else ""
    abnormal = " has-abnormal" if int(row["abnormal_count"]) else ""
    return f"""
      <button class="interval-card{selected}{abnormal}" type="button"
        data-interval-end="{int(row["end_hour"])}">
        <div class="interval-title"><strong>{int(row["start_hour"]):02d}:00–{int(row["end_hour"]):02d}:00</strong>
          <span>{count} 条</span></div>
        <div class="mini-track"><span style="width:{width:.2f}%"></span></div>
        <div class="interval-stats"><span>充值 {int(row["recharge_count"])}</span>
          <span>消耗 {int(row["consumption_count"])}</span>
          <span>电量 {float(row["total_delta_kwh"]):+.2f} 度</span>
          <span>金额 {float(row["total_delta_amount"]):+.2f} 元</span></div>
      </button>"""


def _compact_time(value: str) -> str:
    """后台任务时刻使用上海本地紧凑格式，解析失败时保留原值便于排查。"""

    try:
        return parse_iso(value).strftime("%Y-%m-%d %H:%M")
    except (TypeError, ValueError):
        return value


def _event_interval(previous: str, current: str) -> str:
    """把冗长 ISO 时间改为用户可直接理解的日期与起止时间。"""

    try:
        start = parse_iso(previous)
        end = parse_iso(current)
    except (TypeError, ValueError):
        return f"{previous}–{current}"
    if start.date() == end.date():
        return f'{start:%Y-%m-%d} · {start:%H:%M}–{end:%H:%M}'
    return f'{start:%m-%d %H:%M}–{end:%m-%d %H:%M}'


def _event_date_and_interval(previous: str, current: str) -> tuple[str, str]:
    """事件表将日期和区间拆列，避免窄屏中一段长时间文本挤压其他数据。"""

    try:
        start = parse_iso(previous)
        end = parse_iso(current)
    except (TypeError, ValueError):
        return "—", f"{previous}–{current}"
    date_text = end.strftime("%Y-%m-%d")
    if start.date() == end.date():
        return date_text, f"{start:%H:%M}–{end:%H:%M}"
    return date_text, f"{start:%m-%d %H:%M}–{end:%m-%d %H:%M}"


def _collector_script() -> str:
    """局部刷新事件和分布区域；实时任务卡片不会因筛选或分页被重新加载。"""

    return r"""
    <script>
    (() => {
      const form = document.getElementById('event-filter-form');
      const results = document.getElementById('events-results');
      const loading = document.getElementById('event-loading');
      const distribution = document.getElementById('distribution-results');
      if (!form || !results || !distribution) return;

      const field = (name) => form.elements.namedItem(name);
      const params = () => {
        const query = new URLSearchParams();
        ['date','buildingCode','roomCode','eventType','sort','pageSize',
         'intervalEnd','snapshot','page'].forEach((name) => {
          query.set(name, field(name).value || '');
        });
        return query;
      };
      const localToday = () => {
        const now = new Date();
        const year = now.getFullYear();
        const month = String(now.getMonth() + 1).padStart(2, '0');
        const day = String(now.getDate()).padStart(2, '0');
        return `${year}-${month}-${day}`;
      };
      const shiftDay = (value, offset) => {
        const date = new Date(`${value}T12:00:00`);
        date.setDate(date.getDate() + offset);
        const year = date.getFullYear();
        const month = String(date.getMonth() + 1).padStart(2, '0');
        const day = String(date.getDate()).padStart(2, '0');
        return `${year}-${month}-${day}`;
      };
      const syncAmountType = () => {
        if (field('sort').value === 'recharge_amount_desc') field('eventType').value = '充值';
        if (field('sort').value === 'consumption_amount_desc') field('eventType').value = '用电消耗';
      };
      const updateAddress = (overrides = {}) => {
        const query = params();
        Object.entries(overrides).forEach(([name, value]) => query.set(name, value || ''));
        history.replaceState(null, '', `/admin/collector?${query.toString()}`);
      };

      async function loadEvents(page = 1, resetSnapshot = false) {
        syncAmountType();
        field('page').value = String(page);
        if (resetSnapshot) field('snapshot').value = '0';
        loading.hidden = false;
        results.classList.add('is-loading');
        try {
          const response = await fetch(`/admin/collector/events?${params().toString()}`, {
            headers: {'Accept': 'application/json'}
          });
          if (!response.ok) throw new Error('request failed');
          const payload = await response.json();
          results.innerHTML = payload.html;
          field('page').value = String(payload.page);
          field('pageSize').value = String(payload.pageSize);
          field('snapshot').value = String(payload.snapshotId);
          field('eventType').value = payload.eventType || '';
          updateAddress({eventType: payload.eventType});
        } catch (_) {
          results.innerHTML = '<div class="empty-state error-state">变化事件加载失败，请稍后重试。</div>';
        } finally {
          loading.hidden = true;
          results.classList.remove('is-loading');
        }
      }

      async function loadDistribution(day) {
        const query = new URLSearchParams();
        query.set('date', day);
        query.set('buildingCode', field('buildingCode').value);
        query.set('roomCode', field('roomCode').value);
        query.set('intervalEnd', field('intervalEnd').value);
        distribution.classList.add('is-loading');
        try {
          const response = await fetch(`/admin/collector/distribution?${query.toString()}`, {
            headers: {'Accept': 'application/json'}
          });
          if (!response.ok) throw new Error('request failed');
          const payload = await response.json();
          distribution.innerHTML = payload.html;
          field('date').value = payload.date;
          bindDistributionControls();
        } catch (_) {
          distribution.innerHTML = '<div class="empty-state error-state">时段分布加载失败，请稍后重试。</div>';
        } finally {
          distribution.classList.remove('is-loading');
        }
      }

      async function changeAnalysisDay(day) {
        field('date').value = day;
        field('intervalEnd').value = '0';
        await Promise.all([loadEvents(1, true), loadDistribution(day)]);
      }

      // 分布区域会被局部替换，替换后需要重新绑定按钮。使用直接监听可避免
      // 某些移动浏览器对 document 级事件代理处理不一致的问题。
      function bindDistributionControls() {
        distribution.querySelectorAll('[data-interval-end]').forEach((interval) => {
          interval.addEventListener('click', () => {
            const selected = String(interval.dataset.intervalEnd);
            field('intervalEnd').value = field('intervalEnd').value === selected ? '0' : selected;
            distribution.querySelectorAll('[data-interval-end]').forEach((item) => {
              item.classList.toggle('selected', field('intervalEnd').value === String(item.dataset.intervalEnd));
            });
            loadEvents(1, true);
            document.getElementById('events-section').scrollIntoView({behavior:'smooth', block:'start'});
          });
        });
        distribution.querySelectorAll('[data-day-step]').forEach((button) => {
          button.addEventListener('click', () => {
            const input = document.getElementById('distribution-date');
            changeAnalysisDay(shiftDay(input.value, Number(button.dataset.dayStep)));
          });
        });
        const dateInput = document.getElementById('distribution-date');
        if (dateInput) dateInput.addEventListener('change', () => changeAnalysisDay(dateInput.value));
        const todayButton = document.getElementById('distribution-today');
        if (todayButton) todayButton.addEventListener('click', () => changeAnalysisDay(localToday()));
      }

      form.addEventListener('submit', (event) => {
        event.preventDefault();
        field('intervalEnd').value = '0';
        Promise.all([loadEvents(1, true), loadDistribution(field('date').value)]);
      });
      field('sort').addEventListener('change', syncAmountType);
      field('eventType').addEventListener('change', () => {
        if (field('sort').value === 'recharge_amount_desc' && field('eventType').value !== '充值') {
          field('sort').value = 'time_desc';
        }
        if (field('sort').value === 'consumption_amount_desc' && field('eventType').value !== '用电消耗') {
          field('sort').value = 'time_desc';
        }
      });
      document.getElementById('clear-event-filters').addEventListener('click', () => {
        field('date').value = localToday();
        field('buildingCode').value = '';
        field('roomCode').value = '';
        field('eventType').value = '';
        field('sort').value = 'time_desc';
        field('pageSize').value = '100';
        field('intervalEnd').value = '0';
        Promise.all([loadEvents(1, true), loadDistribution(field('date').value)]);
      });
      results.addEventListener('click', (event) => {
        const button = event.target.closest('[data-event-page]');
        if (!button || button.disabled) return;
        loadEvents(Number(button.dataset.eventPage), false);
      });
      bindDistributionControls();
      const backToTop = document.getElementById('back-to-top');
      backToTop.addEventListener('click', () => {
        document.getElementById('page-top').scrollIntoView({behavior:'smooth'});
      });
      window.addEventListener('scroll', () => {
        backToTop.classList.toggle('visible', window.scrollY > 520);
      }, {passive:true});
    })();
    </script>
    """


def _format_delta_amount(value: Any) -> str:
    if value is None:
        return '<span class="muted">—</span>'
    return f"{float(value):+.2f} 元"


def _format_bytes(value: int) -> str:
    if value < 1024:
        return f"{value} B"
    if value < 1024 * 1024:
        return f"{value / 1024:.1f} KiB"
    return f"{value / 1024 / 1024:.1f} MiB"


def error_page(title: str, message: str) -> str:
    return page_shell(
        title,
        f'<main class="login-wrap"><section class="login-card">'
        f"<h1>{html.escape(title)}</h1><p>{html.escape(message)}</p>"
        '<a class="secondary" href="/admin/">返回</a></section></main>',
    )


def page_shell(title: str, content: str) -> str:
    return f"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{html.escape(title)} · 江理电费管家</title>
  <style>
    :root {{
      color-scheme: light dark;
      --bg:#f5f7fb; --surface:#fff; --text:#17223b; --muted:#667085;
      --border:#e4e9f2; --primary:#2457d6; --soft:#eef3ff; --danger:#c62828;
    }}
    * {{ box-sizing:border-box; }}
    body {{ margin:0; background:var(--bg); color:var(--text);
      font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC",sans-serif; }}
    .topbar, main, footer {{ width:min(1180px,calc(100% - 32px)); margin:auto; }}
    .topbar {{ padding:44px 0 24px; display:flex; justify-content:space-between;
      align-items:flex-end; gap:20px; }}
    h1 {{ margin:4px 0 6px; font-size:30px; }} h2 {{ margin:4px 0; font-size:18px; }}
    .eyebrow {{ color:var(--primary); font-size:12px; font-weight:800; letter-spacing:.12em; }}
    .muted {{ color:var(--muted); margin:0; }}
    .header-actions {{ display:flex; align-items:center; gap:8px; }}
    .header-actions form {{ margin:0; }}
    button,.secondary {{ min-height:44px; border-radius:10px; border:1px solid var(--border);
      padding:10px 16px; font:inherit; font-weight:700; cursor:pointer; text-decoration:none; }}
    button {{ background:var(--primary); color:#fff; border-color:var(--primary); }}
    .secondary {{ background:var(--surface); color:var(--text); display:inline-flex; align-items:center; }}
    .metric-grid {{ display:grid; grid-template-columns:repeat(6,1fr); gap:12px; }}
    .metric,.panel,.login-card {{ background:var(--surface); border:1px solid var(--border);
      border-radius:16px; }}
    .metric {{ padding:18px; min-width:0; }}
    .metric span,.metric small {{ display:block; color:var(--muted); }}
    .metric strong {{ display:block; margin:10px 0 8px; font-size:30px; }}
    .metric small {{ font-size:12px; line-height:1.5; }}
    .two-column {{ display:grid; grid-template-columns:1fr 1fr; gap:16px; margin-top:16px; }}
    .panel {{ padding:22px; min-width:0; }}
    .panel-title {{ display:flex; justify-content:space-between; gap:12px; align-items:flex-start;
      margin-bottom:18px; }}
    .pill {{ background:var(--soft); color:var(--primary); padding:6px 10px;
      border-radius:999px; font-size:12px; font-weight:800; }}
    .bar-row {{ display:grid; grid-template-columns:48px 1fr 32px; gap:10px;
      align-items:center; margin:9px 0; color:var(--muted); font-size:12px; }}
    .bar-track {{ height:9px; background:var(--soft); border-radius:99px; overflow:hidden; }}
    .bar {{ height:100%; min-width:2px; background:var(--primary); border-radius:99px; }}
    .bar-row strong {{ text-align:right; color:var(--text); }}
    dl {{ display:grid; grid-template-columns:120px 1fr; gap:10px 16px; margin:0; }}
    dt {{ color:var(--muted); }} dd {{ margin:0; }}
    .break {{ overflow-wrap:anywhere; }} .mono {{ font-family:ui-monospace,SFMono-Regular,monospace; font-size:12px; }}
    .notes {{ border-top:1px solid var(--border); margin-top:18px; padding-top:16px; }}
    .notes p {{ color:var(--muted); line-height:1.7; }}
    .panel-help {{ margin:-8px 0 12px; line-height:1.6; }}
    table {{ width:100%; border-collapse:collapse; }}
    .table-wrap {{ width:100%; overflow-x:auto; -webkit-overflow-scrolling:touch; }}
    .event-table {{ min-width:760px; }}
    th,td {{ text-align:left; padding:11px 8px; border-bottom:1px solid var(--border); }}
    th {{ color:var(--muted); font-size:12px; }}
    .download-link,.back-link {{ color:var(--primary); font-weight:700; text-decoration:none; }}
    .back-link {{ display:inline-block; margin-top:20px; }}
    footer {{ padding:28px 0 40px; color:var(--muted); font-size:12px; text-align:center; }}
    .login-wrap {{ min-height:100vh; display:grid; place-items:center; padding:24px; }}
    .login-card {{ width:min(420px,100%); padding:30px; }}
    .login-card label {{ display:block; margin:24px 0 8px; font-weight:700; }}
    .login-card input[type=password] {{ width:100%; min-height:48px; padding:10px 12px;
      border:1px solid var(--border); border-radius:10px; background:var(--surface);
      color:var(--text); font:inherit; }}
    .login-card button {{ width:100%; margin-top:14px; }}
    .filters {{ display:flex; align-items:end; gap:10px; flex-wrap:wrap; }}
    .filters label {{ display:grid; gap:6px; color:var(--muted); font-size:12px; }}
    .filters input,.filters select {{ min-height:44px; padding:8px 10px; border:1px solid var(--border);
      border-radius:10px; background:var(--surface); color:var(--text); font:inherit; }}
    .collector-metrics {{ grid-template-columns:repeat(6,1fr); margin-top:16px; }}
    .task-panel {{ border-top:3px solid var(--primary); }}
    .collector-main > section + section {{ margin-top:16px; }}
    .task-card {{ border-top:3px solid var(--primary); padding:24px; }}
    .task-head,.task-primary,.progress-summary > div:first-child,.coverage-summary,
    .failure-summary,.section-heading,.events-meta,.pagination,.date-nav {{
      display:flex; align-items:center; justify-content:space-between; gap:16px;
    }}
    .task-head {{ align-items:flex-start; }}
    .status-label {{ border-radius:999px; padding:7px 11px; font-size:12px; font-weight:800; }}
    .status-ok {{ color:#16875b; background:#eaf8f1; }}
    .status-warning {{ color:#b45309; background:#fff4e5; }}
    .task-primary {{ align-items:stretch; margin:22px 0 18px; }}
    .round-display {{ min-width:250px; padding-right:24px; border-right:1px solid var(--border); }}
    .round-display span,.task-meta span,.task-meta small {{ display:block; color:var(--muted); font-size:12px; }}
    .round-display strong {{ display:block; margin-top:7px; font-size:28px; }}
    .task-meta {{ flex:1; }} .task-meta strong {{ display:block; margin:7px 0; }}
    .progress-summary {{ margin-bottom:18px; }}
    .progress-track,.mini-track {{ height:8px; background:var(--soft); border-radius:999px; overflow:hidden; }}
    .progress-track {{ margin-top:9px; }}
    .progress-track span,.mini-track span {{ display:block; height:100%; background:var(--primary); border-radius:inherit; }}
    .stats-strip {{ display:grid; grid-template-columns:repeat(7,minmax(0,1fr));
      border:1px solid var(--border); border-radius:12px; overflow:hidden; }}
    .stat-item {{ padding:13px 14px; min-width:0; border-right:1px solid var(--border); }}
    .stat-item:last-child {{ border-right:0; }}
    .stat-item span {{ display:block; color:var(--muted); font-size:11px; margin-bottom:6px; }}
    .stat-item strong {{ display:block; font-size:17px; overflow-wrap:anywhere; }}
    .coverage-summary {{ margin-top:18px; padding-top:18px; border-top:1px solid var(--border); }}
    .coverage-summary span,.failure-summary span {{ display:block; margin-top:4px; color:var(--muted); font-size:12px; }}
    .compact-button {{ min-height:38px; padding:7px 12px; font-size:13px; }}
    .expandable {{ border:0; }}
    .expandable > summary {{ list-style:none; cursor:pointer; }}
    .expandable > summary::-webkit-details-marker {{ display:none; }}
    .expandable-content {{ padding-top:14px; animation:expand-reveal .18s ease; }}
    .summary-action {{ flex:none; min-height:38px; padding:8px 12px; border:1px solid var(--border);
      border-radius:9px; color:var(--primary) !important; background:var(--surface); font-size:13px !important;
      font-weight:750; text-align:center; }}
    .when-open {{ display:none !important; }}
    details[open] .when-closed {{ display:none !important; }}
    details[open] .when-open {{ display:inline !important; }}
    @keyframes expand-reveal {{ from {{ opacity:0; transform:translateY(-3px); }} to {{ opacity:1; transform:none; }} }}
    .coverage-table {{ min-width:760px; }}
    .failure-summary {{ margin-top:16px; padding:13px 14px; border-radius:10px;
      color:#9a4d08; background:#fff7e8; border:1px solid #f7d7a7; }}
    .failure-expand .expandable-content {{ overflow-x:auto; }}
    .events-panel,.distribution-panel {{ padding:24px; }}
    .section-heading {{ align-items:flex-start; margin-bottom:18px; }}
    .event-filters {{ padding:14px; background:var(--bg); border-radius:12px; margin-bottom:14px; }}
    .event-filters label {{ flex:1 1 132px; }}
    .event-filters label:nth-child(5) {{ flex-basis:220px; }}
    .event-filters input,.event-filters select {{ width:100%; }}
    .event-filters input[type=hidden] {{ display:none; }}
    .events-meta {{ margin:4px 0 10px; color:var(--muted); font-size:13px; }}
    .event-table {{ min-width:1040px; }}
    .event-type {{ display:inline-block; white-space:nowrap; padding:5px 8px; border-radius:999px;
      background:var(--soft); color:var(--primary); font-size:12px; font-weight:700; }}
    .pagination {{ justify-content:center; margin-top:16px; color:var(--muted); font-size:13px; }}
    button:disabled {{ opacity:.45; cursor:not-allowed; }}
    .local-loading {{ padding:10px 0; color:var(--primary); font-size:13px; }}
    .is-loading {{ opacity:.48; pointer-events:none; transition:opacity .15s ease; }}
    .empty-state {{ padding:32px 12px; text-align:center; color:var(--muted); }}
    .error-state {{ color:var(--danger); }}
    .distribution-heading {{ align-items:center; }}
    .date-nav {{ flex-wrap:wrap; justify-content:flex-end; gap:7px; }}
    .date-nav input {{ min-height:44px; padding:8px 10px; border:1px solid var(--border);
      border-radius:10px; background:var(--surface); color:var(--text); font:inherit; }}
    .distribution-summary {{ display:flex; flex-wrap:wrap; gap:10px 22px; padding:12px 14px;
      border:1px solid var(--border); border-radius:12px; margin-bottom:14px; color:var(--muted); }}
    .distribution-summary strong {{ color:var(--text); margin-right:4px; }}
    .interval-grid {{ display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:10px; }}
    .interval-card {{ min-height:0; padding:13px; text-align:left; background:var(--surface); color:var(--text);
      border:1px solid var(--border); border-radius:11px; font-weight:400; }}
    .interval-card:hover,.interval-card.selected {{ border-color:var(--primary); background:var(--soft); }}
    .interval-card.has-abnormal {{ border-color:#d97706; }}
    .interval-title {{ display:flex; justify-content:space-between; gap:8px; }}
    .interval-title span {{ color:var(--muted); font-size:12px; }}
    .mini-track {{ height:5px; margin:9px 0; }}
    .interval-stats {{ display:grid; grid-template-columns:1fr 1fr; gap:4px 8px;
      color:var(--muted); font-size:11px; }}
    .data-note {{ display:flex; gap:12px; align-items:flex-start; padding:5px 4px;
      color:var(--muted); font-size:12px; line-height:1.6; }}
    .data-note strong {{ color:var(--text); white-space:nowrap; }}
    .back-to-top {{ position:fixed; right:18px; bottom:18px; z-index:5; box-shadow:0 4px 16px #17223b18;
      opacity:0; pointer-events:none; transform:translateY(8px); transition:opacity .18s ease,transform .18s ease; }}
    .back-to-top.visible {{ opacity:1; pointer-events:auto; transform:translateY(0); }}
    .error {{ margin-top:16px; padding:12px; border-radius:10px;
      background:#fdecec; color:var(--danger); }}
    .notice {{ margin-top:16px; padding:12px; border-radius:10px;
      background:var(--soft); color:var(--primary); }}
    @media(max-width:920px) {{
      .metric-grid {{ grid-template-columns:repeat(3,1fr); }}
      .two-column {{ grid-template-columns:1fr; }}
      .collector-metrics {{ grid-template-columns:repeat(3,1fr); }}
      .stats-strip {{ grid-template-columns:repeat(4,minmax(0,1fr)); }}
      .stat-item {{ border-bottom:1px solid var(--border); }}
      .interval-grid {{ grid-template-columns:repeat(2,minmax(0,1fr)); }}
    }}
    @media(max-width:560px) {{
      .topbar {{ align-items:flex-start; flex-direction:column; padding-top:28px; }}
      .metric-grid {{ grid-template-columns:repeat(2,1fr); }}
      dl {{ grid-template-columns:1fr; gap:4px; }} dd {{ margin-bottom:10px; }}
      .task-card,.events-panel,.distribution-panel {{ padding:18px; }}
      .task-primary,.coverage-summary,.failure-summary,.section-heading {{ align-items:flex-start; flex-direction:column; }}
      .summary-action {{ width:100%; }}
      .round-display {{ min-width:0; width:100%; padding:0 0 14px; border-right:0; border-bottom:1px solid var(--border); }}
      .stats-strip {{ grid-template-columns:repeat(2,minmax(0,1fr)); }}
      .stat-item:nth-child(even) {{ border-right:0; }}
      .event-filters {{ display:grid; grid-template-columns:1fr 1fr; align-items:end; }}
      .event-filters label,.event-filters label:nth-child(5) {{ min-width:0; }}
      .event-filters label:nth-child(3),.event-filters label:nth-child(5) {{ grid-column:1/-1; }}
      .event-filters button {{ width:100%; justify-content:center; }}
      .pagination {{ flex-wrap:wrap; }}
      .distribution-heading {{ align-items:flex-start; }}
      .date-nav {{ justify-content:flex-start; }}
      .interval-grid {{ grid-template-columns:1fr; }}
      .data-note {{ flex-direction:column; }}
      .back-to-top {{ right:12px; bottom:12px; }}
    }}
    @media(prefers-color-scheme:dark) {{
      :root {{ --bg:#10131a; --surface:#181d27; --text:#f1f4f8; --muted:#b0bac9;
        --border:#303846; --primary:#7ea2ff; --soft:#25345c; --danger:#ff8a8a; }}
      .error {{ background:#432326; }}
      .status-ok {{ color:#77d5ae; background:#193b31; }}
      .status-warning {{ color:#ffc178; background:#49341c; }}
      .failure-summary {{ color:#ffc178; background:#33291d; border-color:#6f512d; }}
    }}
  </style>
</head>
<body>{content}</body>
</html>"""


def main() -> None:
    settings = Settings()
    service = ElecService(settings)
    Handler.service = service
    server = ElecHttpServer((settings.host, settings.port), Handler)
    print(
        f"{iso_utc_now()} elec service listening on "
        f"{settings.host}:{settings.port}",
        flush=True,
    )
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        service.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
