以下是根据你提供的三个HTML文件提取的爱发电（afdian.net）开发者文档核心内容，涵盖 Webhook、API、OAuth2、前端嵌入及使用指南。

---

## 一、开发者后台基础信息

**地址**：`https://ifdian.net/dashboard/dev`

- **user_id**：唯一标识当前创作者账号  
  示例：`0c63a99c3fcb11ed912052540025c377`

- **API Token**：用于主动请求 API，需妥善保管，切勿泄露。  
  可在后台生成 / 重置。

---

## 二、Webhook（被动接收订单通知）

### 1. 配置
在开发者后台填写 **Webhook URL**（如 `https://yourdomain.com/webhook`），保存后可点击【发送测试】验证连通性。

### 2. 推送数据格式（POST JSON）
```json
{
  "ec": 200,
  "em": "ok",
  "data": {
    "type": "order",
    "order": {
      "out_trade_no": "202106232138371083454010626",
      "custom_order_id": "Steam12345",
      "user_id": "adf397fe8374811eaacee52540025c377",
      "user_private_id": "fdf981fu8f7g891euacee57430321c377",
      "plan_id": "a45353328af911eb973052540025c377",
      "month": 1,
      "total_amount": "5.00",
      "show_amount": "5.00",
      "status": 2,
      "remark": "",
      "redeem_id": "",
      "product_type": 0,
      "discount": "0.00",
      "sku_detail": [],
      "address_person": "",
      "address_phone": "",
      "address_address": ""
    }
  }
}
```

### 3. 开发者必须返回的响应
```json
{"ec":200,"em":""}
```
若返回非 `ec=200`，平台视为推送失败（可能会重试）。

### 4. Webhook 签名验证（2025年7月后支持）
- **公钥**（RSA，SHA256）见文档原文。
- **签名数据**：按顺序拼接 `out_trade_no + user_id + plan_id + total_amount`。
- 验证示例（PHP）：
```php
$publicKey = "-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----";
$ok = openssl_verify($sign_str, base64_decode($sign), $publicKey, 'SHA256');
```

---

## 三、API（主动查询订单 / 赞助者）

### 1. 签名机制
所有 API 请求需携带以下参数：
- `user_id`
- `params`：JSON 字符串
- `ts`：秒级时间戳
- `sign`：`md5(token + params{params}ts{ts}user_id{user_id})`

**示例**：
```json
{
  "user_id": "abc",
  "params": "{\"page\":1}",
  "ts": 1624339905,
  "sign": "a4acc28b81598b7e5d84ebdc3e91710c"
}
```

### 2. 测试签名接口
`POST https://ifdian.net/api/open/ping`  
返回 `ec=200` 表示签名正确；否则返回 `debug.kv_string` 用于排查。

### 3. 查询订单
**接口**：`POST https://ifdian.net/api/open/query-order`

**params 参数**：
- `page`：页码
- `per_page`：每页条数（默认50，最大100）
- `out_trade_no`：订单号（多个用逗号分隔）

**返回结构**（部分）：
```json
{
  "ec":200,
  "data": {
    "list": [ /* 订单对象数组 */ ],
    "total_count": 167,
    "total_page": 11
  }
}
```

### 4. 查询赞助者
**接口**：`POST https://ifdian.net/api/open/query-sponsor`

**params 支持**：
- `page`
- `per_page`（默认20，最大100）
- `user_id`（多个用逗号分隔）

**返回字段**：
- `sponsor_plans`：历史赞助方案
- `current_plan`：当前有效方案
- `all_sum_amount`：累计赞助金额
- `first_pay_time` / `last_pay_time`：首次/最近赞助时间（秒级时间戳）
- `user`：包含 `user_id`, `name`, `avatar`

### 5. 其他 API 接口（2025年新增）

