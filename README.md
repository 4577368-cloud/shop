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

**尚未实现（下一期）：**

- Shopify Product 同步 / 刊登业务
- Fulfillment 挂载
- 真实 Redis / PowerJob / 生产 MySQL 切换指南完善

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
2. 部署完成后打开：`https://<服务名>.onrender.com/api/plugin/health`
3. Shopify 密钥稍后在 Render → Environment 填写：
   - `TANG_PLUGIN_SHOPIFY_API_KEY`
   - `TANG_PLUGIN_SHOPIFY_API_SECRET`
   - `TANG_PLUGIN_SHOPIFY_REDIRECT_URI=https://<服务名>.onrender.com/api/plugin/shopify/auth/callback`
   - `TANG_PLUGIN_SHOPIFY_WEBHOOK_BASE_URL=https://<服务名>.onrender.com`

说明：免费实例会休眠；当前用内存 H2，重启后授权数据会清空（仅适合联调）。

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
