package com.kitex.mom.common.barcode.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.common.barcode.dto.req.PreviewObj;
import com.kitex.mom.common.barcode.entity.PrintTemplateEntity;
import com.kitex.mom.common.barcode.mapper.PrintTemplateMapper;
import com.kitex.mom.common.barcode.service.PrintTemplateService;
import com.kitex.mom.common.barcode.template.HtmlTemplate;
import com.kitex.mom.common.barcode.utils.VelocityKit;
import org.springframework.stereotype.Service;

/**
 * 打印模板
 *
 * @author kitex
 * @date 2023-08-17 16:26:53
 */
@Service
public class PrintTemplateServiceImpl extends ServiceImpl<PrintTemplateMapper, PrintTemplateEntity>
        implements PrintTemplateService {

    @Override
    public String preview(String code) {

        PrintTemplateEntity template = getByCode(code);
        if (template == null) {
            throw new CheckedException("模板为空不能预览");
        }

        String html = HtmlTemplate.getHtml();
        String jsonStr = VelocityKit.templateJsonStr(template.getContent(), template.getJsonData());
        return VelocityKit.renderStr(html, jsonStr);
    }

    public PrintTemplateEntity getByCode(String code) {
        return getOne(Wrappers.<PrintTemplateEntity>lambdaQuery().eq(PrintTemplateEntity::getCode, code));
    }

    @Override
    public String billPreview(PreviewObj obj) {
        PrintTemplateEntity template = getByCode(obj.getTemplateCode());
        if (template == null) {
            throw new CheckedException("请先维护模板");
        }
        String html = HtmlTemplate.getHtml();
        String jsonStr = VelocityKit.templateJsonStr(template.getContent(), obj.getJsonData());
        return VelocityKit.renderStr(html, jsonStr);
    }

}