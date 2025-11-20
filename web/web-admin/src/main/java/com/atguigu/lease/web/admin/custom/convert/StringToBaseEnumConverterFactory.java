package com.atguigu.lease.web.admin.custom.convert;

import com.atguigu.lease.model.enums.BaseEnum;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;
import org.springframework.stereotype.Component;

@Component
public class StringToBaseEnumConverterFactory implements ConverterFactory<String, BaseEnum> {
    @Override
    public <T extends BaseEnum> Converter<String, T> getConverter(Class<T> targetType) {
        return new Converter<String, T>() {
            @Override
            public T convert(String code) {
                // 获取targetType中目标枚举类的所有实例
                // 如果targetType是ItemType.class，那么enumConstants数组就会包含[ItemType.APARTMENT, ItemType.ROOM]
                T[] enumConstants = targetType.getEnumConstants();


                for (T enumConstant : enumConstants) {
                    if (enumConstant.getCode().equals(Integer.valueOf(code))) {
                        return enumConstant;
                    }
                }
                throw new IllegalArgumentException("No enum constant 非法 " + targetType.getCanonicalName() + " with code " + code);
            }
        };
    }
}
