# tangbuy-plugin

TangBuy 的 **平台接入服务**（Shopify 等电商平台的插件式对接），不是整个产品唯一后端。

| 职责 | 栈 |
|------|-----|
| 主站、商家工作台、业务域 API | Node / Python（既有栈继续） |
| 平台 OAuth、订单/商品同步、Webhook 网关 | **本仓库 Java（`com.tang.plugin`）** |

本服务对外暴露接入能力（同步、Webhook、授权回调等），经内部模型与主站/工作台协作；不承接账号体系、工作台 UI、主业务交易闭环等产品主后端职责。

包名与 `docs/` 对齐：`com.tang.plugin`。

## 当前范围（骨架）

已具备：

- Spring Boot 3.3 工程入口
- `PluginType`（含 `SHOPIFY`）
- `CustomException`、`@Slf4j` + `@Resource` 约定
- `RedisManager.lockAround`（本地锁 stub，可换 Redisson）
- `TxManger.run` + 窄事务模板（无 DB 时 NoOp TM）
- `ExternalOrder` / `ThirdPlatformProduct` / `ThirdPlatformSku`
- `ExternalOrderStrategy` + `BaseExternalOrderStrategy` + `ExternalOrderStrategyFactory`
- `BasePublishProductHandler` + `ProductPlatformHandlerHolder`
- `ExchangeRateComponent`、`WeightUnit`、`OuterUniqueComponent`、`RemoteResourceSdkClient` stub
- `shopOrderSyncExecutor` 线程池 Bean
- 健康检查：`GET /api/plugin/health`

**订单接入（一期已具备）：**

- `ShopifyGraphqlClient` / `ShopifyEnabledShopProvider`
- `ShopifyOrderComponent`（`updated_at` + cursor 分页）
- `ShopifyExternalOrderAdapter` / `ShopifyOrderStrategyImpl` / `ShopifyOrderPollingTask`
- 配置：`tang.plugin.shopify.*`（测试店可真网冒烟）

**Auth + Webhook（二期已具备）：**

- OAuth install/callback + JDBC `shopify_store_auth`
- Webhook gateway（raw-body HMAC、orders create/updated 回源、app/uninstalled）
- Provider 默认读 ACTIVE 授权表；yml `test-shops` 仅本地兜底

