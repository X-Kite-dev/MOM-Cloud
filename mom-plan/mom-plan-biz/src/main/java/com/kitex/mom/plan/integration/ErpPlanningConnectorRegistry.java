package com.kitex.mom.plan.integration;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** ERP 连接器白名单注册表。 */
@Component
@RequiredArgsConstructor
public class ErpPlanningConnectorRegistry {
    private final List<ErpPlanningConnector> connectors;

    /** 只返回代码匹配的已安装连接器；未安装时调用方必须保留阻断状态。 */
    public Optional<ErpPlanningConnector> find(String erpSystemCode) {
        return connectors.stream().filter(item -> item.supports(erpSystemCode)).findFirst();
    }
}
