# Render → 阿里云 ECS 迁移指南

> 后端：Java 17 + Spring Boot 3.3.5 + Docker  
> 数据库：PostgreSQL（暂留 Render，后续再迁）  
> 服务器：阿里云 ECS 2核4G

---

## 迁移流程总览

```
┌─────────────────────────────────────────────────────┐
│ 1. Render Dashboard 获取数据库外部连接信息            │
│ 2. ECS 安装 Docker                                   │
│ 3. 上传后端代码到 ECS                                 │
│ 4. 配置 .env 环境变量                                │
│ 5. 构建并启动 Docker 容器                            │
│ 6. 阿里云安全组开放 8088 端口                        │
│ 7. 验证后端健康检查                                  │
│ 8. Vercel 更新 NEXT_PUBLIC_API_BASE                  │
│ 9. Shopify Partner 更新 OAuth 回调地址               │
│ 10.（可选）绑定域名 + Nginx 反向代理 + HTTPS          │
└─────────────────────────────────────────────────────┘
```

---

## 第一步：获取 Render PostgreSQL 外部连接

1. 登录 https://dashboard.render.com
2. 点击 `tangbuy-db` 数据库服务
3. 在 **Connect** / **Connections** 页面找到：
   - **External Host**: `dpg-xxxxxxxxxx.render.com`（注意不是 Internal Host）
   - **Port**: `5432`
   - **Database**: `tangbuy`
   - **User**: `tangbuy`
   - **Password**: 创建时设置的密码（如忘记可在 Dashboard 重置）

> ⚠️ Render 免费 PostgreSQL 允许外部连接，但并发连接数限制约 20 个。HikariCP 已配 `maximum-pool-size: 5`，足够使用。

---

## 第二步：ECS 安装 Docker

SSH 登录 ECS 后执行：

```bash
# 安装 Docker（阿里云镜像加速）
curl -fsSL https://get.docker.com | bash -s docker --mirror Aliyun

# 启动 Docker 并设置开机自启
systemctl enable docker
systemctl start docker

# 验证
docker --version
```

---

## 第三步：上传后端代码到 ECS

### 方式 A：从 GitHub 克隆（推荐）

如果你的 `tang-source-plugin` 已在 GitHub 仓库：

```bash
mkdir -p /opt/tang-source-plugin
cd /opt/tang-source-plugin
git clone <你的GitHub仓库地址> /tmp/tb-repo
# 将 tang-source-plugin 子目录内容复制过来
cp -r /tmp/tb-repo/tang-source-plugin/* /opt/tang-source-plugin/
cp -r /tmp/tb-repo/tang-source-plugin/.* /opt/tang-source-plugin/ 2>/dev/null || true
```

### 方式 B：从本地 scp 上传

在本地终端（非 ECS）执行：

```bash
# 打包后端代码（排除不需要的文件）
cd /Users/panda/Documents/shopify
tar --exclude='tang-source-plugin/target' \
    --exclude='tang-source-plugin/.git' \
    --exclude='tang-source-plugin/data' \
    --exclude='tang-source-plugin/deploy/.env' \
    -czf /tmp/tang-source-plugin.tar.gz tang-source-plugin/

# 上传到 ECS（替换为你的 ECS 公网 IP）
scp /tmp/tang-source-plugin.tar.gz root@你的ECS_IP:/opt/

# SSH 登录 ECS 解压
ssh root@你的ECS_IP
cd /opt && tar -xzf tang-source-plugin.tar.gz
mv tang-source-plugin tang-source-plugin-src
mkdir -p /opt/tang-source-plugin
cp -r /opt/tang-source-plugin-src/* /opt/tang-source-plugin/
```

---

## 第四步：配置环境变量

```bash
cd /opt/tang-source-plugin
cp deploy/.env.example .env
```

编辑 `.env` 文件，填入真实值：

```bash
vi .env
```

**必须修改的关键项：**

