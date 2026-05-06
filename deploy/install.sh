#!/usr/bin/env bash
# JavaClaw 部署一键脚本(在目标机器上的 zip 解压根目录里跑)
#
# 用法:
#   sudo ./install.sh [INSTALL_DIR]
#   默认 INSTALL_DIR = /data/javaClaw   (大容量独立盘)
#
# 流程:
#   1. 创建 javaclaw 用户/组(若不存在)
#   2. 在 INSTALL_DIR 下创建 web-console / workspaces / logs 子目录
#   3. 拷贝 jar + web-console/dist 到 INSTALL_DIR
#   4. 把 nginx-javaclaw.conf 放进 /etc/nginx/conf.d/
#   5. 装 systemd unit 到 /etc/systemd/system/
#   (无 env 文件 —— DB 凭据写死在 jar 内置 application-prod.yml,LLM 全走 DB)

set -euo pipefail

INSTALL_DIR="${1:-/data/javaClaw}"
NGINX_ROOT="/etc/nginx/conf.d"
SYSTEMD_DIR="/etc/systemd/system"

if [[ $EUID -ne 0 ]]; then
  echo "需要 root: sudo $0 $*" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "[install] 部署源: $SCRIPT_DIR"
echo "[install] 目标: $INSTALL_DIR"

# 1) 用户/组
if ! id -u javaclaw >/dev/null 2>&1; then
  echo "[install] 创建 javaclaw 系统用户"
  useradd --system --shell /usr/sbin/nologin --home-dir "$INSTALL_DIR" javaclaw
fi

# 2) 目录结构(全部在大容量盘下)
#    INSTALL_DIR/
#      ├── agent-app.jar
#      (无 javaclaw.env —— DB/LLM 都不用环境变量)
#      ├── web-console/             nginx 静态根 + 后端 EmbedStaticConfig 读取
#      ├── workspaces/              LLM 运行时产物 (可能 GB 级,所以放大盘)
#      └── logs/                    后端 stdout/stderr,journalctl 之外的备份
mkdir -p "$INSTALL_DIR" "$INSTALL_DIR/workspaces" "$INSTALL_DIR/logs"
# web-console 目录在拷贝时才建,先准备空壳

# 3) 拷贝产物
echo "[install] 拷贝 backend jar"
install -m 0644 -o javaclaw -g javaclaw "$SCRIPT_DIR/backend/agent-app.jar" "$INSTALL_DIR/agent-app.jar"

echo "[install] 拷贝 web-console dist"
rm -rf "$INSTALL_DIR/web-console"
mkdir -p "$INSTALL_DIR/web-console"
cp -rT "$SCRIPT_DIR/web-console" "$INSTALL_DIR/web-console"

# 4) 权限统一
#    INSTALL_DIR 自身:    0701 — owner javaclaw 满权限,others 只 x(能穿过到子目录但
#                                不能 ls 列出 jar / JWT_SECRET.txt / FIRST_ADMIN_PASSWORD.txt)。
#                                没这一行,useradd 创建的 home 默认 0750(group=javaclaw),
#                                nginx 不在 javaclaw 组就连进都进不去 → 静态资源全 403。
#    web-console 目录: 0755 (nginx www-data 需要 read+x);文件 0644
#    workspaces / logs: javaclaw 自己读写;0755 即可
chown -R javaclaw:javaclaw "$INSTALL_DIR"
chmod 0701 "$INSTALL_DIR"
find "$INSTALL_DIR/web-console" -type d -exec chmod 0755 {} +
find "$INSTALL_DIR/web-console" -type f -exec chmod 0644 {} +

# 5) (DB 凭据已经写死在 jar 内的 application-prod.yml,LLM 全走 DB,
#     不再有 javaclaw.env 文件;若以后要切 secret 注入方式,在这里加 env 处理)

# 6) nginx
# 配置无变化就完全跳过(不 install、不 nginx -t、不 reload)。idempotent 重跑安装脚本
# 不应该折腾 nginx 服务。只在 conf.d/javaclaw.conf 跟源不一致时才走 install + 校验 + reload。
NGINX_SRC="$SCRIPT_DIR/nginx-javaclaw.conf"
NGINX_DST="$NGINX_ROOT/javaclaw.conf"
if [[ ! -d "$NGINX_ROOT" ]]; then
  echo "[install] WARN: $NGINX_ROOT 不存在,nginx 配置请手动放到合适位置"
elif [[ -f "$NGINX_DST" ]] && cmp -s "$NGINX_SRC" "$NGINX_DST"; then
  echo "[install] nginx 配置无变化,跳过 install + reload"
else
  echo "[install] 安装 nginx 配置(diff 或新装)"
  install -m 0644 "$NGINX_SRC" "$NGINX_DST"
  if ! command -v nginx >/dev/null 2>&1; then
    echo "[install] WARN: 系统未装 nginx,跳过 reload。请安装后 nginx -t && systemctl reload nginx"
  elif ! nginx -t; then
    echo "[install] WARN: nginx -t 校验失败,请手动检查 $NGINX_DST"
  elif systemctl reload nginx; then
    echo "[install] nginx 已 reload"
  elif systemctl restart nginx; then
    echo "[install] nginx reload 失败,已改用 restart 起来"
  else
    echo "[install] WARN: nginx reload + restart 都失败,请手动: sudo systemctl status nginx"
  fi
fi

# 7) systemd
echo "[install] 安装 systemd unit"
install -m 0644 "$SCRIPT_DIR/javaclaw-backend.service" "$SYSTEMD_DIR/javaclaw-backend.service"
systemctl daemon-reload

echo "[install] 完成。下一步:"
cat <<EOF

  1. 启动后端:
       sudo systemctl enable --now javaclaw-backend
       sudo systemctl status javaclaw-backend
       sudo journalctl -u javaclaw-backend -f
  2. 验证:
       curl http://10.173.108.120:8888/api/apps/xmap/enabled
       curl --max-time 3 http://10.173.108.120:8080/api/apps/xmap/enabled  # 应连不上
  3. 默认 admin 密码在第一次启动时自动生成,看:
       sudo cat $INSTALL_DIR/FIRST_ADMIN_PASSWORD.txt
  4. LLM 配置走 admin UI(/llms 菜单)维护,数据库表:llm_provider_config
  5. 目录布局:
       $INSTALL_DIR/agent-app.jar     后端 jar (DB 凭据写死在内置 application-prod.yml)
       $INSTALL_DIR/web-console/      前端 dist (nginx 静态根)
       $INSTALL_DIR/workspaces/       LLM 运行产物 (可能 GB 级)
       $INSTALL_DIR/logs/             后端 stdout/stderr 备份
       $INSTALL_DIR/JWT_SECRET.txt    spring 首启动自动生成
       $INSTALL_DIR/FIRST_ADMIN_PASSWORD.txt   首启动 admin 密码

EOF
