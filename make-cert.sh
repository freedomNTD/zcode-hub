#!/usr/bin/env bash
# 生成本地自签名 CA 和服务器证书（SAN 带局域网 IP），用于把面板变成 https。
# 手机装一次 certs/ca.pem 之后，浏览器就认为它是安全来源：
#   - 可以实时开摄像头扫码（而不是只能拍照）
#   - 可以「添加到主屏幕」成独立 App（PWA）
set -euo pipefail
# Git Bash 会把 -subj "/CN=..." 当成路径来转换，这里关掉
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'
cd "$(dirname "$0")"
OUT=certs
mkdir -p "$OUT"
rm -f "$OUT/ca.key" "$OUT/ca.pem"

IP="${1:-$(node -e '
const os=require("os");
const ips=[];
for(const l of Object.values(os.networkInterfaces())) for(const i of l||[]) if(i.family==="IPv4"&&!i.internal) ips.push(i.address);
console.log(ips.find(p=>p.startsWith("192.168."))||ips.find(p=>p.startsWith("10."))||ips[0]||"127.0.0.1");
')}"
echo "为 192.168.x 地址签发：$IP"

openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes \
  -keyout "$OUT/ca.key" -out "$OUT/ca.pem" \
  -subj "/CN=ZCode Hub Local CA" \
  -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null

cat > "$OUT/ext.cnf" <<EOF
subjectAltName=IP:$IP,IP:127.0.0.1,DNS:localhost
extendedKeyUsage=serverAuth
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
EOF

openssl req -newkey rsa:2048 -nodes -keyout "$OUT/server.key" -out "$OUT/server.csr" \
  -subj "/CN=$IP" 2>/dev/null
openssl x509 -req -in "$OUT/server.csr" -CA "$OUT/ca.pem" -CAkey "$OUT/ca.key" \
  -CAcreateserial -out "$OUT/server.pem" -days 825 -sha256 -extfile "$OUT/ext.cnf" 2>/dev/null
rm -f "$OUT/server.csr" "$OUT/ca.srl"

echo "完成："
echo "  $OUT/ca.pem      ← 手机装这个（CA 证书）"
echo "  $OUT/server.pem  ← 服务端证书"
echo "  $OUT/server.key"
echo "重启服务后访问 https://$IP:8443/?k=<令牌>"
