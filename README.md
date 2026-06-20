# Ifdain — 爱发电支付集成

[![Java](https://img.shields.io/badge/Java-17%2B-blue)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.18-brightgreen)](https://spring.io/projects/spring-boot)
[![Maven](https://img.shields.io/badge/Maven-3.8%2B-orange)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

**Ifdain** 是一个面向 Java 生态的 [爱发电 (afdian.net)](https://ifdian.net) 支付集成库。

作为 **Spring Boot Starter**，它提供了 Webhook 订单接收、API 客户端、对外 REST API、OAuth2 授权、管理后台等开箱即用的能力，只需引入依赖并简单配置即可快速接入爱发电支付通知与开放 API。

---

## 功能特性

- **Webhook 订单接收** — 自动接收爱发电推送的订单通知，RSA-SHA256 签名验证，幂等去重，持久化到数据库
- **API 客户端** — 封装爱发电全部开放 API（订单查询、赞助者查询、方案查询、私信发送、自动回复更新等）及 MD5 签名逻辑
- **对外 REST API** — 供第三方项目通过 API Key 接入，支持 VIP/订阅状态验证、订单查询、赞助者查询、方案查询
- **OAuth2 授权** — 完整 authorization_code 流程，自动保存用户绑定信息
- **管理后台** — 内置 Web 管理界面，Spring Security 表单登录保护
  - 仪表盘（订单统计、收入汇总、方案分析）
  - 订单管理（分页列表、关键词搜索、订单详情）
  - API 工具（在线调试爱发电 API：Ping、查订单、查赞助者、查方案、发私信、订单拉取同步入库）
  - 系统设置（API/Webhook/Redis/账户配置、数据库初始化、连接测试）
  - 安装向导（首次部署 5 步引导：创建管理员 → 配置 API → 配置 Redis → 配置 Webhook → 完成）
- **两种运行模式**
  - **Embedded（嵌入式）** — H2 内存数据库，自动建表，作为依赖嵌入宿主项目
  - **Standalone（独立模式）** — MySQL 数据库，支持自动建表，可独立部署运行
- **可扩展订单处理器** — 实现 `IfdianOrderProcessor` 接口即可定制订单处理业务逻辑
- **自动配置** — Spring Boot Auto-Configuration，引入即用

---
## Linux 快速开始

### 1. 下载

访问下载页面获取安装包：

```
https://clud.www.mortaltom.online/#s/EDTitQUP
```

### 2. 上传服务器并解压

```bash
tar -xzf ifdian.tar.gz
cd ifdian
```

### 3. 安装

自动检测环境、安装依赖、注册 systemd 服务并启动：

```bash
sudo bash install.sh
```

### 4. 访问管理后台

启动后访问：

```
http://服务器IP:8888/admin
```

默认密码：`admin`（首次访问会进入安装向导强制修改）

## 快速开始

### 1. 引入依赖

**Maven：**

```xml
<dependency>
    <groupId>com.ifdain</groupId>
    <artifactId>ifdain-core</artifactId>
    <version>2.1.0.4bate</version>
</dependency>

<!-- 如果使用 standalone 模式，还需引入 MySQL 驱动 -->
<dependency>
    <groupId>mysql</groupId>
    <artifactId>mysql-connector-java</artifactId>
    <version>8.0.33</version>
</dependency>
```

**Gradle：**

```groovy
implementation 'com.ifdain:ifdain-core:2.1.0.4bate'
```

### 2. 配置参数

在 `application.yml` 中配置：

```yaml
ifdain:
  # 开发者身份（必填，从爱发电开发者后台获取）
  user-id: your_user_id_here
  api-token: your_api_token_here

  # 运行模式: embedded（默认） / standalone
  mode: embedded

  # Webhook 配置
  webhook-path: /webhook/ifdian
  enable-webhook-signature: true
  webhook-public-key: |   # RSA 公钥（启用签名验证时需要）
    -----BEGIN PUBLIC KEY-----
    MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA...
    -----END PUBLIC KEY-----

  # API 配置
  api-base-url: https://ifdian.net
  api-timeout-ms: 10000

  # 对外 API Key（第三方项目接入时使用）
  external-api-key: your-secret-api-key

  # OAuth2 配置（需向爱发电官方申请）
  oauth2-client-id: your_client_id
  oauth2-client-secret: your_client_secret

  # 管理后台配置（仅 ifdain-boot 模块）
  admin:
    username: admin
    password: admin           # ← 首次安装后将被替换，务必修改！
    base-path: /admin
```

### 3. 启动应用

```bash
# embedded 模式（默认，H2 内存数据库，开箱即用）
mvn spring-boot:run

# standalone 模式（MySQL）
mvn spring-boot:run -Dspring.profiles.active=standalone
```

启动后访问 `http://localhost:8888/admin` 进入安装向导，按步骤完成初始化配置。

在爱发电开发者后台将 **Webhook URL** 配置为：

```
https://你的域名/webhook/ifdian
```

---

## 运行模式说明

### Embedded 模式（默认）

| 项目 | 说明 |
|------|------|
| 数据库 | H2 内存库 `jdbc:h2:mem:ifdain` |
| 建表 | JPA `ddl-auto: update` 自动建表 |
| 控制台 | `http://localhost:8888/h2-console` |
| 适用场景 | 作为 Starter 嵌入宿主项目、开发测试 |

### Standalone 模式

| 项目 | 说明 |
|------|------|
| 数据库 | MySQL |
| 建表 | 启动时自动执行 `schema.sql` 建表 |
| 激活方式 | `--spring.profiles.active=standalone` |
| 适用场景 | 独立部署、生产环境 |

---

## 配置参考

### `ifdain.*` 完整配置项

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `ifdain.mode` | String | `embedded` | 运行模式：`embedded` / `standalone` |
| `ifdain.user-id` | String | — | **必填**。爱发电开发者 user_id |
| `ifdain.api-token` | String | — | **必填**。API Token |
| `ifdain.webhook-path` | String | `/webhook/ifdian` | Webhook 端点路径 |
| `ifdain.enable-webhook-signature` | Boolean | `true` | 是否启用 Webhook 签名验证 |
| `ifdain.webhook-public-key` | String | — | RSA 公钥（PEM 格式） |
| `ifdain.api-base-url` | String | `https://ifdian.net` | API 基础地址 |
| `ifdain.api-timeout-ms` | Long | `10000` | API 请求超时（毫秒） |
| `ifdain.order-processor-bean` | String | `defaultOrderProcessor` | 自定义订单处理器 Bean 名称 |
| `ifdain.external-api-key` | String | — | 对外 REST API 的访问密钥 |
| `ifdain.oauth2-client-id` | String | — | OAuth2 客户端 ID |
| `ifdain.oauth2-client-secret` | String | — | OAuth2 客户端密钥 |

### `ifdain.admin.*` 配置项

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `ifdain.admin.username` | String | `admin` | 管理后台登录用户名 |
| `ifdain.admin.password` | String | `admin` | 管理后台登录密码（首次安装后将被替换） |
| `ifdain.admin.base-path` | String | `/admin` | 管理后台基础路径 |

---

## API 使用

### Webhook 接收

Webhook 端点默认路径为 `POST /webhook/ifdian`，接收爱发电推送的订单通知。

**请求格式：** 爱发电推送的 JSON（详见[爱发电开发者文档](https://ifdian.net/dashboard/dev)）

**响应格式：**

```json
{"ec": 200, "em": ""}
```

**健康检查：**

```
GET /webhook/health
```

### API 客户端

`AfdianApiClient` 封装了爱发电全部开放 API 的签名生成与 HTTP 调用：

```java
@Autowired
private AfdianApiClient apiClient;

// 测试签名连通性
boolean ok = apiClient.ping();

// 查询订单列表（分页）
JsonNode orders = apiClient.queryOrders(1, 50);

// 按订单号精确查询
JsonNode order = apiClient.queryOrdersByNos(List.of("202106232138371083454010626"));

// 查询赞助者列表
JsonNode sponsors = apiClient.querySponsors(1, 20);

// 查询方案详情
JsonNode plan = apiClient.queryPlan("a45353328af911eb973052540025c377");

// 发送私信（限频: 10次/秒 或 1000次/小时）
JsonNode msg = apiClient.sendPrivateMessage("user_id_xxx", "感谢支持！");

// 更新方案自动回复
JsonNode reply = apiClient.updatePlanReply("plan_id", null, "固定回复", "随机回复1\n随机回复2", "overwrite");

// 查询随机自动回复
JsonNode random = apiClient.queryRandomReply("order_no_xxx");
```

**订单拉取同步（主动入库）：**

除了被动接收 Webhook 推送外，还可以通过管理后台 API 工具页面的"拉取订单同步"功能，从爱发电 API 主动拉取全部历史订单并保存到本地数据库。适用于：

- **Webhook 漏单补偿** — 如果某些通知因网络问题未到达，可通过拉取补全
- **首次数据初始化** — 部署前已存在的赞助者数据可以批量同步
- **数据校验** — 定期拉取与本地数据对比，确保完整性

同步功能自动处理去重（已存在的订单跳过），支持设置起始页、每页条数和最大页数。

```java
// 也可通过代码调用
@Autowired
private IfdianWebhookService webhookService;

// 遍历 API 响应的 list，逐条保存
for (JsonNode orderNode : apiResult.path("data").path("list")) {
    webhookService.saveOrderFromApi(orderNode, orderNode.toString());
}
```

### 对外 REST API

`/api/external/**` 路径下的 REST API 供第三方项目接入使用，所有接口需通过 `X-Api-Key` 请求头或 `api_key` 参数认证。

**通用响应格式：**

```json
{
  "code": 200,
  "message": "success",
  "data": { ... }
}
```

**接口列表：**

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/external/health` | GET | 健康检查（无需认证） |
| `/api/external/orders?out_trade_no=xxx` | GET | 按订单号查询订单 |
| `/api/external/orders?user_id=xxx` | GET | 按用户ID查询订单 |
| `/api/external/orders?custom_order_id=xxx` | GET | 按自定义订单ID查询 |
| `/api/external/orders?page=0&size=20` | GET | 分页查询全部订单 |
| `/api/external/verify-subscription?user_id=xxx&plan_id=xxx` | GET | 验证用户VIP/订阅状态 |
| `/api/external/sponsor?afdian_user_id=xxx` | GET | 查询赞助者详情 |
| `/api/external/sponsor?page=1&per_page=20` | GET | 分页查询赞助者列表 |
| `/api/external/plan?plan_id=xxx` | GET | 查询方案详情 |

**订阅验证示例：**

```bash
curl -H "X-Api-Key: your-api-key" \
  "http://localhost:8888/api/external/verify-subscription?user_id=adf397fe8374811eaacee52540025c377&plan_id=a45353328af911eb973052540025c377"
```

响应：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "subscribed": true,
    "active_plans": ["a45353328af911eb973052540025c377"],
    "total_paid": 15.00,
    "order_count": 3,
    "latest_order_time": "2026-06-15T10:30:00"
  }
}
```

### OAuth2 授权

支持完整的 `authorization_code` 流程，用于第三方项目通过爱发电账号登录并关联用户身份。

**OAuth2 端点：**

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/external/oauth2/authorize?redirect_uri=xxx` | GET | 获取授权 URL |
| `/api/external/oauth2/callback?code=xxx&redirect_uri=xxx` | GET | 处理回调，换 token 并保存绑定 |
| `/api/external/oauth2/binding?user_private_id=xxx` | GET | 查询用户绑定信息 |
| `/api/external/oauth2/binding?local_user_id=xxx` | GET | 按本地用户ID反查绑定 |

**使用流程：**

1. 调用 `/api/external/oauth2/authorize` 获取授权 URL
2. 将用户重定向到爱发电授权页面
3. 用户同意后，爱发电回调到 `redirect_uri` 并携带 `code` 参数
4. 调用 `/api/external/oauth2/callback` 完成授权，获取 `user_private_id`
5. 后续可通过 `user_private_id` 调用订阅验证接口查询用户状态

---

## 管理后台

`ifdain-boot` 模块内置了基于 Thymeleaf + Bootstrap 5 的 Web 管理界面。

### 访问地址

启动后访问 `http://localhost:8888/admin`，首次访问会自动进入**安装向导**，按步骤完成初始化后即可登录使用。

### 功能页面

| 页面 | 路径 | 说明 |
|------|------|------|
| 安装向导 | `/admin/setup` | 首次部署的 5 步引导流程（管理员 → API → Redis → Webhook → 完成），已完成安装后自动屏蔽 |
| 登录页 | `/admin/login` | Spring Security 表单登录 |
| 仪表盘 | `/admin` | 总订单/已支付/待处理统计；近 7 天/30 天收入；方案汇总分析 |
| 订单管理 | `/admin/orders` | 分页列表、关键词搜索、订单详情（含原始 JSON 快照） |
| API 工具 | `/admin/tools` | 在线调试：Ping 连通测试、查询订单、查询赞助者、查询方案、发送私信、**拉取订单同步入库** |
| 系统设置 | `/admin/settings` | API/Webhook 配置、Redis 配置、管理员账号、数据库状态、连接测试 |
| 赞助方案 | `/admin/plans` | 方案发现、查询、创建、修改、删除、隐藏（支持浏览器自动化与 API 两种模式） |

### 安装向导安全机制

安装完成后，`setup_completed` 标记设为 `true`，安装向导相关路径会自动重定向到仪表盘，防止未授权访问修改配置。可通过系统设置页面随时更新配置。

---

## 自定义订单处理

当 Webhook 接收到新订单并持久化后，会调用订单处理器执行业务逻辑。默认处理器仅将订单标记为已处理。

### 实现自定义处理器

```java
@Component("myOrderProcessor")
public class MyOrderProcessor implements IfdianOrderProcessor {

    @Override
    public void process(IfdianOrder order) {
        String orderNo = order.getOutTradeNo();
        String planId = order.getPlanId();
        BigDecimal amount = order.getTotalAmount();

        // 你的业务逻辑：发货、发邮件、更新积分等
        // ...

        // 标记为已处理
        order.setProcessed(1);
    }
}
```

### 配置自定义处理器

```yaml
ifdain:
  order-processor-bean: myOrderProcessor
```

### 订单实体主要字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | Integer | 自增主键 |
| `outTradeNo` | String | 爱发电订单号（唯一索引） |
| `customOrderId` | String | 自定义订单 ID（下单时传入） |
| `userId` | String | 下单用户 ID |
| `userPrivateId` | String | 用户唯一标识（OAuth2 关联用） |
| `planId` | String | 方案 ID |
| `sponsorMonth` | Integer | 赞助月数 |
| `totalAmount` | BigDecimal | 实付金额（元） |
| `showAmount` | BigDecimal | 显示金额（折扣前） |
| `status` | OrderStatus | `PAID`=交易成功 |
| `remark` | String | 用户留言 |
| `redeemId` | String | 兑换码 ID |
| `productType` | Integer | `0`=常规方案，`1`=售卖方案 |
| `discount` | BigDecimal | 折扣金额 |
| `skuDetail` | String | 售卖商品 SKU 明细（JSON） |
| `addressPerson` | String | 收货人姓名 |
| `addressPhone` | String | 收货人电话 |
| `addressAddress` | String | 收货地址 |
| `receivedAt` | LocalDateTime | Webhook 接收时间 |
| `rawData` | String | 原始 Webhook 推送数据（JSON 快照） |
| `processed` | Integer | `0`=未处理，`1`=已处理 |
| `retryCount` | Integer | Webhook 重试计数 |
| `errorMsg` | String | 最近一次处理失败原因 |
| `createdAt` | LocalDateTime | 创建时间 |
| `updatedAt` | LocalDateTime | 更新时间 |

---

## 项目结构

```
ifdain/
├── ifdain-core/              # 核心模块（可作为 Starter 引入宿主项目）
│   └── src/main/java/com/ifdain/
│       ├── config/           # 自动配置、属性类、模式检测
│       ├── controller/       # Webhook 接收端点
│       ├── entity/           # 订单实体、OAuth2 绑定、日志、配置
│       ├── repository/       # JPA 数据访问层
│       ├── service/          # Webhook 处理、API 客户端、订单处理器
│       └── util/             # 签名工具类（MD5 + RSA-SHA256）
├── ifdain-boot/              # 可独立运行的 Spring Boot 应用
│   └── src/main/
│       ├── java/.../IfdainApplication.java
│       ├── java/.../admin/   # 管理后台（控制器、安全配置、OAuth2、系统配置、数据库初始化）
│       │   ├── AdminController.java
│       │   ├── AdminSecurityConfig.java
│       │   ├── ConnectionTestController.java
│       │   ├── DatabaseInitializer.java
│       │   ├── ExternalApiController.java
│       │   ├── OAuth2Controller.java
│       │   ├── OAuth2Service.java
│       │   ├── SettingsController.java
│       │   ├── SetupController.java
│       │   ├── SystemConfigService.java
│       │   └── ToolsController.java
│       └── resources/
│           ├── application.yml
│           └── templates/admin/  # Thymeleaf 管理后台模板
│               ├── layout.html       # 全局布局（响应式侧边栏）
│               ├── login.html
│               ├── setup.html        # 安装向导
│               ├── dashboard.html    # 仪表盘
│               ├── orders.html       # 订单列表
│               ├── order-detail.html # 订单详情
│               ├── settings.html     # 系统设置
│               ├── plans.html        # 赞助方案管理
│               └── tools.html        # API 工具
├── sql/
│   └── schema.sql            # MySQL 建表脚本
├── pom.xml                   # 父 POM
└── README.md
```

---

## 构建

```bash
# 编译全部模块
mvn clean compile

# 运行测试
mvn test

# 构建可运行的 JAR
mvn clean package -pl ifdain-boot

# 运行（embedded 模式）
java -jar ifdain-boot/target/ifdain-boot-2.1.0.4bate.jar

# standalone 模式运行
java -jar ifdain-boot/target/ifdain-boot-2.1.0.4bate.jar --spring.profiles.active=standalone
```

### 环境要求

- **JDK 17+**（推荐 JDK 21）
- **Maven 3.8+**
- **MySQL 8.0+**（仅 standalone 模式需要）

---

## 爱发电开发者资源

- [开发者后台](https://ifdian.net/dashboard/dev)
- Webhook 订单推送（POST JSON 到配置的 URL）
- API 签名机制：`md5(token + params{params}ts{ts}user_id{user_id})`
- Webhook 签名：**RSA-SHA256**，拼接 `out_trade_no + user_id + plan_id + total_amount`
- OAuth2 授权：`authorization_code` 模式，需联系官方客服申请
- 开发咨询 / OAuth2 申请：私信官方客服 [Lain音酱](https://ifdian.net/message/27f7cea2370d11e8ae8852540025c377)

---

## 安全提示

- 默认管理员密码为 `admin`，**首次安装向导会强制修改**，请勿使用默认密码部署到公网
- `application.yml` 中 standalone/production 配置的数据库密码为占位符，部署前必须替换为真实密码
- 建议生产环境通过环境变量或 `application-local.yml`（已 gitignore）覆盖敏感配置
- `ifdain.external-api-key` 和 `ifdain.oauth2-client-secret` 等密钥切勿使用示例值

---

## 许可证

[Apache License 2.0](LICENSE)

Copyright (c) 2026 Ifdain
