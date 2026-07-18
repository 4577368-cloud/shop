# Procurement E2E — 部署后真实 HTTP 联调执行清单

对已封板的 procurement 全链路（task → outbox → consumer receipt → execution）做部署后真实 HTTP 联调。
**只调用既有接口，不新增接口、不新增语义。** 所有异常码为运行时派生（不落库）。

> 与集成测试模板一一对应：主流程 1→9、9 个异常场景、3 条黄金样本。stale 类异常通过 `staleMinutes=1` + 等待 ≥70s 触发，无需改代码。

> **鉴权（Persistence & Hardening P1 起生效）**：`prod` 环境下 `/api/plugin/procurement/**` 需带请求头 `X-Internal-Token: <TANG_PLUGIN_INTERNAL_TOKEN>`，否则返回 401。
>
> 本文鉴权书写规则（统一执行）：
> - **受保护端点** `/api/plugin/procurement/**`：**每条 curl 一律显式加 `-H "$AUTH"`**（`$AUTH` 见 §0 定义），不省略、不依赖上下文继承。
> - **公开端点（放行，显式不带 token）**：`/api/plugin/health`、`/api/plugin/shopify/auth/**`、`/api/plugin/shopify/webhook/**`。这些 curl **显式不加** `-H "$AUTH"`，并在旁标注「公开端点」。

---

## 0. 环境准备

```bash
# 部署地址与联调专用店铺（用独立 shop，避免污染既有数据）
export BASE="https://shop-x2mw.onrender.com"
export SHOP="e2e-shop-$(date +%m%d%H%M)"
export CONSUMER="main-platform"

# 内部鉴权 token（= Render 环境变量 TANG_PLUGIN_INTERNAL_TOKEN）
export TOKEN="<你的 internal token>"
export AUTH="X-Internal-Token: $TOKEN"

# 建议安装 jq 以解析响应并提取 taskId
jq --version
```

联调前基线：

```bash
# 公开端点（放行，显式不带 token）——应显示持久化已启用
curl -s "$BASE/api/plugin/health" | jq '{status, persistence, persistenceStatus, procurementConsumerOps, procurementExecutionStub}'
# procurement 接口（受保护）——必须带 token
curl -s -H "$AUTH" "$BASE/api/plugin/procurement/ops/summary?shopName=$SHOP" | jq .
```

> 预期：`status=UP`、`persistenceStatus=UP`、`persistence=PostgreSQL`；`procurementConsumerOps=AVAILABLE`（已覆盖 ops 链路视图含 execution 只读增强，**不新增独立 health 位**）。summary 各计数为空/0。
> 鉴权自检：不带 `-H "$AUTH"` 调用任一 `/procurement/*` 应返回 **401**；`/health` 不带 token 仍 `200`。

---

## 1. 主流程执行清单（步骤 1→9，使用一条独立 line）

```bash
export L_HAPPY="L-happy-01"
```

**步骤 1 — 生成任务**（预期 `taskStatus=PENDING` / `deliveryStatus=PENDING_DELIVERY`）

```bash
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/task/create-from-line?shopName=$SHOP&lineId=$L_HAPPY" | jq .
# 提取 taskId 供后续步骤使用
export TID=$(curl -s -H "$AUTH" "$BASE/api/plugin/procurement/ops/chain/by-line?shopName=$SHOP&lineId=$L_HAPPY" | jq -r '.taskId')
echo "taskId=$TID"
```

**步骤 2 — 拉取**（预期 `delivery_attempts+1`、`last_pulled_at` 更新、`delivery_status` 不变）

```bash
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/outbox/pull?shopName=$SHOP&limit=10" | jq .
curl -s -H "$AUTH" "$BASE/api/plugin/procurement/ops/chain/by-task?shopName=$SHOP&taskId=$TID" \
  | jq '{taskStatus, deliveryStatus, deliveryAttempts, lastPulledAt}'
```

**步骤 3 — 接收**（预期 `RECEIVED`、`received_at` 首写）

