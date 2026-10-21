package com.kitex.mom.mes.extension;

import com.kitex.mom.mes.entity.MesTaskReportEntity;

import java.util.Map;

/**
 * 行业能力包的生产报工扩展点。
 *
 * <p>扩展只能增加校验、证据和与主报工同事务的行业事实，不能替换五种核心执行器，也不能声明
 * 核心门禁已通过。实现类应作为Spring Bean安装，并使用稳定编码和版本。</p>
 */
public interface MesReportExtension {
    String code();

    default String version() {
        return "1";
    }

    default int order() {
        return 0;
    }

    default boolean supports(MesReportExtensionContext context) {
        return true;
    }

    /**
     * 报工主事务执行前进行行业附加校验，失败应抛出业务异常并整体回滚。
     */
    void validate(MesReportExtensionContext context);

    /**
     * 返回需要冻结到报工事实中的简要证据，不得包含密钥或无界原始报文。
     */
    default Map<String, Object> evidence(MesReportExtensionContext context) {
        return Map.of();
    }

    /**
     * 主报工记录落账后追加行业事实；任何异常都会回滚整次报工。
     */
    default void afterPersist(MesReportExtensionContext context, MesTaskReportEntity report) {
    }
}
