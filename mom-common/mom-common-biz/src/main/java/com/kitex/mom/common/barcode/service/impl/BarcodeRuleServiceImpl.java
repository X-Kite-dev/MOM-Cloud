package com.kitex.mom.common.barcode.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.extra.template.Template;
import cn.hutool.extra.template.TemplateConfig;
import cn.hutool.extra.template.TemplateEngine;
import cn.hutool.extra.template.TemplateUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.ValidateCodeException;
import com.kitex.mom.common.barcode.dto.req.BarcodeBusinessFieldReq;
import com.kitex.mom.common.barcode.dto.req.BarcodeRuleReq;
import com.kitex.mom.common.barcode.entity.BarcodeBusinessFieldEntity;
import com.kitex.mom.common.barcode.entity.BarcodeCheckLogEntity;
import com.kitex.mom.common.barcode.entity.BarcodeRuleEntity;
import com.kitex.mom.common.barcode.mapper.BarcodeRuleMapper;
import com.kitex.mom.common.barcode.plugin.BarcodePlugin;
import com.kitex.mom.common.barcode.service.BarcodeBusinessFieldService;
import com.kitex.mom.common.barcode.service.BarcodeCheckLogService;
import com.kitex.mom.common.barcode.service.BarcodeRuleService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 条码核心表
 *
 * @author kitex
 * @date 2023-09-01 14:45:01
 */
