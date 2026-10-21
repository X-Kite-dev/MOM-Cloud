package com.kitex.datasource;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.kitex.common.core.util.ClassUtils;
import com.kitex.datasource.service.DataSourceRoutingService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

/**
 * 基于包路径的数据源路由AOP切面
 * 根据不同的包路径自动切换到对应的数据源
 */
@Aspect
@Component
@Order(-1)
public class PackagePathDataSourceAspect {

    private static final Logger log = LoggerFactory.getLogger(PackagePathDataSourceAspect.class);

    @Autowired
    private DataSourceRoutingService routingService;

    /**
     * 切入点：拦截所有业务模块的Service层、Mapper层和实现类方法
     */
    @Around("execution(* com.kitex.mom..service..*.*(..)) || " +
            "execution(* com.kitex.mom..mapper..*.*(..)) || " +
            "execution(* com.kitex.mom..impl..*.*(..))")
    public Object aroundDataSource(ProceedingJoinPoint joinPoint) throws Throwable {
        // 获取方法签名和目标对象
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Object target = joinPoint.getTarget();
        String methodName = signature.getName();

        // 检查方法或类上是否有@DS注解，如果有则优先使用注解配置
        DS dsAnnotation = getDSAnnotation(joinPoint, signature);
        if (dsAnnotation != null) {
            log.debug("Method {}.{} has @DS annotation, using datasource: {}",
                    signature.getDeclaringTypeName(), methodName, dsAnnotation.value());
            return joinPoint.proceed();
        }

        // 获取实际业务类（接口或实现类）的完整类名，处理动态代理情况
        String actualClassName = getActualClassName(target, signature);

        // 根据包路径确定数据源
        String datasource = determineDatasourceByPackage(actualClassName);
        if (datasource != null) {
            log.debug("Switching to datasource: {} for {}.{}", datasource, actualClassName, methodName);
            DynamicDataSourceContextHolder.push(datasource);
            try {
                return joinPoint.proceed();
            } finally {
                DynamicDataSourceContextHolder.clear();
            }
        } else {
            log.debug("No specific datasource found for {}, using default", actualClassName);
            return joinPoint.proceed();
        }
    }

    /**
     * 获取方法或类上的@DS注解，方法注解优先于类注解
     */
    private DS getDSAnnotation(ProceedingJoinPoint joinPoint, MethodSignature signature) {
        // 检查方法上是否有@DS注解
        DS methodAnnotation = signature.getMethod().getAnnotation(DS.class);
        if (methodAnnotation != null) {
            return methodAnnotation;
        }

        // 检查类上是否有@DS注解（处理代理对象）
        Class<?> targetClass = getActualClass(joinPoint.getTarget(), signature);
        return targetClass.getAnnotation(DS.class);
    }

    /**
     * 获取实际业务类（接口或实现类）的完整类名
     */
    private String getActualClassName(Object target, MethodSignature signature) {
        Class<?> actualClass = getActualClass(target, signature);
        return actualClass.getName();
    }

    /**
     * 获取实际业务类（接口或实现类）的Class对象，处理各种代理情况
     */
    private Class<?> getActualClass(Object target, MethodSignature signature) {
        // 处理JDK动态代理
        if (Proxy.isProxyClass(target.getClass())) {
            try {
                // 获取代理对象的InvocationHandler
                InvocationHandler handler = Proxy.getInvocationHandler(target);

                // 尝试从Spring的代理中获取目标对象
                Class<?> targetClass = getTargetClassFromSpringProxy(handler);
                if (targetClass != null) {
                    return targetClass;
                }

                // 如果无法获取目标对象，使用接口类作为判断依据
                Class<?>[] interfaces = target.getClass().getInterfaces();
                if (interfaces.length > 0) {
                    return interfaces[0];
                }
            } catch (Exception e) {
                log.warn("Failed to get actual class from JDK proxy: {}", e.getMessage());
            }
        }
        // 处理CGLIB代理（获取原始父类）
        else if (ClassUtils.isCglibProxy(target)) {
            Class<?> superClass = target.getClass().getSuperclass();
            if (superClass != null && superClass != Object.class) {
                return superClass;
            }
        }

        // 如果不是代理对象，直接使用声明方法的类（可能是接口或实现类）
        return signature.getDeclaringType();
    }

    /**
     * 从Spring的动态代理中获取目标类
     */
    private Class<?> getTargetClassFromSpringProxy(InvocationHandler handler) throws Exception {
        // 检查是否是Spring的JdkDynamicAopProxy
        if ("org.springframework.aop.framework.JdkDynamicAopProxy".equals(handler.getClass().getName())) {
            Field advisedField = handler.getClass().getDeclaredField("advised");
            advisedField.setAccessible(true);
            Object advised = advisedField.get(handler);

            Field targetSourceField = advised.getClass().getDeclaredField("targetSource");
            targetSourceField.setAccessible(true);
            Object targetSource = targetSourceField.get(advised);

            // 调用getTarget()方法获取真实目标对象
            Object target = targetSource.getClass().getMethod("getTarget").invoke(targetSource);
            return target.getClass();
        }
        return null;
    }

    /**
     * 根据包路径确定数据源
     */
    private String determineDatasourceByPackage(String className) {
        return routingService.determineDatasourceByPackage(className);
    }
}