```bash
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/consumer/receive?shopName=$SHOP&taskId=$TID&consumerId=$CONSUMER&consumerRef=ref-happy" | jq .
```

**步骤 4 — 中途快照**（预期出现 `RECEIVED_NOT_ACCEPTED`）

```bash
curl -s -H "$AUTH" "$BASE/api/plugin/procurement/ops/chain/by-task?shopName=$SHOP&taskId=$TID" \
  | jq '{deliveryStatus, hasAcceptedReceipt, anomalies}'
```

**步骤 5 — 受理**（预期 `ACCEPTED` + 联动 `DELIVERED`、`delivered_at` 首写）

```bash
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/consumer/accept?shopName=$SHOP&taskId=$TID&consumerId=$CONSUMER&consumerRef=ref-happy" | jq .
```

**步骤 6 — 建执行占位**（预期 `PENDING_EXECUTION`、`created_at` 首写）

```bash
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/execution/create?shopName=$SHOP&taskId=$TID&consumerId=$CONSUMER&note=stub" | jq .
```

**步骤 7 — 完成执行占位**（预期 `COMPLETED_STUB`、`completed_at` 首写）

```bash
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/execution/complete?shopName=$SHOP&taskId=$TID&note=done" | jq .
```

**步骤 8 — 终态快照**（预期四段闭合、`hasExecution=true`、`anomalies=[]`）

```bash
curl -s -H "$AUTH" "$BASE/api/plugin/procurement/ops/chain/by-task?shopName=$SHOP&taskId=$TID" \
  | jq '{taskStatus, deliveryStatus, hasAcceptedReceipt, hasExecution, executionStatus, execution, anomalies}'
```

**步骤 9 — 总览对账**（预期四类 counts 与逐条一致，该链 anomalyCounts 全 0）

```bash
curl -s -H "$AUTH" "$BASE/api/plugin/procurement/ops/summary?shopName=$SHOP" | jq .
```

| 步骤 | 断言 | 通过 |
|---|---|---|
| 1 | PENDING / PENDING_DELIVERY / attempts=0 | ☐ |
| 2 | attempts=1、lastPulledAt 有值、delivery 不变 | ☐ |
| 3 | RECEIVED、receivedAt 首写 | ☐ |
| 4 | anomalies 含 RECEIVED_NOT_ACCEPTED | ☐ |
| 5 | accept=ACCEPTED、delivery=DELIVERED、deliveredAt 首写 | ☐ |
| 6 | PENDING_EXECUTION、createdAt 首写 | ☐ |
| 7 | COMPLETED_STUB、completedAt 首写 | ☐ |
| 8 | 四段闭合、hasExecution=true、anomalies=[] | ☐ |
| 9 | counts 一致、该链 anomalyCounts=0 | ☐ |

---

## 2. 异常场景执行清单（每条独立 line/task，避免多异常叠加）

默认 `anomalies` 只返回 WARN + ERROR；INFO 需 `includeInfo=true`。stale 类用 `staleMinutes=1` + 等待 ≥70s。

### 2.1 `STALE_PENDING_UNPULLED` / WARN

```bash
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/task/create-from-line?shopName=$SHOP&lineId=L-stale-unpulled" | jq -r '.taskStatus'
sleep 75
curl -s -H "$AUTH" "$BASE/api/plugin/procurement/ops/anomalies?shopName=$SHOP&type=STALE_PENDING_UNPULLED&staleMinutes=1" | jq .
```

### 2.2 `PULLED_NOT_DELIVERED` / WARN

```bash
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/task/create-from-line?shopName=$SHOP&lineId=L-pulled-nd" >/dev/null
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/outbox/pull?shopName=$SHOP&limit=50" >/dev/null
sleep 75
curl -s -H "$AUTH" "$BASE/api/plugin/procurement/ops/anomalies?shopName=$SHOP&type=PULLED_NOT_DELIVERED&staleMinutes=1" | jq .
```

