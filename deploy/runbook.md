# 部署 Runbook（AI 与人共用剧本 · day23 架构）

> **AI 执行守则**：按步骤顺序执行；所有脚本幂等可重跑；
> 命中【人工确认】清单的操作必须先停下来向用户复述命令并等待批准；
> 禁止把 .env 真实值回显到日志/对话/截图。

## 0. 前置（服务器：阿里 2C2G，Docker 已装）

```bash
docker --version && docker compose version   # 需 compose v2
free -h                                       # 确认可用内存 ≥1.5G
```

## 1. 拉代码 + 填环境

```bash
git clone <repo> /opt/legal-assistant && cd /opt/legal-assistant
# 仅当 .env 不存在时从模板生成（已存在则保留真实密钥，避免重跑被覆盖；
# 如需重置为模板：先手动删除 deploy/.env 再执行本步）
if [ ! -f deploy/.env ]; then cp deploy/env.template deploy/.env; fi
# 编辑 deploy/.env：全部 CHANGE_ME 换真实值（密钥清单见 env.template 注释）
```

## 2. 起栈（首次构建约 5-10 分钟，Maven/npm 层缓存后 <2 分钟）

```bash
cd deploy && docker compose up -d --build
docker compose ps        # 四服务全 healthy/running
docker compose logs -f backend   # 见 "Started BackendApplication" 后 Ctrl+C
```

## 3. 数据迁移（二选一，见 migrate.md）

- 路线 A（推荐）：本地 pg_dump → scp → `docker exec -i lab-pg pg_restore ...`
- 路线 B：db/init.sql（单文件：结构+全量法条，幂等）+ 管理端重同步向量

## 4. 首发管理员（二选一）

**通道 A（推荐）：BOOTSTRAP_ADMIN——不预置任何口令**

1. 浏览器打开站点 → 注册页注册你的账号（手机号+验证码；本地体验可先把 sms-provider 切 mock）；
2. 编辑 deploy/.env：`BOOTSTRAP_ADMIN=你的用户名` → `docker compose up -d backend`（重启生效）；
3. 后端日志见 `【bootstrap-admin】已提权: username=... → ADMIN`；
4. 登录 → 设置 → 可见"向量库同步"等管理项 = 提权成功；
5. **首发完成后从 .env 删除 BOOTSTRAP_ADMIN 并重启**（幂等无害但违反最小权限）。

**通道 B（快捷体验）：预设管理员种子——有默认口令，仅限本地/内网**

```bash
# 预置 admin / 123456（BCrypt 已验证）＋示例手机号；幂等可重跑
docker exec -i lab-pg psql -U postgres -d lab_legal_kb < db/seed-admin.sql
```

⚠️ 公网部署禁用默认口令：登录后立即改密，或不走此通道。改密配方（免短信）：

```bash
java -cp <spring-security-crypto.jar> scripts/GenBcrypt.java encode <新密码>   # 输出哈希
psql -U postgres -d lab_legal_kb -c "UPDATE app_user SET password_hash='<哈希>' WHERE username='admin';"
```

## 5. 验收探针

```bash
bash deploy/probes.sh http://127.0.0.1
# 6 项全 PASS 才算部署成功；任一 FAIL 按提示排障后重跑（探针可重复执行）
```

重点探针含义：
- `stats-json`：API 返回 JSON 首字符 `{` —— 失败=nginx 白名单漏配（HTML 污染坑）；
- `sse-streaming`：20s 内见 `event:` —— 失败=proxy_buffering 未关或后端未活；
- `admin-auth-guard`：未登录访问管理接口必须 401/403。

## 5.5 日常发布（git 化，一条命令）

服务器 `/opt/legal-assistant` 已初始化为 git 工作副本（origin = GitHub 仓库）。
首次配置 Deploy Key（只读即可，**不要**用 PAT，也**不要**把 token 写进 remote URL）：

```bash
# 服务器上生成专用密钥（已有 /root/.ssh/github_deploy 则跳过）
ssh-keygen -t ed25519 -N "" -C "legal-assistant-deploy@$(hostname)" -f /root/.ssh/github_deploy
cat /root/.ssh/github_deploy.pub     # 把这一行加到 GitHub 仓库 Settings → Deploy keys（勾选只读）
# 本机 ~/.ssh/config 也可加 Host github.com + IdentityFile 方便调试
```

之后每次发布（服务器上执行）：

```bash
bash deploy/deploy.sh
# = git pull --ff-only → 数据库迁移（幂等）→ docker compose up -d --build → 探针
# 变体：--no-db 只更新代码与镜像；--no-pull 用工作区当前提交发布（应急）
```

**拉取前置条件**：`git fetch origin` 能通（Deploy Key 已加）。拿不到远端时脚本会告警并
退回"用当前提交发布"，不会静默失败。首次把工作副本接到 origin 后，如上游分支尚未建立：

```bash
git fetch origin && git branch --set-upstream-to=origin/main main
```

> ⚠ **严禁在本目录执行 `git clean -xfd`**：`deploy/.env` 是未跟踪文件（真实密钥），
> 会被一并删除。同理不要用 `git clean` 清理任何未跟踪文件。
> ⚠ 服务器到 GitHub 的 **HTTPS(443) 常被墙**，git 必须走 SSH（`git@github.com:...`），
> 这也是 remote 用 SSH 地址的原因。

## 6. 回滚

```bash
cd deploy
docker compose down                 # 不删卷，数据安全
git checkout <上一个发布 tag>
docker compose up -d --build
```
镜像层缓存保证回滚构建 <2 分钟；PG/Redis 卷不受 compose down 影响（仅 `down -v` 才删卷——【人工确认】）。

## 【人工确认】清单（AI 禁止自主执行）

- `docker compose down -v` / 任何删除 named volume 的操作；
- `pg_restore` 到**非空**库、`drop schema/table`、`truncate`；
- `redis-cli flushall/flushdb`；
- 修改安全组/防火墙规则、删除 .env 备份；
- 宿主机 `rm -rf` 任何目录；
- `git clean -xfd`（会删除未跟踪的 `deploy/.env`）。

## 部署契约（DoD，防模板腐化）

凡改动触及以下任一项，**同一 commit 必须更新 deploy/ 对应文件**：
1. 新增后端 API 前缀 → nginx.conf.template 白名单正则 + probes.sh；
2. 新增环境变量/密钥 → env.template +（如 yml 无占位）application.yml 占位；
3. 新增表/列 → scripts/pg-schema.sql + migrate.md 注意事项；
4. 新增端口/服务 → docker-compose.yml；
5. 资源画像变化（新重依赖）→ compose 内存参数 + 本 runbook 前置检查。