| 功能 | 接口 | 说明 |
|------|------|------|
| 根据订单号查随机自动回复 | `/api/open/query-random-reply` | 参数 `out_trade_no` |
| 更新方案自动回复 | `/api/open/update-plan-reply` | 支持 `plan_id` 或 `sku_id`，`auto_reply` / `auto_random_reply` / `update_random_reply_type`（append/overwrite） |
| 发送私信 | `/api/open/send-msg` | 参数 `recipient`, `content`，限频 10次/秒 或 1000次/小时 |
| 查看方案详情 | `/api/open/query-plan` | 参数 `plan_id`，返回方案类型（订阅/商品/捆绑包等）、SKU 列表等 |

---

## 四、OAuth2 关联授权（第三方登录）

### 1. 申请方式
私信官方客服，提供：
- 应用名称
- 应用用途
- 可信域名
- clientSecret（可选，不提供则随机生成）

### 2. 授权流程（authorization_code 模式）

**步骤1：发起授权**
```
https://ifdian.net/oauth2/authorize?response_type=code&scope=basic&client_id=YOUR_CLIENT_ID&redirect_uri=YOUR_REDIRECT_URI&state=123
```

**步骤2：用户同意后回调**，携带 `code` 和 `state`

**步骤3：服务端换取 access_token（POST form）**
```
https://ifdian.net/api/oauth2/access_token
```
参数：
- `grant_type=authorization_code`
- `client_id`
- `client_secret`
- `code`
- `redirect_uri`

**返回示例**：
```json
{
  "ec":200,
  "data": {
    "user_id": "xxx",
    "user_private_id": "xxx",
    "name": "昵称",
    "avatar": "头像URL"
  }
}
```

---

## 五、前端嵌入与 URL 参数

### 1. 嵌入赞助模块（iframe）
```html
<iframe src="https://ifdian.net/leaflet?slug=你的主页后缀" width="640" height="200" frameborder="0"></iframe>
```
手机自适应版见文档内完整代码。

### 2. 赞助按钮图片
```html
<a href="https://ifdian.net/a/你的slug">
  <img width="200" src="https://pic1.afdiancdn.com/static/img/welcome/button-sponsorme.png">
</a>
```

### 3. URL 参数（下单页）
在 `https://ifdian.net/order/create` 后附加：
- `remark`：留言
- `month`：月数
- `custom_order_id`：自定义订单号
- `custom_price`：自选金额

示例：
```
https://ifdian.net/order/create?plan_id=xxx&product_type=0&month=3&remark=支持爱发电
```

---

## 六、字段说明汇总

### 订单字段
| 字段 | 含义 |
|------|------|
| out_trade_no | 订单号 |
| custom_order_id | 自定义订单号 |
| user_id | 下单用户ID |
| plan_id | 方案ID（自选为空） |
| month | 赞助月数 |
| total_amount | 实际支付金额 |
| show_amount | 显示金额（折扣前） |
| status | 2 = 交易成功 |
| product_type | 0=常规方案，1=售卖方案 |
| sku_detail | 售卖商品明细 |
| remark | 留言 |

### 赞助者字段
| 字段 | 含义 |
|------|------|
| all_sum_amount | 累计赞助金额 |
| create_time | 首次赞助时间（秒） |
| last_pay_time | 最近赞助时间（秒） |
| current_plan | 当前有效方案 |
| sponsor_plans | 历史方案列表 |

---

## 七、错误码（API）

| ec   | 含义 |
|------|------|
| 400001 | params incomplete |
| 400002 | time was expired（ts 过期，允许3600s误差） |
| 400003 | params 不是合法 JSON |
| 400004 | 未找到有效 token |
| 400005 | sign validation failed（签名验证失败） |

---

## 八、官方支持与反馈

- **开发咨询 / 申请 OAuth2**：私信官方客服 [Lain音酱](https://ifdian.net/message/27f7cea2370d11e8ae8852540025c377)
- **需求反馈**：[开发者需求问卷](https://ztitw2o1he.feishu.cn/share/base/form/shrcngLiP7eHYfPiM5zSXPhrpxg)

---

以上内容涵盖了爱发电面向开发者的全部主要接口与使用指南，可直接用于集成开发。如需具体代码示例或调试帮助，可进一步提问。