### 2.3 `RECEIVED_NOT_ACCEPTED` / WARN

```bash
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/task/create-from-line?shopName=$SHOP&lineId=L-recv-na" >/dev/null
RID=$(curl -s -H "$AUTH" "$BASE/api/plugin/procurement/ops/chain/by-line?shopName=$SHOP&lineId=L-recv-na" | jq -r '.taskId')
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/consumer/receive?shopName=$SHOP&taskId=$RID&consumerId=$CONSUMER" >/dev/null
curl -s -H "$AUTH" "$BASE/api/plugin/procurement/ops/anomalies?shopName=$SHOP&type=RECEIVED_NOT_ACCEPTED" | jq .
```

### 2.4 `DELIVERED_WITHOUT_ACCEPT` / INFO

```bash
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/task/create-from-line?shopName=$SHOP&lineId=L-direct-ack" >/dev/null
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/outbox/ack?shopName=$SHOP&lineId=L-direct-ack" | jq .
curl -s -H "$AUTH" "$BASE/api/plugin/procurement/ops/anomalies?shopName=$SHOP&type=DELIVERED_WITHOUT_ACCEPT&includeInfo=true" | jq .
```

### 2.5 `ACCEPTED_NOT_DELIVERED` / ERROR — 纯 HTTP 无法自然构造

> 该码是 ACCEPTED 回执存在但 task 非 DELIVERED 的**漂移态**。既有 `accept` 单事务内联动 ack → DELIVERED，正常 HTTP 路径**不可能产生此漂移**。仅可通过 DB 层故障注入构造，**不属于纯 HTTP 联调范围**；已由集成测试 `ProcurementOpsQueryServiceTest#anomaliesSurfaceAcceptedNotDeliveredAsError` 覆盖。现场只需确认：正常链路下该码计数恒为 0。

```bash
curl -s -H "$AUTH" "$BASE/api/plugin/procurement/ops/summary?shopName=$SHOP" | jq '.anomalyCounts.ACCEPTED_NOT_DELIVERED'   # 预期 0
```

### 2.6 `CANCELLED_WITH_RECEIPTS` / INFO

```bash
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/task/create-from-line?shopName=$SHOP&lineId=L-cancel-rcpt" >/dev/null
CID=$(curl -s -H "$AUTH" "$BASE/api/plugin/procurement/ops/chain/by-line?shopName=$SHOP&lineId=L-cancel-rcpt" | jq -r '.taskId')
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/consumer/receive?shopName=$SHOP&taskId=$CID&consumerId=$CONSUMER" >/dev/null
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/task/cancel?shopName=$SHOP&lineId=L-cancel-rcpt" | jq .
curl -s -H "$AUTH" "$BASE/api/plugin/procurement/ops/anomalies?shopName=$SHOP&type=CANCELLED_WITH_RECEIPTS&includeInfo=true" | jq .
```

### 2.7 `MULTI_CONSUMER_ACCEPTED` / INFO

```bash
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/task/create-from-line?shopName=$SHOP&lineId=L-multi" >/dev/null
MID=$(curl -s -H "$AUTH" "$BASE/api/plugin/procurement/ops/chain/by-line?shopName=$SHOP&lineId=L-multi" | jq -r '.taskId')
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/consumer/accept?shopName=$SHOP&taskId=$MID&consumerId=consumer-a" >/dev/null
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/consumer/accept?shopName=$SHOP&taskId=$MID&consumerId=consumer-b" >/dev/null
curl -s -H "$AUTH" "$BASE/api/plugin/procurement/ops/anomalies?shopName=$SHOP&type=MULTI_CONSUMER_ACCEPTED&includeInfo=true" | jq .
```

### 2.8 `EXECUTION_COMPLETED_ON_CANCELLED` / INFO

