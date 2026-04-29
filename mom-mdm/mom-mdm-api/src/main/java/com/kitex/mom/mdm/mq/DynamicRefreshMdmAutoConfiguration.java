//package com.kitex.mom.mdm.mq;
//
//
//import com.kitex.mom.mdm.Constants;
//import cn.hutool.json.JSONUtil;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
//import org.springframework.context.annotation.Bean;
//import org.springframework.data.redis.connection.RedisConnectionFactory;
//import org.springframework.data.redis.listener.ChannelTopic;
//import org.springframework.data.redis.listener.RedisMessageListenerContainer;
//import org.springframework.data.redis.serializer.StringRedisSerializer;
//
//@Slf4j
//public class DynamicRefreshMdmAutoConfiguration {
//
//
//    /**
//     * redis 监听配置
//     *
//     * @param redisConnectionFactory redis 配置
//     * @return
//     */
//    @Bean
//    public RedisMessageListenerContainer refreshMdmRedisContainer(RedisConnectionFactory redisConnectionFactory, DynamicRefreshMdm dynamicRefreshMdm) {
//        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
//        container.setConnectionFactory(redisConnectionFactory);
//
//        StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();
//
//        container.addMessageListener((message, bytes) -> {
//            log.warn("接收到重新JVM 重新加载路由事件");
//
//            String deserialize = stringRedisSerializer.deserialize(message.getBody());
//            RefreshMdmEntity bean = JSONUtil.toBean(deserialize, RefreshMdmEntity.class);
//
/// /            RefreshMdmEntity bean = BeanUtil.toBean(deserialize, RefreshMdmEntity.class);
//
//
//            dynamicRefreshMdm.refreshMdm(bean);
//            // 发送刷新路由事件
//        }, new ChannelTopic(Constants.MDM_REFRESH_RELOAD_TOPIC));
//        return container;
//    }
//
//
//    @Bean
//    @ConditionalOnMissingBean(DynamicRefreshMdm.class)
//    public DynamicRefreshMdm getDynamicRefreshMdm() {
//        return new DefaultDynamicRefreshMdm();
//    }
//
//}
