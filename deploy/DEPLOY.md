# Ifdain 生产环境部署指南

## 前置条件

- 一台 Linux 服务器 (Ubuntu 20.04+ / Debian 11+ / CentOS 8+)
- 已解析到服务器的域名
- Java 11+ 和 MySQL 8.0+ 已安装
- 防火墙已开放 80 和 443 端口

---

## 1. 数据库初始化

```sql
-- 创建数据库
CREATE DATABASE ifdain CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'ifdain'@'localhost' IDENTIFIED BY 'your_strong_password';
GRANT ALL PRIVILEGES ON ifdain.* TO 'ifdain'@'localhost';
FLUSH PRIVILEGES;

-- 导入建表脚本
mysql -u root -p ifdain < sql/schema.sql
```

然后修改 `application.yml` 中 `production` profile 的数据库连接信息。

---

## 2. 构建 & 部署应用

```bash
# 在开发机上构建
cd /path/to/ifdain
mvn clean package -DskipTests

# 上传到服务器
scp ifdain-boot/target/ifdain-boot-*.jar user@your-server:/opt/ifdain/

# 在服务器上启动 (production profile)
java -jar /opt/ifdain/ifdain-boot-*.jar --spring.profiles.active=production
```

建议使用 systemd 管理服务，见下方。

---

## 3. Nginx 安装与配置

```bash
# 安装 nginx
sudo apt update && sudo apt install nginx  # Ubuntu/Debian
# 或
sudo yum install nginx                      # CentOS

# 复制配置文件
sudo cp deploy/nginx.conf /etc/nginx/sites-available/ifdain
sudo ln -s /etc/nginx/sites-available/ifdain /etc/nginx/sites-enabled/

# 将配置文件中的 your-domain.com 替换为实际域名
sudo sed -i 's/your-domain.com/你的域名/g' /etc/nginx/sites-available/ifdain

# 测试配置
sudo nginx -t

# 重载
sudo nginx -s reload
```

---

## 4. SSL 证书 (Let's Encrypt)

```bash
# 安装 certbot
sudo apt install certbot python3-certbot-nginx  # Ubuntu/Debian

# 先确保 nginx HTTP (80) 配置已生效，然后:
sudo certbot --nginx -d your-domain.com

# 证书会自动续期，手动测试续期:
sudo certbot renew --dry-run
```

---

## 5. systemd 服务 (推荐)

创建 `/etc/systemd/system/ifdain.service`:

```ini
[Unit]
Description=Ifdain - 爱发电支付集成
After=network.target mysql.service

[Service]
User=ifdain
WorkingDirectory=/opt/ifdain
ExecStart=/usr/bin/java -jar /opt/ifdain/ifdain-boot.jar --spring.profiles.active=production
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal
Environment="JAVA_OPTS=-Xms256m -Xmx512m"

[Install]
WantedBy=multi-user.target
```

启动与开机自启:

```bash
sudo systemctl daemon-reload
sudo systemctl enable ifdain
sudo systemctl start ifdain
sudo systemctl status ifdain
```

---

## 6. 首次访问

打开 `https://your-domain.com/admin/setup`，按照向导完成初始化设置（设置管理员密码、配置爱发电 API 凭证等）。

---

## 7. 爱发电 Webhook 配置

在爱发电开发者后台 (https://ifdian.net/dashboard/dev)，将 Webhook 回调地址设置为:

```
https://your-domain.com/webhook/ifdian
```