```bash
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/task/create-from-line?shopName=$SHOP&lineId=L-exec-cancel" >/dev/null
EID=$(curl -s -H "$AUTH" "$BASE/api/plugin/procurement/ops/chain/by-line?shopName=$SHOP&lineId=L-exec-cancel" | jq -r '.taskId')
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/consumer/accept?shopName=$SHOP&taskId=$EID&consumerId=$CONSUMER" >/dev/null
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/execution/create?shopName=$SHOP&taskId=$EID&consumerId=$CONSUMER" >/dev/null
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/execution/complete?shopName=$SHOP&taskId=$EID" >/dev/null
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/task/cancel?shopName=$SHOP&lineId=L-exec-cancel" >/dev/null
curl -s -H "$AUTH" "$BASE/api/plugin/procurement/ops/anomalies?shopName=$SHOP&type=EXECUTION_COMPLETED_ON_CANCELLED&includeInfo=true" | jq .
```

### 2.9 `EXECUTION_PENDING_STALE` / WARN

```bash
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/task/create-from-line?shopName=$SHOP&lineId=L-exec-stale" >/dev/null
SID=$(curl -s -H "$AUTH" "$BASE/api/plugin/procurement/ops/chain/by-line?shopName=$SHOP&lineId=L-exec-stale" | jq -r '.taskId')
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/consumer/accept?shopName=$SHOP&taskId=$SID&consumerId=$CONSUMER" >/dev/null
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/execution/create?shopName=$SHOP&taskId=$SID&consumerId=$CONSUMER" >/dev/null
sleep 75
curl -s -H "$AUTH" "$BASE/api/plugin/procurement/ops/anomalies?shopName=$SHOP&type=EXECUTION_PENDING_STALE&staleMinutes=1" | jq .
```

| 场景 | 预期码 / severity | 命中 taskId | 通过 |
|---|---|---|---|
| 2.1 | STALE_PENDING_UNPULLED / WARN | | ☐ |
| 2.2 | PULLED_NOT_DELIVERED / WARN | | ☐ |
| 2.3 | RECEIVED_NOT_ACCEPTED / WARN | | ☐ |
| 2.4 | DELIVERED_WITHOUT_ACCEPT / INFO | | ☐ |
| 2.5 | ACCEPTED_NOT_DELIVERED / ERROR（HTTP 不可构造，验计数=0） | — | ☐ |
| 2.6 | CANCELLED_WITH_RECEIPTS / INFO | | ☐ |
| 2.7 | MULTI_CONSUMER_ACCEPTED / INFO | | ☐ |
| 2.8 | EXECUTION_COMPLETED_ON_CANCELLED / INFO | | ☐ |
| 2.9 | EXECUTION_PENDING_STALE / WARN | | ☐ |

**幂等/拒绝边界抽验**

```bash
# 重复 ack → ALREADY_DELIVERED，delivered_at 不刷新
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/outbox/ack?shopName=$SHOP&lineId=$L_HAPPY" | jq '.outcome'
# 重复 complete → ALREADY_COMPLETED
curl -s -H "$AUTH" -X POST "$BASE/api/plugin/procurement/execution/complete?shopName=$SHOP&taskId=$TID" | jq '.outcome'
# CANCELLED 任务 create execution → 拒绝（4xx / 明确错误体）
curl -s -H "$AUTH" -o /dev/null -w "%{http_code}\n" -X POST "$BASE/api/plugin/procurement/execution/create?shopName=$SHOP&taskId=$EID"
# 跨店查询 → 拒绝
curl -s -H "$AUTH" -o /dev/null -w "%{http_code}\n" "$BASE/api/plugin/procurement/ops/chain/by-task?shopName=other-shop&taskId=$TID"
```

---

## 3. 黄金样本记录位（联调后填写，供回归复用）

| 样本 | 场景 | lineId | taskId | 关键断言 | 记录时间 |
|---|---|---|---|---|---|
| Happy Path | 主流程 1→9 全绿 | `L-happy-01` | ______ | 终态四段闭合、anomalies=[] | ______ |
| WARN 样本 | `EXECUTION_PENDING_STALE` | `L-exec-stale` | ______ | staleMinutes=1 命中、severity=WARN | ______ |
| INFO 样本 | `EXECUTION_COMPLETED_ON_CANCELLED` | `L-exec-cancel` | ______ | includeInfo=true 命中、severity=INFO | ______ |

