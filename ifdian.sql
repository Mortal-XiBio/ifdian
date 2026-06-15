-- ============================================================
-- 爱发电 (afdian.net) 订单数据库 Schema
-- 兼容模式: standalone (独立部署) / embedded (嵌入宿主)
-- 字符集: utf8mb4_unicode_ci (排序更准确)
-- 备选字符集: utf8mb4_general_ci (性能略优)
-- ============================================================

-- 基础表：所有模式共享
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

    -- 扩展字段
    pay_time          datetime                            null            comment '支付时间 (Webhook 到达时间或主动查询填充)',
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
create index idx_ifdian_pay_time          on ifdian_orders (pay_time);
create index idx_ifdian_plan_id           on ifdian_orders (plan_id);
create index idx_ifdian_user_id           on ifdian_orders (user_id);
create index idx_ifdian_processed         on ifdian_orders (processed);
create index idx_ifdian_created_at        on ifdian_orders (created_at);
