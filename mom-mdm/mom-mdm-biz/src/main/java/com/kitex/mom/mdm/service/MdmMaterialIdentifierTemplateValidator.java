package com.kitex.mom.mdm.service;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.common.identifier.IdentifierCodeTemplate;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;

/**
 * 保存物料前校验可选批次和单件条码模板，不影响手工编码流程。
 */
public final class MdmMaterialIdentifierTemplateValidator {
    private MdmMaterialIdentifierTemplateValidator() {
    }

    public static void validate(MdmMaterialEntity material) {
        if (material == null) {
            return;
        }
        if (material.getBatchCodeTemplate() != null) {
            material.setBatchCodeTemplate(normalize(material.getBatchCodeTemplate(), "批次"));
        }
        if (material.getSerialCodeTemplate() != null) {
            material.setSerialCodeTemplate(normalize(material.getSerialCodeTemplate(), "单件条码"));
        }
    }

    private static String normalize(String input, String kind) {
        String template = StrUtil.trim(input);
        if (StrUtil.isBlank(template)) {
            return "";
        }
        try {
            IdentifierCodeTemplate.validate(template);
        } catch (IllegalArgumentException exception) {
            throw new CheckedException(kind + "模板无效: " + exception.getMessage());
        }
        return template;
    }
}
