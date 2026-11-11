"""只同步公开初始化 SQL 的 schema 部分，不连接数据库或导出业务数据。"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
template = (ROOT / "scripts/init-template.sql").read_text(encoding="utf-8")
schema = (ROOT / "scripts/pg-schema.sql").read_text(encoding="utf-8")
schema += "\n" + (ROOT / "scripts/migration-04-conversation-history.sql").read_text(encoding="utf-8")
schema += "\n" + (ROOT / "scripts/migration-05-security-and-evidence.sql").read_text(encoding="utf-8")
target = ROOT / "db/init.sql"
current = target.read_text(encoding="utf-8")
marker = "-- 数据区：法条底账"
data_pos = current.index(marker)
separator = current.rfind("-- ============================================================", 0, data_pos)
# 模板的说明头包含数据行数占位符，保留现有公开数据行数。
rows = re.search(r"共 (\d+) 条", current).group(1)
prefix = template.split("@@SCHEMA@@")[0].replace("@@ROWS@@", rows)
target.write_text(prefix + schema.rstrip() + "\n\n" + current[separator:], encoding="utf-8", newline="\n")
