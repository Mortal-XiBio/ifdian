-- ============================================================
-- 爱发电 (afdian.net) 订单数据库 DDL (MySQL)
-- 用于 standalone 部署模式
-- 使用方式: mysql -u root -p ifdain < schema.sql
-- ============================================================

create database if not exists ifdain
    default character set utf8mb4
    default collate utf8mb4_unicode_ci;

use ifdain;

create table if not exists ifdian_orders
(
    id                int auto_increment
        primary key                                                     comment '自增主键',

    -- 爱发电核心字段 (来自 Webhook 推送)
    out_trade_no      varchar(64)                         not null        comment '爱发电订单号',
    custom_order_id   varchar(128)                        null            comment '自定义订单ID (下单时传入)',
    user_id           varchar(64)                         not null        comment '爱发电用户ID (下单用户)',
    user_private_id   varchar(64)                         null            comment '用户唯一标识 (OAuth2 关联用)',
    plan_id           varchar(64)                         not null        comment '方案ID (自选金额为空)',
    sponsor_month     int         default 1               null            comment '赞助月数',
    total_amount      decimal(10, 2)                      not null        comment '实付金额 (元)',
    show_amount       decimal(10, 2)                      null            comment '显示金额 (折扣前的原始金额)',
    status            tinyint     default 2               not null        comment '订单状态: 2=交易成功',
    remark            text                                null            comment '用户留言',
    redeem_id         varchar(128)                        null            comment '兑换码ID (兑换场景)',
    product_type      tinyint     default 0               not null        comment '方案类型: 0=常规方案 1=售卖方案',
    discount          decimal(10, 2) default 0.00         null            comment '折扣金额 (元)',
    sku_detail        json                                null            comment '售卖商品 SKU 明细',
    address_person    varchar(64)                         null            comment '收货人姓名',
    address_phone     varchar(32)                         null            comment '收货人电话',
    address_address   varchar(256)                        null            comment '收货地址',

    -- Webhook 接收时间 (非真实支付时间)
    received_at       datetime                            null            comment 'Webhook 接收时间',
    raw_data          json                                null            comment '原始 Webhook 推送数据 (JSON 快照)',
    processed         tinyint     default 0               not null        comment '业务处理标记: 0=未处理 1=已处理',
    retry_count       int         default 0               not null        comment 'Webhook 重试计数',
    error_msg         varchar(512)                        null            comment '最近一次处理失败原因',
    created_at        timestamp   default current_timestamp               comment '记录创建时间',
    updated_at        timestamp   default current_timestamp
                                  on update current_timestamp             comment '记录更新时间',

    -- 唯一约束
    constraint uk_out_trade_no
        unique (out_trade_no)
) engine = InnoDB
  default charset = utf8mb4
  collate = utf8mb4_unicode_ci
    comment '爱发电订单表';

-- 索引
create index idx_ifdian_custom_order_id   on ifdian_orders (custom_order_id);
create index idx_ifdian_received_at       on ifdian_orders (received_at);
create index idx_ifdian_plan_id           on ifdian_orders (plan_id);
create index idx_ifdian_user_id           on ifdian_orders (user_id);
create index idx_ifdian_processed         on ifdian_orders (processed);
create index idx_ifdian_created_at        on ifdian_orders (created_at);

-- ============================================================
-- 系统配置表
-- ============================================================
create table if not exists system_config
(
    id           int auto_increment primary key comment '自增主键',
    config_key   varchar(128)  not null comment '配置键',
    config_value text          null comment '配置值',
    description  varchar(256)  null comment '配置描述',
    updated_at   timestamp default current_timestamp
                     on update current_timestamp comment '更新时间',
    constraint uk_config_key unique (config_key)
) engine = InnoDB
  default charset = utf8mb4
  collate = utf8mb4_unicode_ci
    comment '系统配置表';

-- ============================================================
-- 审计日志表
-- ============================================================
create table if not exists ifdain_logs
(
    id           bigint auto_increment primary key comment '自增主键',
    level        varchar(16)  not null comment '日志级别: INFO/WARN/ERROR',
    source       varchar(32)  not null comment '事件来源',
    message      varchar(512) not null comment '日志消息摘要',
    out_trade_no varchar(64)  null comment '关联的爱发电订单号',
    exception    text         null comment '异常栈摘要',
    created_at   timestamp default current_timestamp comment '记录创建时间'
) engine = InnoDB
  default charset = utf8mb4
  collate = utf8mb4_unicode_ci
    comment '爱发电审计日志表';

-- ============================================================
-- 外部支付请求表
-- ============================================================
create table if not exists payment_requests
(
    id                    bigint auto_increment primary key       comment '自增主键',
    request_id            varchar(64)   not null                  comment '对外暴露的请求标识 (UUID)',
    user_id               varchar(64)   not null                  comment '爱发电用户 ID',
    plan_id               varchar(64)   not null                  comment '爱发电方案 ID',
    custom_order_id       varchar(128)  not null                  comment '外部程序自定义订单号 (Webhook 匹配)',
    callback_url          varchar(512)  not null                  comment '支付成功回调地址',
    status                tinyint       not null default 0        comment '状态: 0=PENDING 1=PAID 2=CALLBACK_SUCCESS 3=EXPIRED 4=FAILED',
    out_trade_no          varchar(64)   null                      comment '爱发电订单号 (支付后由 Webhook 填充)',
    signature_secret      varchar(64)   not null                  comment '回调签名密钥 (Base64)',
    total_amount          decimal(10,2) null                      comment '预期支付金额',
    remark                varchar(256)  null                      comment '备注',
    callback_retry_count  int           not null default 0        comment '回调重试次数',
    callback_last_attempt datetime      null                      comment '最近一次回调尝试时间',
    callback_error        varchar(512)  null                      comment '最近回调失败原因',
    created_at            timestamp     default current_timestamp comment '创建时间',
    updated_at            timestamp     default current_timestamp on update current_timestamp comment '更新时间',

    constraint uk_request_id      unique (request_id),
    constraint uk_custom_order_id unique (custom_order_id)
) engine = InnoDB
  default charset = utf8mb4
  collate = utf8mb4_unicode_ci
    comment '外部程序支付请求表';

create index idx_pr_status       on payment_requests (status);
create index idx_pr_out_trade_no on payment_requests (out_trade_no);
create index idx_pr_user_id      on payment_requests (user_id);
create index idx_pr_created_at   on payment_requests (created_at);
