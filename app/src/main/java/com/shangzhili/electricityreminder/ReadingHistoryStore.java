package com.shangzhili.electricityreminder;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 保存原始查询读数，并分别提供“每日最后读数”和“每小时最后读数”两个视图。
 * 月度统计继续使用每日点，近期余额与速率趋势使用小时点，原始数据本身不会被归并删除。
 */
public final class ReadingHistoryStore extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "reading_history.db";
    private static final int DATABASE_VERSION = 9;
    private static final long RETENTION_MILLIS = 400L * 24 * 60 * 60 * 1_000;

    public ReadingHistoryStore(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL(
                "CREATE TABLE readings ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "room_id TEXT NOT NULL,"
                        + "sample_time INTEGER NOT NULL,"
                        + "surplus REAL NOT NULL,"
                        + "amount REAL NOT NULL,"
                        + "source TEXT NOT NULL,"
                        + "cloud_sample_key TEXT,"
                        + "change_start_time INTEGER,"
                        + "change_type TEXT)"
        );
        database.execSQL(
                "CREATE INDEX readings_room_time ON readings(room_id, sample_time)"
        );
        createCloudHistoryIndex(database);
        createRechargeSchema(database);
        createRechargeAttemptSchema(database);
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        // 所有升级都只追加字段或表，不删除 readings，确保历史余额曲线完整保留。
        if (oldVersion < 2) {
            // 从 v1 升级时直接创建最新结构，不能随后再次 ALTER 同一个字段。
            createRechargeSchema(database);
        } else if (oldVersion < 3) {
            migrateRechargeTimes(database);
        }
        if (oldVersion < 4) {
            // 独立尝试表不会改写 recharges，v1/v2/v3 都可直接创建且不丢历史数据。
            createRechargeAttemptSchema(database);
        } else if (oldVersion < 5) {
            // v4 使用余额上涨推测到账；v5 追加官方 payNo，并安全终止无法精确查询的旧尝试。
            migrateRechargeAttemptsToOfficialOrder(database);
        }
        if (oldVersion < 6) {
            // v6 只给 readings 增加可空幂等键。已有本地记录全部保持 NULL，
            // 充值表和用户历史不会被重写；随后建立的部分唯一索引只约束云端记录。
            database.execSQL("ALTER TABLE readings ADD COLUMN cloud_sample_key TEXT");
        }
        if (oldVersion < 7) {
            // 开发期 v6 曾把 cloud_sample_key 设为全库唯一，导致同一物理房间以两个备注
            // 添加时第二个本地 roomId 无法导入。v7 改成“本地房间 + 云端键”联合唯一。
            database.execSQL("DROP INDEX IF EXISTS readings_cloud_sample_key");
            createCloudHistoryIndex(database);
        }
        if (oldVersion >= 2 && oldVersion < 8) {
            /*
             * 旧版只有一张充值表，无法区分“用户手工登记”和“校付宝官方订单确认”。
             * 默认值 deliberately 设为 manual：不能把历史手填金额冒充为官方支付事实。
             * 记录不会丢失，月度统计等既有功能仍可读取全部充值。
             */
            database.execSQL(
                    "ALTER TABLE recharges ADD COLUMN source TEXT NOT NULL DEFAULT 'manual'"
            );
            /*
             * v5～v7 已经保存了官方确认 attempt 与 recharge_id 的关联，可无歧义恢复来源；
             * 更早或单纯手填的旧记录继续保持 manual，避免错误参与精确趋势校正。
             */
            if (oldVersion >= 5) {
                database.execSQL(
                        "UPDATE recharges SET source = 'official' WHERE id IN ("
                                + "SELECT recharge_id FROM recharge_attempts "
                                + "WHERE status = 'confirmed' AND recharge_id IS NOT NULL)"
                );
            }
        }
        if (oldVersion < 9) {
            database.execSQL("ALTER TABLE readings ADD COLUMN change_start_time INTEGER");
            database.execSQL("ALTER TABLE readings ADD COLUMN change_type TEXT");
            /*
             * 旧云端缓存没有变化事件字段。它本来就是可重新下载的公共副本，因此只删除
             * source=cloud 的记录，让下一次详情页同步重新获取最近 30 天事件；用户本地
             * 手动/后台查询、充值记录、房间别名和提醒配置完全不动。
             */
            database.delete("readings", "source = ?", new String[]{"cloud"});
        }
    }

    public void record(String roomId, Reading reading, String source) {
        SQLiteDatabase database = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("room_id", roomId);
        values.put("sample_time", reading.timestamp);
        values.put("surplus", reading.surplus);
        values.put("amount", reading.amount);
        values.put("source", source);
        database.insert("readings", null, values);

        // 历史只保留约 400 天，既能展示年度趋势，也避免数据库无限增长。
        database.delete(
                "readings", "sample_time < ?",
                new String[]{Long.toString(System.currentTimeMillis() - RETENTION_MILLIS)}
        );
    }

    /**
     * 把公共云端采样合并到当前用户的本地 roomId。
     *
     * <p>云端按真实 roomCode 提供公共数据，本地仍按随机 roomId 隔离用户配置。方法先校验
     * 每条记录的 roomCode，再以 cloud_sample_key INSERT OR IGNORE；因此重复请求、分页
     * 重叠和进程重试都不会插入重复点。这里只写 readings，绝不访问或覆盖 recharges。</p>
     */
    public int mergeCloudHistory(
            String roomId,
            String expectedRoomCode,
            List<CloudHistoryRecord> records
    ) {
        if (roomId == null || roomId.isEmpty()
                || expectedRoomCode == null
                || !expectedRoomCode.matches("\\d{15}")
                || records == null
                || records.isEmpty()) {
            return 0;
        }
        SQLiteDatabase database = getWritableDatabase();
        int imported = 0;
        database.beginTransaction();
        try {
            for (CloudHistoryRecord record : records) {
                if (record == null
                        || !record.isValidSuccess()
                        || !expectedRoomCode.equals(record.roomCode)) {
                    continue;
                }
                ContentValues values = new ContentValues();
                values.put("room_id", roomId);
                values.put("sample_time", record.queriedAt);
                values.put("surplus", record.surplus);
                values.put("amount", record.amount);
                values.put("source", "cloud");
                values.put("cloud_sample_key", record.sampleKey);
                if (record.changeStartAt > 0) {
                    values.put("change_start_time", record.changeStartAt);
                }
                if (!record.changeType.isEmpty()) {
                    values.put("change_type", record.changeType);
                }
                long inserted = database.insertWithOnConflict(
                        "readings", null, values, SQLiteDatabase.CONFLICT_IGNORE
                );
                if (inserted != -1) imported++;
            }
            database.delete(
                    "readings", "sample_time < ?",
                    new String[]{Long.toString(
                            System.currentTimeMillis() - RETENTION_MILLIS
                    )}
            );
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        return imported;
    }

    /** 返回该本地房间最近一次已导入云端采样的时间，用于下次增量查询。 */
    public long latestCloudTimestamp(String roomId) {
        Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT MAX(sample_time) FROM readings "
                        + "WHERE room_id = ? AND cloud_sample_key IS NOT NULL",
                new String[]{roomId}
        );
        try {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getLong(0) : 0;
        } finally {
            cursor.close();
        }
    }

    public List<HistoryPoint> loadDailyPoints(String roomId, int days) {
        long since = System.currentTimeMillis() - days * 24L * 60 * 60 * 1_000;
        Cursor cursor = getReadableDatabase().query(
                "readings",
                new String[]{
                        "sample_time", "surplus", "amount",
                        "change_start_time", "change_type"
                },
                "room_id = ? AND sample_time >= ?",
                new String[]{roomId, Long.toString(since)},
                null, null, "sample_time ASC"
        );

        // 按本机时区划分自然日；同一天后读到的点覆盖前一个点，最终留下当天最后读数。
        Map<LocalDate, HistoryPoint> daily = new LinkedHashMap<>();
        ZoneId zoneId = ZoneId.systemDefault();
        try {
            while (cursor.moveToNext()) {
                long timestamp = cursor.getLong(0);
                LocalDate date = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate();
                daily.put(date, historyPoint(cursor));
            }
        } finally {
            cursor.close();
        }
        return new ArrayList<>(daily.values());
    }

    /**
     * 读取最近若干小时的原始余额，并把同一自然小时内的重复手动刷新归并为最后一次读数。
     *
     * <p>查询范围额外向前多取两小时，便于趋势计算器使用可见窗口之前的一个点计算第一个
     * 区间的耗电速率。时间轴仍使用真实时间戳，网络延迟不会被伪装为恰好整点。</p>
     */
    public List<HistoryPoint> loadHourlyPoints(String roomId, int hours) {
        long since = System.currentTimeMillis() - (hours + 2L) * 60 * 60 * 1_000;
        Cursor cursor = getReadableDatabase().query(
                "readings",
                new String[]{
                        "sample_time", "surplus", "amount",
                        "change_start_time", "change_type"
                },
                "room_id = ? AND sample_time >= ?",
                new String[]{roomId, Long.toString(since)},
                null, null, "sample_time ASC"
        );
        Map<Long, HistoryPoint> hourly = new LinkedHashMap<>();
        ZoneId zoneId = ZoneId.systemDefault();
        try {
            while (cursor.moveToNext()) {
                long timestamp = cursor.getLong(0);
                long hourStart = Instant.ofEpochMilli(timestamp)
                        .atZone(zoneId)
                        .truncatedTo(ChronoUnit.HOURS)
                        .toInstant()
                        .toEpochMilli();
                hourly.put(hourStart, historyPoint(cursor));
            }
        } finally {
            cursor.close();
        }
        return new ArrayList<>(hourly.values());
    }

    /** 同时兼容本地无事件采样和服务器带变化区间的公共采样。 */
    private HistoryPoint historyPoint(Cursor cursor) {
        return new HistoryPoint(
                cursor.getLong(0),
                cursor.getDouble(1),
                cursor.getDouble(2),
                cursor.isNull(3) ? 0 : cursor.getLong(3),
                cursor.isNull(4) ? "" : cursor.getString(4)
        );
    }

    /**
     * 保存一笔用户确认的充值金额，精确到本地分钟。
     *
     * <p>recharge_date 继续写入是为了兼容已经发布的旧数据库结构和必要时的降级读取；
     * 新算法统一使用 recharge_time，因此同一天多笔充值不会再被混成一个日期事件。</p>
     */
    public long recordRecharge(
            String roomId, LocalDate date, LocalTime time, double amount
    ) {
        if (roomId == null || roomId.isEmpty() || date == null || time == null || amount <= 0) {
            throw new IllegalArgumentException("充值房间、日期、时间和金额无效");
        }
        long timestamp = date.atTime(time.withSecond(0).withNano(0))
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
        SQLiteDatabase database = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("room_id", roomId);
        values.put("recharge_date", date.toEpochDay());
        values.put("recharge_time", timestamp);
        values.put("amount", amount);
        values.put("source", "manual");
        long id = database.insertOrThrow("recharges", null, values);

        // 余额原始数据只保留约 400 天；更早的充值已没有对应采样可用于修正，因此同步清理。
        database.delete(
                "recharges", "recharge_time < ?",
                new String[]{Long.toString(System.currentTimeMillis() - RETENTION_MILLIS)}
        );
        return id;
    }

    /**
     * 在校付宝已经返回收银台地址后，持久化一次待确认尝试。
     *
     * <p>必须在打开微信前完成插入：即使支付期间 Android 回收了 App 进程，用户再次进入
     * 房间时仍能使用 payNo 恢复官方订单查询。paymentNo 只保存在本机数据库，不写日志、
     * 不上传开发者服务器。</p>
     */
    public RechargeAttempt createRechargeAttempt(
            String roomId, String normalizedAmount, String paymentNo
    ) {
        if (roomId == null || roomId.isEmpty()
                || paymentNo == null
                || !paymentNo.matches("\\d{10,40}")) {
            throw new IllegalArgumentException("充值尝试缺少房间或官方支付单号");
        }
        long requestedCents;
        try {
            requestedCents = new BigDecimal(normalizedAmount)
                    .setScale(2, RoundingMode.UNNECESSARY)
                    .movePointRight(2)
                    .longValueExact();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("充值金额格式无效", exception);
        }
        if (requestedCents <= 0) {
            throw new IllegalArgumentException("充值金额无效");
        }

        long now = System.currentTimeMillis();
        RechargeAttempt attempt = new RechargeAttempt(
                "attempt-" + UUID.randomUUID(),
                roomId,
                requestedCents,
                now,
                0,
                0,
                0,
                RechargeAttempt.STATUS_PENDING,
                0,
                null,
                false,
                paymentNo
        );
        ContentValues values = new ContentValues();
        values.put("attempt_id", attempt.attemptId);
        values.put("room_id", roomId);
        values.put("requested_cents", requestedCents);
        // baseline_cents 保留是为了无损兼容已发布的 v4 表；v5 官方订单确认不再读取它。
        values.put("baseline_cents", 0);
        values.put("created_at", now);
        values.put("verification_generation", 0);
        values.putNull("launched_at");
        values.putNull("returned_at");
        values.put("status", RechargeAttempt.STATUS_PENDING);
        values.putNull("recharge_id");
        values.putNull("result_notice");
        values.put("result_notice_shown", 0);
        values.put("payment_no", paymentNo);
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            // 同一房间只允许一笔尝试参与自动确认。旧尝试仍保留审计记录但不再轮询；
            // payment_no 唯一索引还保证同一官方支付单不能被两个本地 attempt 重复记账。
            ContentValues superseded = new ContentValues();
            superseded.put("status", RechargeAttempt.STATUS_SUPERSEDED);
            superseded.put("result_notice_shown", 1);
            database.update(
                    "recharge_attempts", superseded,
                    "room_id = ? AND status IN (?, ?)",
                    new String[]{
                            roomId,
                            RechargeAttempt.STATUS_PENDING,
                            RechargeAttempt.STATUS_UNCONFIRMED
                    }
            );
            database.insertOrThrow("recharge_attempts", null, values);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
        pruneOldRechargeAttempts();
        return attempt;
    }

    /** 只有 Activity 启动请求被系统接受后才写入，未启动尝试绝不能进入官方订单确认。 */
    public void markRechargeAttemptLaunched(String attemptId) {
        if (attemptId == null || attemptId.isEmpty()) return;
        ContentValues values = new ContentValues();
        values.put("launched_at", System.currentTimeMillis());
        getWritableDatabase().update(
                "recharge_attempts", values,
                "attempt_id = ? AND launched_at IS NULL",
                new String[]{attemptId}
        );
    }

    /** 支付页返回只记录时间，不把状态改成 confirmed。 */
    public void markRechargeAttemptReturned(String attemptId) {
        if (attemptId == null || attemptId.isEmpty()) return;
        ContentValues values = new ContentValues();
        values.put("returned_at", System.currentTimeMillis());
        getWritableDatabase().update(
                "recharge_attempts", values,
                "attempt_id = ? AND returned_at IS NULL",
                new String[]{attemptId}
        );
    }

    /** 读取该房间最近一笔尚未确认的尝试，用于进程恢复后继续检测。 */
    public RechargeAttempt loadLatestVerifiableRechargeAttempt(
            String roomId, long maximumAgeMillis
    ) {
        long since = System.currentTimeMillis() - Math.max(0, maximumAgeMillis);
        Cursor cursor = getReadableDatabase().query(
                "recharge_attempts",
                new String[]{
                        "attempt_id", "room_id", "requested_cents", "created_at",
                        "verification_generation", "launched_at",
                        "returned_at", "status", "recharge_id", "result_notice",
                        "result_notice_shown", "payment_no"
                },
                "room_id = ? AND launched_at IS NOT NULL AND returned_at IS NOT NULL "
                        + "AND payment_no IS NOT NULL "
                        + "AND status IN (?, ?) AND created_at >= ?",
                new String[]{
                        roomId,
                        RechargeAttempt.STATUS_PENDING,
                        RechargeAttempt.STATUS_UNCONFIRMED,
                        Long.toString(since)
                },
                null, null, "created_at DESC", "1"
        );
        try {
            return cursor.moveToFirst() ? readRechargeAttempt(cursor) : null;
        } finally {
            cursor.close();
        }
    }

    public RechargeAttempt loadRechargeAttempt(String attemptId) {
        if (attemptId == null || attemptId.isEmpty()) return null;
        return loadRechargeAttempt(getReadableDatabase(), attemptId);
    }

    /**
     * 原子领取该房间最近一条尚未展示的到账结果。
     *
     * <p>“查询”和“标记已展示”必须处于同一事务，并同时校验结果类型和数据库代次。
     * 否则 Activity 恢复回调与正在结束的轮询可能各弹一次提示，或者失败提示刚被重试
     * 改成成功提示后，旧回调误把新的成功结果消费掉。</p>
     */
    public RechargeAttempt claimLatestUnshownRechargeNotice(String roomId) {
        if (roomId == null || roomId.isEmpty()) return null;
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            Cursor cursor = database.query(
                    "recharge_attempts",
                    new String[]{
                            "attempt_id", "room_id", "requested_cents", "created_at",
                            "verification_generation", "launched_at",
                            "returned_at", "status", "recharge_id", "result_notice",
                            "result_notice_shown", "payment_no"
                    },
                    "room_id = ? AND result_notice IS NOT NULL "
                            + "AND result_notice_shown = 0",
                    new String[]{roomId},
                    null, null, "created_at DESC", "1"
            );
            RechargeAttempt attempt;
            try {
                attempt = cursor.moveToFirst() ? readRechargeAttempt(cursor) : null;
            } finally {
                cursor.close();
            }
            RechargeAttempt claimed = claimRechargeNotice(database, attempt);
            database.setTransactionSuccessful();
            return claimed;
        } finally {
            database.endTransaction();
        }
    }

    /**
     * 原子领取指定尝试的当前结果。调用者不传入旧状态，方法会在事务内重新读取，
     * 因而“超时失败”和“并发到账成功”竞争时，最终只会展示数据库里的真实终态。
     */
    public RechargeAttempt claimRechargeNotice(String attemptId) {
        if (attemptId == null || attemptId.isEmpty()) return null;
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            RechargeAttempt claimed = claimRechargeNotice(
                    database, loadRechargeAttempt(database, attemptId)
            );
            database.setTransactionSuccessful();
            return claimed;
        } finally {
            database.endTransaction();
        }
    }

    /**
     * Activity 在用户确认弹窗前被重建时，把刚领取的结果退回“未展示”。
     * CAS 字段与领取时完全一致，因此不会把已经重试或改变终态的新结果错误释放。
     */
    public boolean releaseRechargeNotice(RechargeAttempt attempt) {
        if (attempt == null
                || attempt.resultNotice == null) {
            return false;
        }
        ContentValues values = new ContentValues();
        values.put("result_notice_shown", 0);
        return getWritableDatabase().update(
                "recharge_attempts", values,
                "attempt_id = ? AND status = ? AND result_notice = ? "
                        + "AND verification_generation = ? "
                        + "AND result_notice_shown = 1",
                new String[]{
                        attempt.attemptId,
                        attempt.status,
                        attempt.resultNotice,
                        Long.toString(attempt.verificationGeneration)
                }
        ) == 1;
    }

    /**
     * 为一次检测分配数据库代次。用户暂停或重新检测会推进代次，旧网络回调即使稍后返回，
     * confirmRechargeAttempt 也无法提交，从而不依赖跨线程 boolean 的可见性。
     */
    public long beginRechargeVerification(String attemptId) {
        if (attemptId == null || attemptId.isEmpty()) return -1;
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            RechargeAttempt before = loadRechargeAttempt(database, attemptId);
            if (before == null
                    || before.launchedAt <= 0
                    || before.returnedAt <= 0
                    || before.paymentNo == null
                    || before.paymentNo.isEmpty()
                    || (!RechargeAttempt.STATUS_PENDING.equals(before.status)
                    && !RechargeAttempt.STATUS_UNCONFIRMED.equals(before.status))) {
                database.setTransactionSuccessful();
                return -1;
            }

            long nextGeneration = before.verificationGeneration + 1;
            ContentValues values = new ContentValues();
            values.put("status", RechargeAttempt.STATUS_PENDING);
            values.put("verification_generation", nextGeneration);
            values.putNull("result_notice");
            values.put("result_notice_shown", 0);
            int changed = database.update(
                    "recharge_attempts", values,
                    "attempt_id = ? AND verification_generation = ? "
                            + "AND status = ? AND launched_at IS NOT NULL "
                            + "AND returned_at IS NOT NULL",
                    new String[]{
                            attemptId,
                            Long.toString(before.verificationGeneration),
                            before.status
                    }
            );
            database.setTransactionSuccessful();
            return changed == 1 ? nextGeneration : -1;
        } finally {
            database.endTransaction();
        }
    }

    /**
     * 在同一个 SQLite 事务内插入充值记录并确认 attempt，保证重复回调和进程异常都不会
     * 让同一次支付写入两条记录。
     *
     * @return 本轮新写入的充值记录 ID；代次已失效、尝试已结束或不存在时返回 -1。
     */
    public long confirmRechargeAttempt(
            String attemptId,
            long verificationGeneration,
            long officialPaidAt
    ) {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            RechargeAttempt attempt = loadRechargeAttempt(database, attemptId);
            if (attempt == null) return -1;
            if (!RechargeAttempt.STATUS_PENDING.equals(attempt.status)
                    || attempt.verificationGeneration != verificationGeneration
                    || attempt.launchedAt <= 0
                    || attempt.returnedAt <= 0
                    || attempt.paymentNo == null
                    || attempt.paymentNo.isEmpty()
                    || officialPaidAt <= 0) {
                database.setTransactionSuccessful();
                return -1;
            }

            // 直接使用官方订单 payTime，而不是微信返回时间或余额变化时间；同一天多笔
            // 充值仍能按真实支付时刻归入正确采样区间。
            long timestamp = officialPaidAt;
            LocalDate date = Instant.ofEpochMilli(timestamp)
                    .atZone(ZoneId.systemDefault()).toLocalDate();
            ContentValues recharge = new ContentValues();
            recharge.put("room_id", attempt.roomId);
            recharge.put("recharge_date", date.toEpochDay());
            recharge.put("recharge_time", timestamp);
            recharge.put("amount", attempt.requestedCents / 100.0);
            recharge.put("source", "official");
            long rechargeId = database.insertOrThrow("recharges", null, recharge);
            database.delete(
                    "recharges", "recharge_time < ?",
                    new String[]{Long.toString(
                            System.currentTimeMillis() - RETENTION_MILLIS
                    )}
            );

            ContentValues confirmed = new ContentValues();
            confirmed.put("status", RechargeAttempt.STATUS_CONFIRMED);
            confirmed.put("recharge_id", rechargeId);
            confirmed.put("result_notice", RechargeAttempt.STATUS_CONFIRMED);
            confirmed.put("result_notice_shown", 0);
            database.update(
                    "recharge_attempts", confirmed, "attempt_id = ?",
                    new String[]{attemptId}
            );
            database.setTransactionSuccessful();
            return rechargeId;
        } finally {
            database.endTransaction();
        }
    }

    /** 官方详情明确返回支付失败时立即结束，不再继续轮询到超时。 */
    public boolean markRechargeAttemptFailed(
            String attemptId, long verificationGeneration
    ) {
        if (attemptId == null || attemptId.isEmpty()) return false;
        ContentValues values = new ContentValues();
        values.put("status", RechargeAttempt.STATUS_FAILED);
        values.put("verification_generation", verificationGeneration + 1);
        values.put("result_notice", RechargeAttempt.STATUS_FAILED);
        values.put("result_notice_shown", 0);
        return getWritableDatabase().update(
                "recharge_attempts", values,
                "attempt_id = ? AND status = ? AND verification_generation = ?",
                new String[]{
                        attemptId,
                        RechargeAttempt.STATUS_PENDING,
                        Long.toString(verificationGeneration)
                }
        ) == 1;
    }

    /** 超时只标为未确认，以便页面说明“未检测到到账”，不冒充微信支付失败。 */
    public boolean markRechargeAttemptUnconfirmed(
            String attemptId, long verificationGeneration
    ) {
        if (attemptId == null || attemptId.isEmpty()) return false;
        ContentValues values = new ContentValues();
        values.put("status", RechargeAttempt.STATUS_UNCONFIRMED);
        values.put("verification_generation", verificationGeneration + 1);
        values.put("result_notice", RechargeAttempt.STATUS_UNCONFIRMED);
        values.put("result_notice_shown", 0);
        return getWritableDatabase().update(
                "recharge_attempts", values,
                "attempt_id = ? AND status = ? AND verification_generation = ?",
                new String[]{
                        attemptId,
                        RechargeAttempt.STATUS_PENDING,
                        Long.toString(verificationGeneration)
                }
        ) == 1;
    }

    public void deleteRechargeAttempt(String attemptId) {
        if (attemptId == null || attemptId.isEmpty()) return;
        getWritableDatabase().delete(
                "recharge_attempts", "attempt_id = ?", new String[]{attemptId}
        );
    }

    /** 按精确时间升序读取，统计算法可线性判断每笔充值属于哪个相邻采样区间。 */
    public List<RechargeRecord> loadRecharges(String roomId) {
        Cursor cursor = getReadableDatabase().query(
                "recharges",
                new String[]{"id", "recharge_time", "amount", "source"},
                "room_id = ?", new String[]{roomId},
                null, null, "recharge_time ASC, id ASC"
        );
        List<RechargeRecord> result = new ArrayList<>();
        ZoneId zoneId = ZoneId.systemDefault();
        try {
            while (cursor.moveToNext()) {
                result.add(new RechargeRecord(
                        cursor.getLong(0), cursor.getLong(1), cursor.getDouble(2), zoneId,
                        "official".equals(cursor.getString(3))
                ));
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    public void deleteRecharge(String roomId, long rechargeId) {
        getWritableDatabase().delete(
                "recharges", "room_id = ? AND id = ?",
                new String[]{roomId, Long.toString(rechargeId)}
        );
    }

    public void deleteRoom(String roomId) {
        SQLiteDatabase database = getWritableDatabase();
        database.delete("readings", "room_id = ?", new String[]{roomId});
        database.delete("recharges", "room_id = ?", new String[]{roomId});
        database.delete("recharge_attempts", "room_id = ?", new String[]{roomId});
    }

    private void createRechargeSchema(SQLiteDatabase database) {
        database.execSQL(
                "CREATE TABLE IF NOT EXISTS recharges ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "room_id TEXT NOT NULL,"
                        + "recharge_date INTEGER NOT NULL,"
                        + "recharge_time INTEGER NOT NULL,"
                        + "amount REAL NOT NULL,"
                        + "source TEXT NOT NULL DEFAULT 'manual')"
        );
        database.execSQL(
                "CREATE INDEX IF NOT EXISTS recharges_room_date "
                        + "ON recharges(room_id, recharge_date)"
        );
        database.execSQL(
                "CREATE INDEX IF NOT EXISTS recharges_room_time "
                        + "ON recharges(room_id, recharge_time)"
        );
    }

    private void createCloudHistoryIndex(SQLiteDatabase database) {
        database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS readings_room_cloud_sample_key "
                        + "ON readings(room_id, cloud_sample_key) "
                        + "WHERE cloud_sample_key IS NOT NULL"
        );
    }

    private void createRechargeAttemptSchema(SQLiteDatabase database) {
        database.execSQL(
                "CREATE TABLE IF NOT EXISTS recharge_attempts ("
                        + "attempt_id TEXT PRIMARY KEY,"
                        + "room_id TEXT NOT NULL,"
                        + "requested_cents INTEGER NOT NULL,"
                        + "baseline_cents INTEGER NOT NULL,"
                        + "created_at INTEGER NOT NULL,"
                        + "verification_generation INTEGER NOT NULL DEFAULT 0,"
                        + "launched_at INTEGER,"
                        + "returned_at INTEGER,"
                        + "status TEXT NOT NULL,"
                        + "recharge_id INTEGER,"
                        + "result_notice TEXT,"
                        + "result_notice_shown INTEGER NOT NULL DEFAULT 0,"
                        + "payment_no TEXT)"
        );
        database.execSQL(
                "CREATE INDEX IF NOT EXISTS recharge_attempts_room_status "
                        + "ON recharge_attempts(room_id, status, created_at)"
        );
        database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS recharge_attempts_payment_no "
                        + "ON recharge_attempts(payment_no) "
                        + "WHERE payment_no IS NOT NULL"
        );
    }

    private RechargeAttempt loadRechargeAttempt(
            SQLiteDatabase database, String attemptId
    ) {
        Cursor cursor = database.query(
                "recharge_attempts",
                new String[]{
                        "attempt_id", "room_id", "requested_cents", "created_at",
                        "verification_generation", "launched_at",
                        "returned_at", "status", "recharge_id", "result_notice",
                        "result_notice_shown", "payment_no"
                },
                "attempt_id = ?", new String[]{attemptId},
                null, null, null, "1"
        );
        try {
            return cursor.moveToFirst() ? readRechargeAttempt(cursor) : null;
        } finally {
            cursor.close();
        }
    }

    private RechargeAttempt readRechargeAttempt(Cursor cursor) {
        return new RechargeAttempt(
                cursor.getString(0),
                cursor.getString(1),
                cursor.getLong(2),
                cursor.getLong(3),
                cursor.getLong(4),
                cursor.isNull(5) ? 0 : cursor.getLong(5),
                cursor.isNull(6) ? 0 : cursor.getLong(6),
                cursor.getString(7),
                cursor.isNull(8) ? 0 : cursor.getLong(8),
                cursor.isNull(9) ? null : cursor.getString(9),
                cursor.getInt(10) != 0,
                cursor.isNull(11) ? null : cursor.getString(11)
        );
    }

    /**
     * 在调用方持有写事务时，用“尝试 ID + 结果 + 代次 + 未展示”完成一次 CAS 领取。
     * 返回 null 表示结果已由另一个页面领取，调用方不得再弹窗。
     */
    private RechargeAttempt claimRechargeNotice(
            SQLiteDatabase database, RechargeAttempt attempt
    ) {
        if (attempt == null
                || attempt.resultNotice == null
                || attempt.resultNoticeShown) {
            return null;
        }
        ContentValues values = new ContentValues();
        values.put("result_notice_shown", 1);
        int changed = database.update(
                "recharge_attempts", values,
                "attempt_id = ? AND status = ? AND result_notice = ? "
                        + "AND verification_generation = ? "
                        + "AND result_notice_shown = 0",
                new String[]{
                        attempt.attemptId,
                        attempt.status,
                        attempt.resultNotice,
                        Long.toString(attempt.verificationGeneration)
                }
        );
        return changed == 1 ? attempt : null;
    }

    private void pruneOldRechargeAttempts() {
        // 与余额/充值历史使用相同保留期；已确认尝试只承担幂等凭据，不无限增长。
        getWritableDatabase().delete(
                "recharge_attempts", "created_at < ?",
                new String[]{Long.toString(System.currentTimeMillis() - RETENTION_MILLIS)}
        );
    }

    /**
     * 旧记录只有日期。升级时将它们安全地放在当天 12:00；用户仍可删除后按真实时间重录，
     * 同时不会因为新增 NOT NULL 字段而丢失既有充值修正。
     */
    private void migrateRechargeTimes(SQLiteDatabase database) {
        database.execSQL(
                "ALTER TABLE recharges ADD COLUMN recharge_time INTEGER NOT NULL DEFAULT 0"
        );
        Cursor cursor = database.query(
                "recharges", new String[]{"id", "recharge_date"},
                "recharge_time = 0", null, null, null, null
        );
        ZoneId zoneId = ZoneId.systemDefault();
        List<Long> ids = new ArrayList<>();
        List<Long> timestamps = new ArrayList<>();
        try {
            while (cursor.moveToNext()) {
                long timestamp = LocalDate.ofEpochDay(cursor.getLong(1))
                        .atTime(12, 0)
                        .atZone(zoneId)
                        .toInstant()
                        .toEpochMilli();
                ids.add(cursor.getLong(0));
                timestamps.add(timestamp);
            }
        } finally {
            cursor.close();
        }
        // 先关闭查询游标再逐行更新，避免部分 SQLite 实现中“读同一表时写同一表”的锁竞争。
        for (int index = 0; index < ids.size(); index++) {
            ContentValues values = new ContentValues();
            values.put("recharge_time", timestamps.get(index));
            database.update(
                    "recharges", values, "id = ?",
                    new String[]{Long.toString(ids.get(index))}
            );
        }
        database.execSQL(
                "CREATE INDEX IF NOT EXISTS recharges_room_time "
                        + "ON recharges(room_id, recharge_time)"
        );
    }

    /**
     * v4→v5：增加校付宝 payNo，并终止无法精确匹配官方订单的旧版 pending 尝试。
     *
     * <p>旧尝试没有收银台 tran_no，继续用金额差额推测会违背 v5 的官方订单确认原则；
     * 因此把它们安全标为“未确认”并提示用户，而不是自动补记或静默丢弃。</p>
     */
    private void migrateRechargeAttemptsToOfficialOrder(SQLiteDatabase database) {
        database.execSQL(
                "ALTER TABLE recharge_attempts ADD COLUMN payment_no TEXT"
        );
        database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS recharge_attempts_payment_no "
                        + "ON recharge_attempts(payment_no) "
                        + "WHERE payment_no IS NOT NULL"
        );
        database.execSQL(
                "UPDATE recharge_attempts "
                        + "SET status = ?, "
                        + "verification_generation = verification_generation + 1, "
                        + "result_notice = ?, result_notice_shown = 0 "
                        + "WHERE status = ?",
                new Object[]{
                        RechargeAttempt.STATUS_UNCONFIRMED,
                        RechargeAttempt.STATUS_UNCONFIRMED,
                        RechargeAttempt.STATUS_PENDING
                }
        );
    }
}
