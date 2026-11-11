-- 停后端后升级再启动新版；加列不恢复旧消息中从未保存的证据。
BEGIN;
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS auth_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS evidence TEXT NOT NULL DEFAULT '{}';
COMMIT;