| 变量 | 说明 | 从哪里获取 |
|------|------|-----------|
| `DB_HOST` | Render 数据库外网地址 | Render Dashboard → tangbuy-db → Connect |
| `DB_PORT` | 数据库端口 | `5432` |
| `DB_NAME` | 数据库名 | `tangbuy` |
| `SPRING_DATASOURCE_USERNAME` | 数据库用户 | `tangbuy` |
| `SPRING_DATASOURCE_PASSWORD` | 数据库密码 | Render Dashboard 重置/查看 |
| `TANG_PLUGIN_JWT_SECRET` | JWT 签名密钥 | 与 Render 环境变量中相同（确保一致，否则旧 token 失效） |
| `TANG_PLUGIN_SHOPIFY_API_KEY` | Shopify API Key | Render Dashboard 或 Shopify Partner Dashboard |
| `TANG_PLUGIN_SHOPIFY_API_SECRET` | Shopify API Secret | 同上 |
| `TANG_PLUGIN_SHOPIFY_REDIRECT_URI` | OAuth 回调 | `http://你的ECS公网IP:8088/api/plugin/shopify/auth/callback` |
| `TANG_PLUGIN_SHOPIFY_WEBHOOK_BASE_URL` | Webhook 基址 | `http://你的ECS公网IP:8088` |
| `TANG_PLUGIN_PIPIADS_API_KEY` | pipispy API Key | Render Dashboard |
| `TANG_PLUGIN_INTERNAL_TOKEN` | 内部接口 token | Render Dashboard |
| `TANG_PLUGIN_TANGBUY_MALL_TOKEN` | Tangbuy 商城 token | Render Dashboard |
| `TANG_PLUGIN_PAYPAL_*` | PayPal 凭证 | Render Dashboard |

> 💡 **快捷方式**：在 Render Dashboard 的 Environment 页面可以直接看到所有 `sync: false` 变量的值，逐个复制过来即可。

---

## 第五步：构建并启动

```bash
cd /opt/tang-source-plugin
bash deploy/aliyun-deploy.sh
```

脚本会自动：
1. 构建 Docker 镜像（约 3-5 分钟）
2. 停止旧容器（如有）
3. 启动新容器
4. 等待 15 秒后健康检查
5. 重试 5 次直到成功

---

## 第六步：阿里云安全组开放端口

