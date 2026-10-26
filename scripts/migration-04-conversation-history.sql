-- 先停旧后端（旧代码会物理裁剪原文），备份后在同一事务升级，再启动新版本。
-- 只保留现有原文；不恢复、不推造历史已删除的消息。可重复执行。
BEGIN;
ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS turn_id VARCHAR(36);
ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'COMPLETE';
CREATE UNIQUE INDEX IF NOT EXISTS idx_msg_turn_role ON chat_message(session_id,turn_id,role);
CREATE INDEX IF NOT EXISTS idx_msg_user_cursor ON chat_message(session_id,id) WHERE role='USER';
CREATE INDEX IF NOT EXISTS idx_msg_running ON chat_message(created_at) WHERE status='RUNNING';
ALTER TABLE chat_summary ADD COLUMN IF NOT EXISTS legacy BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE chat_summary ALTER COLUMN legacy SET DEFAULT FALSE;
ALTER TABLE chat_summary ADD COLUMN IF NOT EXISTS covered_until_message_id BIGINT NOT NULL DEFAULT 0;
ALTER TABLE chat_summary ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE chat_summary ADD COLUMN IF NOT EXISTS lease_token VARCHAR(36);
ALTER TABLE chat_summary ADD COLUMN IF NOT EXISTS lease_until TIMESTAMP;
ALTER TABLE chat_summary ADD COLUMN IF NOT EXISTS failures INTEGER NOT NULL DEFAULT 0;
ALTER TABLE chat_summary ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_summary_retry ON chat_summary(next_attempt_at);
INSERT INTO chat_summary(session_id,content)
SELECT session_id,'' FROM chat_session ON CONFLICT(session_id) DO NOTHING;
COMMIT;
