package com.kitex.mom.mdm.utils;

import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.json.JSONUtil;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.mq.RefreshMdmEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 动态刷新缓存工具类
 */
@Slf4j
public class DynamicRefreshUtils {


    /**
     * 通过redis 的发布订阅进行缓存刷新
     * 进行redis 消息发布
     *
     * @param obj
     */
    public static void refreshMdm(Object obj) {

        RedisTemplate redisTemplate = SpringUtil.getBean(RedisTemplate.class);

        redisTemplate.setValueSerializer(new StringRedisSerializer());
        // 通知网关重置路由


        RefreshMdmEntity refreshMdmEntity = new RefreshMdmEntity();
        refreshMdmEntity.setType(obj.getClass().getName());
        refreshMdmEntity.setObj(obj);
        String str = JSONUtil.toJsonStr(refreshMdmEntity);
        log.info("刷新数据：{}", str);


        redisTemplate.convertAndSend(Constants.MDM_REFRESH_RELOAD_TOPIC, str);
    }

}
