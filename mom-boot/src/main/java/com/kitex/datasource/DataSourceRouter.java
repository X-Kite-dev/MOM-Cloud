package com.kitex.datasource;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.kitex.datasource.service.DataSourceRoutingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 数据源路由工具类
 * 提供手动切换数据源的功能
 */
@Component
public class DataSourceRouter {

    private static final Logger log = LoggerFactory.getLogger(DataSourceRouter.class);

    @Autowired
    private DataSourceRoutingService routingService;

    /**
     * 切换到指定数据源
     */
    public static void switchTo(String datasource) {
        log.debug("Switching to datasource: {}", datasource);
        DynamicDataSourceContextHolder.push(datasource);
    }

    /**
     * 切换到MDM数据源
     */
    public static void switchToMdm() {
        switchTo("mdm");
    }

    /**
     * 切换到MES数据源
     */
    public static void switchToMes() {
        switchTo("mes");
    }

    /**
     * 切换到WMS数据源
     */
    public static void switchToWms() {
        switchTo("wms");
    }

    /**
     * 切换到QMS数据源
     */
    public static void switchToQms() {
        switchTo("qms");
    }

    /**
     * 切换到TPM数据源
     */
    public static void switchToTpm() {
        switchTo("tpm");
    }

    /**
     * 切换到PLAN数据源
     */
    public static void switchToPlan() {
        switchTo("plan");
    }

    /**
     * 切换到PMS数据源
     */
    public static void switchToPms() {
        switchTo("pms");
    }

    /**
     * 切换到IOT数据源
     */
    public static void switchToIot() {
        switchTo("iot");
    }

    /**
     * 切换到主数据源
     */
    public static void switchToMaster() {
        switchTo("master");
    }

    /**
     * 清除数据源上下文
     */
    public static void clear() {
        DynamicDataSourceContextHolder.clear();
    }

    /**
     * 获取当前数据源
     */
    public static String getCurrentDatasource() {
        return DynamicDataSourceContextHolder.peek();
    }


    /**
     * 在指定数据源中执行任务
     */
    public static <T> T executeInDatasource(String datasource, DataSourceTask<T> task) {
        try {
            switchTo(datasource);
            return task.execute();
        } finally {
            clear();
        }
    }


    /**
     * 数据源任务接口
     */
    @FunctionalInterface
    public interface DataSourceTask<T> {
        T execute();
    }
}