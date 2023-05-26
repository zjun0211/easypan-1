package com.easypan.utils;

import org.springframework.beans.BeanUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 利用spring提供的BeenUtils工具类实现集合元素映射以及单个对象映射
 */
public class CopyTools {
    public static <T, S> List<T> copyList(List<S> list, Class<T> clazz) {
        return list.stream().map(s -> {
            T t = null;
            try {
                t = clazz.newInstance();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            BeanUtils.copyProperties(s, t);
            return t;
        }).collect(Collectors.toList());
        /*List<T> list = new ArrayList<T>();
        for (S s : sList) {
            T t = null;
            try {
                t = clazz.newInstance();
            } catch (Exception e) {
                e.printStackTrace();
            }
            BeanUtils.copyProperties(s, t);
            list.add(t);
        }
        return list;
         */
    }

    public static <T, S> T copy(S s, Class<T> clazz) {
        T t = null;
        try {
            t = clazz.newInstance();
        } catch (Exception e) {
            e.printStackTrace();
        }
        BeanUtils.copyProperties(s, t);
        return t;
    }
}
