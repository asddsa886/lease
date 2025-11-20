package com.atguigu.lease.web.admin.custom.convert;

import com.atguigu.lease.model.enums.ItemType;
import io.micrometer.common.lang.NonNull;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class StringToItemTypeConverter implements Converter<String, ItemType> {
    @Override
    public ItemType convert(@NonNull String code) {
        ItemType[] values = ItemType.values();
        for (ItemType itemType : values) {
            if (itemType.getCode().equals(Integer.valueOf(code))){
                return itemType;
            }
        }
        throw new IllegalArgumentException("code"+code+"非法");
    }
}
