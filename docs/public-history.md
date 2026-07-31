# 公共房间历史缓存

## 数据边界

云端只保存南昌校区指定 12 栋楼的公共房间目录、整点余额采样、变化事件和采集任务明细。
用户的房间备注、提醒阈值、监测开关、通知状态、手动/自动充值记录继续只保存在手机。

App 仍先完成校付宝实时查询并写入本地 SQLite，随后才异步拉取云端补充历史。云端超时、
断网、HTTP 异常、JSON 损坏或数据库不可用都会静默返回本地数据，不弹阻塞窗口，也不影响
查询、图表、提醒、充值和后台调度。

## 精确采集范围

采集范围由以下 `buildingCode` 白名单决定，不使用名称模糊匹配：

| buildingCode | 楼栋 |
| --- | --- |
| 001001001 | 第一公寓 |
| 001001002 | 第二公寓 |
| 001001003 | 第四公寓 |
| 001001004 | 第五公寓 |
| 001001005 | 第六公寓 |
| 001001006 | 第七公寓 |
| 001001007 | 第八公寓 |
| 001001008 | 第九公寓 |
| 001001014 | 一号家属楼 |
| 001001015 | 二号家属楼 |
| 001001016 | 三号家属楼 |
| 001001017 | 四号家属楼 |

目录每天至少完整同步一次。每栋楼只有在楼层、房间接口全部成功后才在一个事务中替换；
暂时失败不会把旧目录误判为删除。房间按 15 位 `roomCode` 去重，跨楼栋或层级前缀异常的
返回会被丢弃。无电表房间保留目录项和分类错误，方便后台审计。
余额允许为负数；负数表示欠费，属于有效成功样本，不能按接口异常或无电表处理。

## 调度和失败控制

- 时区固定为 `Asia/Shanghai`。
- 每日 08:00–20:00 的每个整点启动，共 13 轮。
- 服务器重启后只在整点后 10 分钟内补跑当前轮次。
- 单进程非阻塞锁禁止上一轮未完成时叠加下一轮。
- 校付宝请求默认单并发、最小间隔 250ms、12 秒超时、最多重试 2 次。
- 只对网络、超时和临时 HTTP 错误指数退避；授权、无电表和格式错误不盲目重试。
- `(room_code, slot_time)` 唯一约束保证任务重跑幂等；失败占位允许被同轮成功结果修复。

## 数据库迁移

服务端公共历史使用独立的 `public_history.sqlite3`，启动时以
`CREATE TABLE/INDEX IF NOT EXISTS` 自动创建 v1 结构，`PRAGMA user_version = 1`。
它不迁移或修改现有 `analytics.sqlite3`。

Android 本地 `reading_history.db` 从 v5 升级到 v7：

1. 仅向 `readings` 增加可空的 `cloud_sample_key`；
2. 建立 `(room_id, cloud_sample_key)` 的部分唯一索引；同一物理房间使用两个本地备注时，
   两个本地 roomId 都能得到曲线，同时每个 roomId 内仍保持幂等；
3. 旧本地读数全部保持 `NULL`，不重写历史；
4. `recharges` 和 `recharge_attempts` 表完全不变。

回滚服务器时可先关闭 `PUBLIC_HISTORY_COLLECTOR_ENABLED` 再部署旧代码，公共历史数据库保留
且不会影响原统计服务。Android 数据库为追加字段，旧版 SQLite 会忽略该字段并继续读取原列。

## 公共读取接口

`GET /api/v1/public-history`

参数：

- `roomCode`：必填，精确 15 位房间码；
- `sinceMillis` / `untilMillis`：可选，Unix 毫秒；服务端最多返回最近 30 天；
- `cursor`：可选，上页返回的整数游标；
- `limit`：可选，1–500，默认 200。

响应包含 `dataVersion`、`serverTime`、`timezone`、`records`、`nextCursor` 和 `hasMore`。
成功记录至少包含房间及楼栋/楼层信息、余额、电费、查询时间、结果和来源。失败记录只暴露
稳定错误分类，不返回 Cookie、服务器路径、第三方原始响应或任何认证信息。大于 512 字节的
JSON 在客户端声明 `Accept-Encoding: gzip` 时使用 gzip。
公共接口按来源 IP 在内存中限制为每分钟 120 次；IP 不写入数据库，限速也不影响更新、
下载、心跳或后台页面。

示例（域名使用部署环境自己的值）：

```text
GET https://electricity.example.com/api/v1/public-history
    ?roomCode=001001001001001
    &sinceMillis=1785427200000
    &limit=500
```

## 变化事件

余额与上一条成功样本不同时创建事件，并保存前后查询时间、余额和变化量。后台直接使用
`11:00–12:00` 一类采集区间表达变化所在时段，不把后一次查询时间描述为平台准确时间。

- 下降：`用电消耗`
- 上涨：`充值`
- 一小时下降达到 100 度：`待确认`

正向变化分类用于公共余额趋势展示，不会写入或覆盖用户手机里的官方充值记录。旧数据库
启动时会把所有正向事件分类迁移为“充值”，原始前后余额、变化量和房间关联保持不变。

开发者后台把 08:00～20:00 映射为第 1～13 轮，并支持最近变化事件按时间、楼栋或
电量变化绝对值排序。金额排序分为“充值金额由高到低（仅充值）”与“消耗金额由高到低
（仅用电消耗）”，不会把方向相反的两类事件按绝对值混排。所有排序字段使用服务端
白名单，查询参数不会直接进入 SQL。

## 部署配置

敏感值只放在服务器的 EnvironmentFile，不提交到 Git：

```ini
PUBLIC_HISTORY_DATABASE_PATH=/var/lib/jiangli-electricity/public_history.sqlite3
PUBLIC_HISTORY_COLLECTOR_ENABLED=true
XIAOFUBAO_SHIRO_JID=由部署者填写
COLLECTOR_REQUEST_INTERVAL_SECONDS=0.25
COLLECTOR_REQUEST_TIMEOUT_SECONDS=12
```

数据目录必须只允许服务账号写入。生产部署应同时让 Nginx 反向代理
`/api/v1/public-history` 和 `/admin/collector`，并为 JSON 开启 gzip。
