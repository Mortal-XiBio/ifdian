package com.ifdain.entity;

import javax.persistence.AttributeConverter;
import javax.persistence.Converter;

/**
 * JPA {@link AttributeConverter} — 自动将 {@link OrderStatus} 映射为数据库整型值
 *
 * <p>使用 {@code @Converter(autoApply = true)} 全局生效，
 * 所有出现 {@code OrderStatus} 类型的字段自动转换。</p>
 */
@Converter(autoApply = true)
public class OrderStatusConverter implements AttributeConverter<OrderStatus, Integer> {

    @Override
    public Integer convertToDatabaseColumn(OrderStatus attribute) {
        return attribute == null ? null : attribute.getValue();
    }

    @Override
    public OrderStatus convertToEntityAttribute(Integer dbData) {
        return dbData == null ? null : OrderStatus.fromValue(dbData);
    }
}