@Service
@AllArgsConstructor
public class BarcodeRuleServiceImpl extends ServiceImpl<BarcodeRuleMapper, BarcodeRuleEntity>
        implements BarcodeRuleService {

    private final BarcodeBusinessFieldService barcodeBusinessFieldService;

    private final Map<String, BarcodePlugin> barcodePluginMap;

    private final BarcodeCheckLogService barcodeCheckLogService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveTemplate(BarcodeRuleReq barcodeRuleReq) {
        List<BarcodeBusinessFieldReq> fieldReqList = barcodeRuleReq.getBusinessReqList();
        BarcodeRuleEntity barcodeRule = new BarcodeRuleEntity();
        BeanUtil.copyProperties(barcodeRuleReq, barcodeRule);
        this.save(barcodeRule);
        List<BarcodeBusinessFieldEntity> fieldList = fieldReqList.stream().map(item -> {
            item.setBarcodeRuleId(barcodeRule.getId());
            BarcodeBusinessFieldEntity barcodeBusinessField = new BarcodeBusinessFieldEntity();
            BeanUtil.copyProperties(item, barcodeBusinessField);
            barcodeBusinessField.setFactoryCode(barcodeRule.getFactoryCode());
            return barcodeBusinessField;
        }).collect(Collectors.toList());
        barcodeBusinessFieldService.saveBatch(fieldList);
        return Boolean.TRUE;

    }

    @Override
    public BarcodeRuleReq getInfoById(Long id) {
        BarcodeRuleEntity template = this.getById(id);
        BarcodeRuleReq barcodeRuleReq = new BarcodeRuleReq();
        BeanUtil.copyProperties(template, barcodeRuleReq);
        List<BarcodeBusinessFieldEntity> list = barcodeBusinessFieldService
                .list(Wrappers.<BarcodeBusinessFieldEntity>lambdaQuery().eq(BarcodeBusinessFieldEntity::getBarcodeRuleId, id));
        List<BarcodeBusinessFieldReq> fieldReqList = list.stream().map(item -> {
            BarcodeBusinessFieldReq barcodeBusinessFieldReq = new BarcodeBusinessFieldReq();
            BeanUtil.copyProperties(item, barcodeBusinessFieldReq);
            return barcodeBusinessFieldReq;
        }).collect(Collectors.toList());
        barcodeRuleReq.setBusinessReqList(fieldReqList);
        return barcodeRuleReq;
    }

    /**
     * 更新父规则并替换业务字段，必须在同一事务内完成，避免冻结读取到中间定义。
     *
     * @param barcodeRuleReq 规则及完整业务字段
     * @return 更新结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateById(BarcodeRuleReq barcodeRuleReq) {
        if (barcodeRuleReq.getId() == null) {
            throw new ValidateCodeException("id不能为空");
        }
        List<BarcodeBusinessFieldReq> fieldReqList = barcodeRuleReq.getBusinessReqList();
        if (CollUtil.isEmpty(fieldReqList)) {
            throw new ValidateCodeException("业务字段不能为空");
        }
        BarcodeRuleEntity barcodeRule = new BarcodeRuleEntity();
        BeanUtil.copyProperties(barcodeRuleReq, barcodeRule);
        if (!this.updateById(barcodeRule)) {
            throw new ValidateCodeException("条码原规则更新失败，请重新读取");
        }
        barcodeBusinessFieldService.remove(Wrappers.<BarcodeBusinessFieldEntity>lambdaQuery()
                .eq(BarcodeBusinessFieldEntity::getBarcodeRuleId, barcodeRuleReq.getId()));
        List<BarcodeBusinessFieldEntity> fieldList = fieldReqList.stream().map(item -> {
            item.setBarcodeRuleId(barcodeRule.getId());
            BarcodeBusinessFieldEntity barcodeBusinessField = new BarcodeBusinessFieldEntity();
            BeanUtil.copyProperties(item, barcodeBusinessField);
            barcodeBusinessField.setId(null);
            barcodeBusinessField.setFactoryCode(barcodeRule.getFactoryCode());
            return barcodeBusinessField;
        }).collect(Collectors.toList());
        if (!barcodeBusinessFieldService.saveBatch(fieldList)) {
            throw new ValidateCodeException("条码业务字段保存失败，整笔规则更新回滚");
        }
        return Boolean.TRUE;
    }

    /**
     * 根据模板中的正则去匹配对应的条码， 根据条码配置的插件进行解析 解析后返回解析出来的结果
     *
     * @param code 条码
     * @return
     */
    @Override
    public Object rander(String code) {
        BarcodeCheckLogEntity barcodeCheckLog = new BarcodeCheckLogEntity();
        barcodeCheckLog.setBarcode(code);

        List<BarcodeRuleEntity> list = list();
        List<BarcodeRuleEntity> collect = list.stream().filter(item -> {
            String regular = item.getRegular();
            Pattern pattern = Pattern.compile(regular);
            Matcher matcher = pattern.matcher(code);
            return matcher.matches();
        }).collect(Collectors.toList());
        if (CollUtil.isEmpty(collect)) {
            barcodeCheckLog.setResult("无法匹配对应的条码模板");
            barcodeCheckLogService.save(barcodeCheckLog);
            throw new ValidateCodeException("无法匹配对应的条码模板");
        }
        BarcodeRuleEntity barcodeRule = collect.get(0);
        // 获取到bean的全路径名称
        BarcodeRuleReq templateReq = getInfoById(barcodeRule.getId());
        BarcodePlugin barcodePlugin = barcodePluginMap.get(barcodeRule.getPluginPath());
        barcodePlugin.setTemplate(templateReq);
        Map<String, String> render = barcodePlugin.render(code);
        List<BarcodeBusinessFieldReq> businessReqList = templateReq.getBusinessReqList();

        barcodeCheckLog.setJsonData(JSONUtil.parse(render).toString());
        barcodeCheckLog.setBarcodeName(barcodeRule.getName());
        barcodeCheckLog.setBarcodeRule(barcodeRule.getRegular());
        barcodeCheckLog.setBarcodeCode(barcodeRule.getCode());
        barcodeCheckLog.setResult("匹配成功");
        barcodeCheckLog.setFactoryCode(barcodeRule.getFactoryCode());
        barcodeCheckLogService.save(barcodeCheckLog);

        return MapUtil.builder().put("data", render).put("schema", businessReqList).build();
    }

    /**
     * 根据模板对的业务字段，进行构造对应的内容
     *
     * @param jsonObject
     * @param templateCode
     * @return
     */
    @Override
    public String genBarcode(JSONObject jsonObject, String barcodeRuleCode) {
        BarcodeRuleEntity barcodeRule = getOne(
                Wrappers.<BarcodeRuleEntity>lambdaQuery().eq(BarcodeRuleEntity::getCode, barcodeRuleCode));

        List<BarcodeBusinessFieldEntity> list = barcodeBusinessFieldService.list(Wrappers.<BarcodeBusinessFieldEntity>lambdaQuery()
                .eq(BarcodeBusinessFieldEntity::getBarcodeRuleId, barcodeRule.getId()));

        TemplateEngine engine = TemplateUtil.createEngine(new TemplateConfig());

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            BarcodeBusinessFieldEntity barcodeBusinessField = list.get(i);
            if (i == 0) {
                sb.append(String.format("${%s}", barcodeBusinessField.getValue()));
            } else {
                sb.append(String.format("/${%s}", barcodeBusinessField.getValue()));
            }
        }
        // 假设我们引入的是Beetl引擎，则：
        Template template = engine.getTemplate(sb.toString());

        return template.render(jsonObject);
    }

}
