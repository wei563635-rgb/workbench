#!/usr/bin/env bash
set -euo pipefail

SERVER_NAME="${1:-_}"
SITE_ROOT="${2:-/var/www/wendajia-dashboard}"
SITE_NAME="${3:-wendajia.conf}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
TEMPLATE_PATH="${SCRIPT_DIR}/nginx-wendajia.conf.example"
NGINX_AVAILABLE="/etc/nginx/sites-available/${SITE_NAME}"
NGINX_ENABLED="/etc/nginx/sites-enabled/${SITE_NAME}"
TMP_CONFIG="$(mktemp)"

require_root() {
    if [[ "${EUID}" -ne 0 ]]; then
        echo "请用 root 运行，或在 sudo -i 后再执行。"
        exit 1
    fi
}

install_nginx() {
    if command -v nginx >/dev/null 2>&1; then
        echo "nginx 已安装，跳过安装步骤。"
        return
    fi

    if command -v apt-get >/dev/null 2>&1; then
        apt-get update
        apt-get install -y nginx
        return
    fi

    if command -v dnf >/dev/null 2>&1; then
        dnf install -y nginx
        systemctl enable nginx
        return
    fi

    if command -v yum >/dev/null 2>&1; then
        yum install -y nginx
        systemctl enable nginx
        return
    fi

    echo "没有找到受支持的包管理器，请手动安装 nginx 后再重试。"
    exit 1
}

sync_site_files() {
    mkdir -p "${SITE_ROOT}"

    if command -v rsync >/dev/null 2>&1; then
        rsync -a \
            --delete \
            --exclude ".git/" \
            --exclude "deploy/*.md" \
            "${PROJECT_ROOT}/" "${SITE_ROOT}/"
        return
    fi

    cp -a "${PROJECT_ROOT}/." "${SITE_ROOT}/"
}

write_nginx_config() {
    sed \
        -e "s|your-domain.com www.your-domain.com _|${SERVER_NAME}|g" \
        -e "s|/var/www/wendajia-dashboard|${SITE_ROOT}|g" \
        "${TEMPLATE_PATH}" > "${TMP_CONFIG}"

    cp "${TMP_CONFIG}" "${NGINX_AVAILABLE}"
    ln -sf "${NGINX_AVAILABLE}" "${NGINX_ENABLED}"
}

reload_nginx() {
    nginx -t
    systemctl enable nginx
    systemctl restart nginx
}

show_result() {
    echo
    echo "部署完成。"
    echo "站点目录: ${SITE_ROOT}"
    echo "Nginx 配置: ${NGINX_AVAILABLE}"
    echo "server_name: ${SERVER_NAME}"
    echo
    echo "如果你还没放行腾讯云安全组，请放行 TCP 80 和 TCP 443。"
}

cleanup() {
    rm -f "${TMP_CONFIG}"
}

trap cleanup EXIT

require_root
install_nginx
sync_site_files
write_nginx_config
reload_nginx
show_result