---

## 4. 部署环境注意事项

1. **真实服务实例可达**：`GET /api/plugin/health` 返回 `status=UP`；若为 Render 免费实例，先发一次预热请求消除冷启动（首请求可能 30s+）。确认 `shopifyRedirectUri` 指向当前部署域名。
2. **建表确认（关键）**：`third_platform_procurement_task` / `_consumption` / `_execution` 三表必须已存在于运行库。
   - 文件型 H2 或生产 MySQL：`CREATE TABLE IF NOT EXISTS` **不会为旧库补列/补表**；升级实例需人工执行 `schema.sql` 中三张采购表 DDL 及唯一键/索引（execution `uk(task_id)`、consumption `uk(task_id, consumer_id)`、task `idx(shop_name, task_status, delivery_status)`）。
   - 验证：任一 `create-from-line` 成功且能被 `ops/chain/by-task` 读回，即表结构就绪。
3. **health 不新增位**：本增强复用 `procurementConsumerOps`（已覆盖 ops 链路视图含 execution 只读增强），不应出现新的独立 health 字段；若发现新增位则为回归偏差。
4. **联调数据隔离**：使用带时间戳的独立 `SHOP`，联调后可按 `shop_name` 清理，避免污染真实店铺统计。
5. **stale 阈值口径**：`staleMinutes` 非正数回落默认 60；联调用 `staleMinutes=1` + 等待 ≥70s 触发 stale 类。`EXECUTION_PENDING_STALE` 以 execution 自身 `created_at` 计算，勿与 task 时间戳混用。

---

## 5. 需要现场手工确认的 5 个点

1. **服务可达性 & 冷启动**：health `UP`，预热后响应正常，`shopifyRedirectUri` 为当前域名。
2. **建表已生效**：运行库三张采购表 + 唯一键/索引齐全（`create-from-line` → `ops/chain/by-task` 读回验证）。
3. **health 无新增位**：`procurementConsumerOps=AVAILABLE` 且未出现 execution-view 独立位。
4. **stale 触发口径**：`staleMinutes=1` + `sleep 75` 能稳定命中 2.1 / 2.2 / 2.9（受实例时钟与网络延迟影响，必要时延长等待）。
5. **正交组合可解释**：2.8 中 `task_status=CANCELLED` 与 `execution_status=COMPLETED_STUB` 并存被判为 INFO（非报错），`complete` 未因 CANCELLED 被拒。

---

## 6. 真实 HTTP 联调完成后的签收标准

- **主流程**：步骤 1→9 全部断言通过；终态 `ops/chain/by-task` 四段闭合（task + delivery + receipts + execution）、`hasExecution=true`、`anomalies=[]`。
- **异常场景**：2.1–2.4、2.6–2.9 各自命中预期码与 severity，且**互不叠加**（每条独立 line）；2.5 确认正常链路下计数恒为 0（漂移态由集成测试覆盖）。
- **幂等/拒绝**：重复 ack/complete 返回 `ALREADY_*` 且时间戳不刷新；CANCELLED create execution、跨店查询均被拒绝（4xx + 明确错误体）。
- **状态正交**：全程 `task_status` / `delivery_status` / `consumption_status` / `execution_status` 互不越权覆盖；`delivery_status` 仅由 `ack`（含 accept 联动）置 DELIVERED。
- **黄金样本登记**：第 3 节三条样本的真实 `taskId` 已回填，可作为后续回归基线。
- **环境项**：第 4 节 5 项与第 5 节 5 个手工确认点全部勾选。
- **数据卫生**：联调 `SHOP` 数据已按需清理或标记为联调专用，不影响真实店铺统计。

> 全部满足即判定 **Procurement E2E Validation P1 — 真实 HTTP 联调签收通过**。
