-- ============================================================
-- 预设管理员种子（开源快速体验通道 · 显式可选，不随 db/init.sql 自动执行）
--   用户名：admin        密码：123456        角色：ADMIN
--   手机号：13800000000（示例号，建议改成你自己的号码，以便使用短信重置通道）
-- 密码哈希为 BCrypt（强度 10，与后端 SecurityConfig 编码器一致），已验证 MATCH；
-- 重新生成/验证哈希：java -cp <spring-security-crypto.jar> scripts/GenBcrypt.java encode <新密码>
--
-- ⚠️ 安全警告：默认口令仅适用于本地/内网首次体验。
--   公网部署请二选一：
--     ① 登录后立刻改密（下方"改密方法"）；
--     ② 不用本种子，改用 deploy/runbook.md 的 BOOTSTRAP_ADMIN 流程（注册自己的账号后提权）。
-- ============================================================

-- 幂等守卫：仅当 admin 不存在时插入（可重复执行）
INSERT INTO app_user (username, password_hash, phone, role, status, created_at)
SELECT 'admin',
       '$2a$10$AWfF/BnyG7V2RDK44D97NufbHjoN200jxSAMfedKqOCuixAbWYvAy',
       '13800000000',
       'ADMIN',
       0,
       CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM app_user WHERE username = 'admin');

-- 改密方法（不走短信通道，直接换哈希）：
--   1) java -cp <spring-security-crypto.jar> scripts/GenBcrypt.java encode <你的新密码>
--   2) psql -U postgres -d lab_legal_kb -c \
--      "UPDATE app_user SET password_hash='<上一步输出的哈希>' WHERE username='admin';"
-- 注意：密码强度规则（8-32 位且同时含字母和数字）虽只在注册/重置时校验，
--       但预设的 123456 属于弱口令，正式使用前请务必替换。