**采购链路（Procurement，已具备）：** 见下方 [采购链路](#采购链路procurement) 章节。

**尚未实现（下一期）：**

- Fulfillment 挂载
- 真实供应商下单 / 采购执行（当前仅 execution stub 占位）
- 真实 Redis / PowerJob / 生产 MySQL 切换指南完善

## 采购链路（Procurement）

tangbuy-plugin 只承担平台接入侧的采购**任务交接与消费接入**，不做真实采购执行、供应商下单、物流回传。

### 能力清单

- **Procurement Task Creation P1** — 从 BOUND 订单行显式生成采购任务（outbox 交接记录），幂等 by `(shop_name, line_id)`。
- **Procurement Outbox Delivery P1** — outbox 交付：`pull` 拉取待交付任务（只读 + 观测标记），`ack` 交付确认（ack-based at-least-once）。
- **Procurement Consumer Integration P1** — 消费接入：主站/采购系统登记 `received` / `accepted` 回执；`accept` 为业务化 ack 入口，驱动 outbox 交付。
- **Procurement Consumer Operations P1** — 运营/审计（全只读）：按 taskId / lineId / shopName 查询链路全貌、按状态列异常任务、店铺维度总览。
- **Procurement Execution Stub P1** — 已受理后的最小执行占位层，仅记录 `PENDING_EXECUTION` / `COMPLETED_STUB`，**不做真实采购执行**。
- **Procurement Ops Chain Execution View P1** — 只读增强：把 execution 占位并入 ops 链路视图，使链路从 `task → outbox → consumer receipt` 扩展为 `task → outbox → consumer receipt → execution`。不新增写接口、不改状态机，复用既有 `/ops` 端点。

### 状态语义

四个状态相互正交，仅通过 `accept → ack` 单向衔接，互不覆盖：

| 状态字段 | 载体 | 取值 | 含义 |
|---|---|---|---|
| `task_status` | procurement_task | `PENDING` / `CANCELLED` | 任务业务态 |
| `delivery_status` | procurement_task | `PENDING_DELIVERY` / `DELIVERED` | outbox 交付事实（唯一交付真相，仅 `ack` 可置 DELIVERED） |
| `consumption_status` | procurement_consumption | `RECEIVED` / `ACCEPTED` | 消费方回执台账（`accept` 时联动 ack） |
| `execution_status` | procurement_execution | `PENDING_EXECUTION` / `COMPLETED_STUB` | 执行占位态（下游新维度，非真实执行） |

> `execution complete` **不以 task 未 CANCELLED 为前置**，二者语义正交：`task_status=CANCELLED` 与 `execution_status=COMPLETED_STUB` 是可解释组合，由只读查询 / ops 视图解释，写路径不做业务态裁决。

### 关键接口分组

- **Outbox 交付** `/api/plugin/procurement/outbox`：`POST /pull`、`POST /ack`、`POST /ack-by-task`
- **消费接入** `/api/plugin/procurement/consumer`：`POST /receive`、`POST /accept`、`GET /by-task`、`GET /by-status`
- **运营审计（只读）** `/api/plugin/procurement/ops`：`GET /chain/by-task`、`GET /chain/by-line`、`GET /chain/by-shop`、`GET /anomalies`、`GET /summary`
- **执行占位** `/api/plugin/procurement/execution`：`POST /create`、`POST /complete`、`GET /by-task`、`GET /by-status`

### 链路视图与异常码

`ProcurementChainView` 以 task 为锚点装配四段：task 快照 + delivery 快照 + consumer receipts + **execution 占位快照**。execution 为追加只读层，不改上层 receipts / anomaly 语义：

- `execution` — execution 精简只读快照（`ProcurementExecutionView`，仅 `taskId / executionStatus / consumerId / note / createdAt / completedAt`，不暴露 `id` / `del_flag`）；无占位时为 `null`。
- `hasExecution` — 是否存在执行占位，布尔判断锚点。
- `executionStatus` — 便捷字段，无占位时为 `null`。

> `COMPLETED_STUB` 仅为**执行占位标记，不代表任何真实供应商下单 / 采购执行**。

运行时派生异常码（不落库，随查询计算；默认返回 WARN + ERROR，`includeInfo=true` 才含 INFO）：

| 异常码 | severity | 触发条件 |
|---|---|---|
| `STALE_PENDING_UNPULLED` | WARN | PENDING + PENDING_DELIVERY，从未拉取且超 stale 阈值 |
| `PULLED_NOT_DELIVERED` | WARN | 已拉取（attempts≥1）但久未交付 |
| `RECEIVED_NOT_ACCEPTED` | WARN | 有 RECEIVED 无 ACCEPTED，仍 PENDING_DELIVERY |
| `DELIVERED_WITHOUT_ACCEPT` | INFO | 直连 outbox ack 交付，无消费方 accept 回执 |
| `ACCEPTED_NOT_DELIVERED` | ERROR | 有 ACCEPTED 回执但 task 非 DELIVERED（台账/交付漂移） |
| `CANCELLED_WITH_RECEIPTS` | INFO | 任务已 CANCELLED 但存在消费回执 |
| `MULTI_CONSUMER_ACCEPTED` | INFO | 多个消费方受理同一任务（P1 无 claim） |
| `EXECUTION_COMPLETED_ON_CANCELLED` | INFO | `execution_status=COMPLETED_STUB` 且 `task_status=CANCELLED`（正交可解释组合，非报错） |
| `EXECUTION_PENDING_STALE` | WARN | `execution_status=PENDING_EXECUTION` 且 execution 自身 `created_at` 超 stale 阈值 |

> health（`GET /api/plugin/health`）不为本增强新增独立位：`procurementConsumerOps` 已覆盖 ops 链路视图（含 execution chain view 只读增强），避免 health 位膨胀。

### 幂等原则

- 幂等键：任务 `(shop_name, line_id)`；消费回执 `(task_id, consumer_id)`；执行占位 `task_id`（一任务一占位）。
- `delivered_at` / `received_at` / `accepted_at` / `completed_at` 均**仅首次写入，不刷新**。
- 重复 `ack` / `receive` / `accept` / `create` / `complete` 均为幂等成功（返回 `ALREADY_*`）。
- `accept` 全流程单事务：`ensureReceipt + markAccepted + ackByTaskId`，ack 失败整体回滚。
- ops 层全只读，不写库、不改任何状态；execution 写路径不回写 task/consumption。

### 本期不做

供应商下单、自动采购执行、比价选源、物流回传、claim/lease/超时回收/重投/requeue、工作台 UI、执行失败/补偿编排。

### 持久化与鉴权（Persistence & Hardening P1）

**持久化**：支持三档运行环境，语义完全一致：

| 环境 | Profile | 数据源 |
|---|---|---|
| 本地默认 | （无） | 文件 H2 `./data/tangbuy-plugin`（现状不变） |
| 测试 | `test` | 内存 H2（现状不变） |
| 生产 / Render | `prod` | **PostgreSQL（持久）** |

- `schema.sql` 为跨库可移植 DDL（H2 + PostgreSQL 通用，`GENERATED BY DEFAULT AS IDENTITY` + `IF NOT EXISTS` 幂等建表），三档共用同一份。
- 切库对 repository/service 透明；插入统一用 `new String[]{"id"}` 回取生成主键（兼容 PostgreSQL）。

**最小鉴权**：`prod` 下对 `/api/plugin/procurement/**` 强制校验请求头 `X-Internal-Token`（值 = `TANG_PLUGIN_INTERNAL_TOKEN`），不符返回 401。

- 放行：`/api/plugin/health`、`/api/plugin/shopify/auth/**`（OAuth）、`/api/plugin/shopify/webhook/**`（HMAC）。
- 开关：`tang.plugin.security.internal-token.enabled`，本地/测试默认关闭，`prod` 默认开启。
- token 仅从环境变量注入，不入库、不打日志。

## 本地运行

需要 **JDK 17+** 与 Maven：

```bash
cd /Users/panda/Documents/shopify/tangbuy-plugin
mvn spring-boot:run
```

健康检查：

```bash
curl http://localhost:8088/api/plugin/health
```

## 部署到 Render（GitHub）

本目录已含 `Dockerfile`、`render.yaml`。建议把 **`tangbuy-plugin` 单独作为 GitHub 仓库根目录**推送（不要把整个前端 monorepo 当根）。

### 1. 创建 GitHub 仓库并推送

在 `tangbuy-plugin` 目录：

```bash
cd /Users/panda/Documents/shopify/tangbuy-plugin
git init
git add .
git commit -m "Initial tangbuy-plugin for Render"
# 在 GitHub 新建空仓库后：
git remote add origin git@github.com:<你的账号>/tangbuy-plugin.git
git branch -M main
git push -u origin main
```

### 2. 在 Render 创建服务

1. [Render Dashboard](https://dashboard.render.com) → **New** → **Blueprint**（用 `render.yaml`）或 **Web Service**（选该仓库，Runtime = Docker）
2. 部署完成后打开：`https://<服务名>.onrender.com/api/plugin/health`（应显示 `persistenceStatus=UP`、`persistence=PostgreSQL`）
3. `render.yaml` 已声明托管 PostgreSQL（`tangbuy-db`）并注入数据源；`SPRING_PROFILES_ACTIVE=prod` 自动启用持久库 + 鉴权。
4. 在 Render → Environment 填写密钥（`sync: false` 的项）：
   - `TANG_PLUGIN_INTERNAL_TOKEN`（procurement 内部接口的共享 token，强随机串）
   - `TANG_PLUGIN_SHOPIFY_API_KEY`
   - `TANG_PLUGIN_SHOPIFY_API_SECRET`
   - `TANG_PLUGIN_SHOPIFY_REDIRECT_URI=https://<服务名>.onrender.com/api/plugin/shopify/auth/callback`
   - `TANG_PLUGIN_SHOPIFY_WEBHOOK_BASE_URL=https://<服务名>.onrender.com`

说明：免费实例会休眠；数据现由持久化 PostgreSQL 存储（不再随重启清空）。免费 PostgreSQL 有容量/时限，生产前请评估升档。

## 架构红线（与 docs 一致）

1. 只用 `@Resource`，不用 `@Autowired`
2. Strategy / Handler **禁止**直接发 HTTP/GraphQL → 下沉 `*Component`
3. 第三方原始模型经 `*Adapter` 转内部实体
4. 平台分发走 Factory / Holder，禁止 `if (shopType == SHOPIFY)`
5. JSON 用 fastjson2；时间用 `Instant`

## 建议下一刀

- Shopify Product 同步 / 刊登业务
- Fulfillment 挂载
- 生产库（MySQL）与 Redisson