1. 登录 [阿里云控制台](https://ecs.console.aliyun.com)
2. 找到你的 ECS 实例 → **安全组** → **配置规则**
3. 添加入方向规则：
   - 端口范围：`8088/8088`
   - 授权对象：`0.0.0.0/0`
   - 协议类型：TCP

---

## 第七步：验证

在本地浏览器访问：

```
http://你的ECS公网IP:8088/api/plugin/health
```

应返回 HTTP 200 + 健康状态 JSON。

---

## 第八步：更新 Vercel 前端代理

1. 登录 [Vercel Dashboard](https://vercel.com/dashboard)
2. 找到你的项目 → **Settings** → **Environment Variables**
3. 修改 `NEXT_PUBLIC_API_BASE`：
   - 旧值：`https://shop-x2mw.onrender.com`
   - 新值：`http://你的ECS公网IP:8088`
4. **重新部署**（必须，因为 `NEXT_PUBLIC_*` 在构建时内联）：
   - Deployments → 最新部署 → `...` → **Redeploy**

> ⚠️ Vercel 的 next.config rewrites 会将 `/api/plugin/*` 代理到 `NEXT_PUBLIC_API_BASE`。改完后前端的 API 请求就会指向阿里云 ECS。

---

## 第九步：更新 Shopify OAuth 回调

1. 登录 [Shopify Partner Dashboard](https://partners.shopify.com)
2. Apps → 你的 App → **App setup**
3. 更新 **Allowed redirection URI(s)**：
   - 添加：`http://你的ECS公网IP:8088/api/plugin/shopify/auth/callback`
   - 保留旧的 Render 回调（过渡期兼容已授权店铺）

---

## 第十步（可选）：绑定域名 + HTTPS

当验证一切正常后，建议绑定域名：

### 10.1 域名解析

在阿里云 DNS 添加 A 记录：
```
api.tangbuy.com → 你的ECS公网IP
```

### 10.2 Nginx 反向代理

```bash
# 安装 Nginx
yum install -y nginx  # 或 apt install -y nginx

# 配置反向代理
cat > /etc/nginx/conf.d/tangbuy-api.conf << 'EOF'
server {
    listen 80;
    server_name api.tangbuy.com;

    client_max_body_size 50m;

    location / {
        proxy_pass http://127.0.0.1:8088;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_connect_timeout 60s;
        proxy_read_timeout 120s;
    }
}
EOF

systemctl enable nginx
systemctl restart nginx
```

### 10.3 HTTPS（Let's Encrypt 免费证书）

```bash
# 安装 certbot
yum install -y certbot python3-certbot-nginx  # 或 apt install -y certbot python3-certbot-nginx

# 申请证书
certbot --nginx -d api.tangbuy.com

# 自动续期（已内置 cron）
certbot renew --dry-run
```

### 10.4 证书到位后更新环境变量

修改 `/opt/tang-source-plugin/.env`：
```bash
TANG_PLUGIN_SHOPIFY_REDIRECT_URI=https://api.tangbuy.com/api/plugin/shopify/auth/callback
TANG_PLUGIN_SHOPIFY_WEBHOOK_BASE_URL=https://api.tangbuy.com
TANG_PLUGIN_COOKIE_SECURE=true
```

然后重启容器：
```bash
cd /opt/tang-source-plugin && bash deploy/aliyun-update.sh
```

### 10.5 更新 Vercel 和 Shopify

- Vercel `NEXT_PUBLIC_API_BASE` → `https://api.tangbuy.com`
- Shopify OAuth 回调 → `https://api.tangbuy.com/api/plugin/shopify/auth/callback`

---

## 后续代码更新

每次后端代码更新后，在 ECS 上执行：

```bash
cd /opt/tang-source-plugin
# 如果用 git 管理代码：
git pull
# 然后重新部署：
bash deploy/aliyun-update.sh
```

---

## 数据库迁移（后续）

当你准备好将 PostgreSQL 也从 Render 迁到阿里云时：

### 方案 A：阿里云 RDS PostgreSQL（推荐）
1. 在阿里云购买 RDS PostgreSQL 实例
2. 用 `pg_dump` 从 Render 导出数据：
   ```bash
   pg_dump "host=dpg-xxx.render.com port=5432 dbname=tangbuy user=tangbuy password=xxx" > backup.sql
   ```
3. 导入到 RDS：
   ```bash
   psql "host=RDS内网地址 port=5432 dbname=tangbuy user=tangbuy password=xxx" < backup.sql
   ```
4. 修改 `.env` 中的 DB_HOST/DB_PORT/DB_NAME/用户名/密码
5. 重启容器

### 方案 B：ECS 自建 PostgreSQL（Docker）
```bash
docker run -d \
  --name tangbuy-pg \
  --restart unless-stopped \
  -e POSTGRES_DB=tangbuy \
  -e POSTGRES_USER=tangbuy \
  -e POSTGRES_PASSWORD=你的密码 \
  -v /opt/pgdata:/var/lib/postgresql/data \
  -p 5432:5432 \
  postgres:16-alpine
```

---

## 常见问题

### Q: Render 免费 PostgreSQL 外部连接被拒绝？
A: Render 免费版确实允许外部连接，但需确认使用的是 **External Host**（不是 Internal Host）。Internal Host 只在 Render 内网可用。

### Q: 容器启动但健康检查失败？
A: 查看日志：`docker logs tang-source-plugin`。常见原因：
- 数据库密码错误 → 检查 .env 中 `SPRING_DATASOURCE_PASSWORD`
- JWT_SECRET 缺失 → 必须设置 ≥32 字符的密钥
- 端口被占用 → `netstat -tlnp | grep 8088` 检查

### Q: 前端跨域报错？
A: 检查 `.env` 中 `TANG_PLUGIN_CORS_ALLOWED_ORIGINS` 是否包含前端域名。

### Q: Cookie 无法携带？
A: IP+端口方式跨站，必须保持 `TANG_PLUGIN_COOKIE_SAMESITE=none` 和 `TANG_PLUGIN_COOKIE_SECURE=false`（HTTP 无证书时）。绑定域名+HTTPS 后改为 `secure=true`。

### Q: Render 休眠问题？
A: Render 免费版 Web Service 15分钟无请求会休眠。迁移到 ECS 后此问题自动消失。但数据库（PostgreSQL）在 Render 免费版不会休眠，只是连接数有限。
