#!/usr/bin/env bash
# 部署后验收探针（runbook 第 6 步）：用法 ./probes.sh [base_url]
# 默认 https://127.0.0.1 —— 站点已全站 HTTPS（80 只做 301），且用 IP 探测时证书名
# 不匹配属预期，故统一带 -k 跳过校验（这里验的是"链路活"，不是"证书对不对"；
# 证书有效性由外部检查：openssl s_client -connect <域名>:443）。
set -u
BASE=${1:-https://127.0.0.1}
CURL_K="-k"
pass=0; fail=0
ok()   { echo "PASS $1"; pass=$((pass+1)); }
bad()  { echo "FAIL $1: $2"; fail=$((fail+1)); }

# 0 HTTPS 可用（若传 http:// 作为 BASE，这步会提示但不算失败）
if [ "${BASE#https}" != "$BASE" ]; then
  code=$(curl $CURL_K -s -o /dev/null -w '%{http_code}' "$BASE/")
  if [ "$code" = "200" ]; then ok https-200; else bad https-200 "HTTP $code"; fi
fi

# 1 静态首页（SPA 入口）
if curl $CURL_K -fsS "$BASE/" | grep -q 'id="app"'; then ok static-index; else bad static-index "首页非 SPA 入口"; fi

# 2 HTML 污染探针：API 必须返回 JSON 首字符 '{'（被 SPA fallback 接住=白名单漏配）
first=$(curl $CURL_K -fsS "$BASE/stats/home" 2>/dev/null | head -c 1)
if [ "$first" = "{" ]; then ok stats-json; else bad stats-json "首字符='$first'，疑似 HTML 污染/白名单漏配"; fi

# 3 配额接口（游客可读）
code=$(curl $CURL_K -s -o /dev/null -w '%{http_code}' "$BASE/auth/quota")
if [ "$code" = "200" ]; then ok quota-200; else bad quota-200 "HTTP $code"; fi

# 4 管理接口鉴权（未登录应 401/403，而非 200/HTML）
code=$(curl $CURL_K -s -o /dev/null -w '%{http_code}' "$BASE/admin/sync-status")
if [ "$code" = "401" ] || [ "$code" = "403" ]; then ok admin-auth-guard; else bad admin-auth-guard "HTTP $code（鉴权缺失或白名单漏配）"; fi

# 5 SSE 冒烟：20s 内必须出现 event: 行（流式链路活）
if curl $CURL_K -fsN -m 20 "$BASE/chat/stream?sessionId=probe-$$&userMessage=%E4%BD%A0%E5%A5%BD" 2>/dev/null | head -20 | grep -q '^event:'; then
  ok sse-streaming
else
  bad sse-streaming "20s 内无 SSE event（检查 proxy_buffering/后端存活）"
fi

# 6 头像静态资源
code=$(curl $CURL_K -s -o /dev/null -w '%{http_code}' "$BASE/avatars/1.jpg")
if [ "$code" = "200" ]; then ok avatar-static; else bad avatar-static "HTTP $code"; fi

# 7 HTTP 必须 301 到 HTTPS（全站强制，仅当 BASE 是 https 时用同域 http 探）
if [ "${BASE#https}" != "$BASE" ]; then
  http_base="http${BASE#https}"
  code=$(curl -s -o /dev/null -w '%{http_code}' "$http_base/")
  if [ "$code" = "301" ] || [ "$code" = "308" ]; then ok http-redirect; else bad http-redirect "HTTP $code（期望 301 跳 HTTPS）"; fi
fi

echo "---- probes: $pass passed, $fail failed ----"
[ "$fail" -eq 0 ]
