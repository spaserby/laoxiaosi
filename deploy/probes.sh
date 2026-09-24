#!/usr/bin/env bash
# 部署后验收探针（runbook 第 6 步）：用法 ./probes.sh [base_url]，默认 http://127.0.0.1
set -u
BASE=${1:-http://127.0.0.1}
pass=0; fail=0
ok()   { echo "PASS $1"; pass=$((pass+1)); }
bad()  { echo "FAIL $1: $2"; fail=$((fail+1)); }

# 1 静态首页（SPA 入口）
if curl -fsS "$BASE/" | grep -q 'id="app"'; then ok static-index; else bad static-index "首页非 SPA 入口"; fi

# 2 HTML 污染探针：API 必须返回 JSON 首字符 '{'（被 SPA fallback 接住=白名单漏配）
first=$(curl -fsS "$BASE/stats/home" 2>/dev/null | head -c 1)
if [ "$first" = "{" ]; then ok stats-json; else bad stats-json "首字符='$first'，疑似 HTML 污染/白名单漏配"; fi

# 3 配额接口（游客可读）
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/auth/quota")
if [ "$code" = "200" ]; then ok quota-200; else bad quota-200 "HTTP $code"; fi

# 4 管理接口鉴权（未登录应 401/403，而非 200/HTML）
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/admin/sync-status")
if [ "$code" = "401" ] || [ "$code" = "403" ]; then ok admin-auth-guard; else bad admin-auth-guard "HTTP $code（鉴权缺失或白名单漏配）"; fi

# 5 SSE 冒烟：20s 内必须出现 event: 行（流式链路活；参数名须与 ChatController 契约一致：userMessage）
if curl -fsN -m 20 "$BASE/chat/stream?sessionId=probe-$$&userMessage=%E4%BD%A0%E5%A5%BD" 2>/dev/null | head -20 | grep -q '^event:'; then
  ok sse-streaming
else
  bad sse-streaming "20s 内无 SSE event（检查 proxy_buffering/后端存活）"
fi

# 6 头像静态资源
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/avatars/1.jpg")
if [ "$code" = "200" ]; then ok avatar-static; else bad avatar-static "HTTP $code"; fi

echo "---- probes: $pass passed, $fail failed ----"
[ "$fail" -eq 0 ]
