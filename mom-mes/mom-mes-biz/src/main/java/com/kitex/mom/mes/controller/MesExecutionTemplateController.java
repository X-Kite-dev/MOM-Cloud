package com.kitex.mom.mes.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.mes.dto.MesExecutionContractDTO;
import com.kitex.mom.mes.dto.MesIndustryExecutionPresetDTO;
import com.kitex.mom.mes.dto.MesIdentifierNodeReadinessDTO;
import com.kitex.mom.mes.enums.MesReportChannelEnum;
import com.kitex.mom.mes.entity.MesExecutionTemplateEntity;
import com.kitex.mom.mes.service.MesExecutionTemplateService;
import com.kitex.mom.mes.service.impl.MesIndustryExecutionPresetCatalog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 生产执行模板配置、发布和派工冻结契约查询。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesExecutionTemplate")
@Tag(name = "生产执行模板", description = "离散制造报工模式与现场控制的版本化配置")
public class MesExecutionTemplateController {

    private final MesExecutionTemplateService service;
    private final MesIndustryExecutionPresetCatalog presetCatalog;

    @GetMapping("/node-readiness")
    @Operation(summary = "查询动态节点实时部署或精确入口诊断", description = "只读；不执行SQL、不写派工、不替代现场验收或实际生产门")
    @PreAuthorize("@pms.hasPermission('mesExecutionTemplate:show','mesExecutionTemplate:publish','mesTaskReport:report','mesStationPass:report','mesTaskReport:integration','mesAutoReport:event','mesAutoReport:retry')")
    public R<MesIdentifierNodeReadinessDTO> nodeReadiness(@RequestParam(required = false) Long templateId,
                                                       @RequestParam(required = false) String arrangeCode,
                                                       @RequestParam(required = false) MesReportChannelEnum channel) {
        return R.ok(service.nodeReadiness(templateId, arrangeCode, channel));
    }

    @GetMapping("/industry-presets")
    @Operation(summary = "查询常见离散行业执行模板建议",
            description = "只返回配置起点，不直接发布；应用后必须补充真实作用域并验证")
    @PreAuthorize("@pms.hasPermission('mesExecutionTemplate:show')")
    public R<List<MesIndustryExecutionPresetDTO>> industryPresets() {
        return R.ok(presetCatalog.list());
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询生产执行模板")
    @PreAuthorize("@pms.hasPermission('mesExecutionTemplate:show')")
    public R<IPage<MesExecutionTemplateEntity>> page(Page<MesExecutionTemplateEntity> page,
                                                     MesExecutionTemplateEntity query) {
        return R.ok(service.queryPage(page, query));
    }

    @PostMapping("/draft")
    @SysLog("保存生产执行模板草稿")
    @Operation(summary = "新增或修改草稿", description = "已发布版本不可原地修改")
    @PreAuthorize("@pms.hasPermission('mesExecutionTemplate:edit')")
    public R<MesExecutionTemplateEntity> saveDraft(@RequestBody MesExecutionTemplateEntity draft) {
        return R.ok(service.saveDraft(draft));
    }

    @PostMapping("/copy")
    @SysLog("复制生产执行模板新版本")
    @Operation(summary = "复制为下一个草稿版本")
    @PreAuthorize("@pms.hasPermission('mesExecutionTemplate:edit')")
    public R<MesExecutionTemplateEntity> copy(@RequestParam Long id) {
        return R.ok(service.copyVersion(id));
    }

    @PostMapping("/publish")
    @SysLog("发布生产执行模板")
    @Operation(summary = "发布草稿版本", description = "发布后只影响新派工，不改写在制派工快照")
    @PreAuthorize("@pms.hasPermission('mesExecutionTemplate:publish')")
    public R<MesExecutionTemplateEntity> publish(@RequestParam Long id) {
        return R.ok(service.publish(id));
    }

    @PostMapping("/retire")
    @SysLog("退役生产执行模板")
    @Operation(summary = "退役已发布版本", description = "已冻结到派工的版本仍按原快照执行")
    @PreAuthorize("@pms.hasPermission('mesExecutionTemplate:publish')")
    public R<MesExecutionTemplateEntity> retire(@RequestParam Long id) {
        return R.ok(service.retire(id));
    }

    @GetMapping("/contract")
    @Operation(summary = "查询派工执行契约", description = "管理端、工站端和移动端使用同一冻结契约")
    public R<MesExecutionContractDTO> contract(@RequestParam String arrangeCode) {
        return R.ok(service.contract(arrangeCode));
    }
}
