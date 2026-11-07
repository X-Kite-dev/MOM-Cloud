package com.kitex.mom.common.barcode.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.query.enums.CommonQuery;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.mom.common.barcode.dto.FrozenBarcodeDefinition;
import com.kitex.mom.common.barcode.dto.req.BarcodeDefinitionFreezeReq;
import com.kitex.mom.common.barcode.dto.req.BarcodeRuleReq;
import com.kitex.mom.common.barcode.entity.BarcodeRuleEntity;
import com.kitex.mom.common.barcode.plugin.BarcodePlugin;
import com.kitex.mom.common.barcode.service.BarcodeRuleService;
import com.kitex.mom.common.barcode.service.FrozenBarcodeDefinitionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 条码核心表
 *
 * @author kitex
 * @date 2023-09-01 14:45:01
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/barcodeRule")
@Tag(description = "barcodeRule", name = "条码核心表管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class BarcodeRuleController {

    private final BarcodeRuleService barcodeRuleService;

	private final FrozenBarcodeDefinitionService frozenDefinitions;

	/**
	 * 读取当前规则的一致原定义，业务域负责随执行实例持久保存，后续按原定义编码。
	 * @param request 当前实际工厂内的条码规则编码
	 * @return 包含组织、确定字段顺序和原文指纹的快照；无权限或规则不兼容时拒绝
	 */
	@Operation(summary = "冻结条码定义", description = "实名核工厂读权限后读取一致原定义，不分配条码")
	@PostMapping("/freezeDefinition")
	@PreAuthorize("@pms.hasPermission('barcode_barcodeRule_view')")
	public R<FrozenBarcodeDefinition> freezeDefinition(@RequestBody @Valid BarcodeDefinitionFreezeReq request) {
		return R.ok(frozenDefinitions.freezeDefinition(request));
	}

    /**
     * 分页查询
     *
     * @param page           分页对象
     * @param barcodeRuleReq 条码核心表
     * @return
     */
    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('barcode_barcodeRule_view')")
    public R<Page<BarcodeRuleEntity>> searchPage(SearchPage page, BarcodeRuleReq barcodeRuleReq, CommonQuery commonQuery) {
        QueryWrapper<BarcodeRuleEntity> queryWrapper = QueryGenerator.initQueryWrapper(barcodeRuleReq, commonQuery);
        return R.ok(barcodeRuleService.page(page.toMpPage(), queryWrapper));
    }

    /**
     * 查询条码规则列表
     *
     * @param barcodeRuleReq
     * @param commonQuery
     * @return
     */
    @Operation(summary = "查询列表", description = "查询列表")
    @GetMapping("/list")
    public R<List<BarcodeRuleEntity>> list(BarcodeRuleReq barcodeRuleReq, CommonQuery commonQuery) {
        QueryWrapper<BarcodeRuleEntity> queryWrapper = QueryGenerator.initQueryWrapper(barcodeRuleReq, commonQuery);
        return R.ok(barcodeRuleService.list(queryWrapper));
    }

    /**
     * 通过id查询条码核心表
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('barcode_barcodeRule_view')")
    public R<BarcodeRuleReq> getById(@PathVariable("id") Long id) {
        return R.ok(barcodeRuleService.getInfoById(id));
    }

    /**
     * 新增条码核心表
     *
     * @param barcodeRuleReq 条码核心表
     * @return R
     */
    @Operation(summary = "新增条码核心表", description = "新增条码核心表")
    @SysLog("新增条码核心表")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('barcode_barcodeRule_add')")
    public R<Boolean> save(@RequestBody @Valid BarcodeRuleReq barcodeRuleReq) {
        return R.ok(barcodeRuleService.saveTemplate(barcodeRuleReq));
    }

    /**
     * 修改条码核心表
     *
     * @param barcodeRuleReq 条码核心表
     * @return R
     */
    @Operation(summary = "修改条码核心表", description = "修改条码核心表")
    @SysLog("修改条码核心表")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('barcode_barcodeRule_edit')")
    public R<Boolean> updateById(@RequestBody BarcodeRuleReq barcodeRuleReq) {
        return R.ok(barcodeRuleService.updateById(barcodeRuleReq));
    }

    /**
     * 通过id删除条码核心表
     *
     * @param ids id
     * @return R
     */
    @Operation(summary = "通过id删除条码核心表", description = "通过id删除条码核心表")
    @SysLog("通过id删除条码核心表")
    @DeleteMapping()
    @PreAuthorize("@pms.hasPermission('barcode_barcodeRule_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(barcodeRuleService.removeBatchByIds(CollUtil.toList(ids)));
    }

    /**
     * 导出excel 表格
     *
     * @param barcodeRule 查询条件
     * @return excel 文件流
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('barcode_barcodeRule_export')")
    public List<BarcodeRuleEntity> export(BarcodeRuleEntity barcodeRule) {
        return barcodeRuleService.list(Wrappers.query(barcodeRule));
    }

    /**
     * 生成条码
     *
     * @param jsonObject      条码内容
     * @param barcodeRuleCode 条码规则编码
     * @return
     */
    @PostMapping("/genBarcode")
    public R<String> genBarcode(@RequestBody JSONObject jsonObject, String barcodeRuleCode) {
        return R.ok(barcodeRuleService.genBarcode(jsonObject, barcodeRuleCode));
    }

    /**
     * 识别条码
     *
     * @param code
     * @return
     */
    @GetMapping("/rander")
    public R rander(String code) {
        return R.ok(barcodeRuleService.rander(code));
    }

    @GetMapping("/genBarcodePluginList")
    public R genBarcodePluginList() {
        Map<String, BarcodePlugin> beansOfType = SpringUtil.getBeansOfType(BarcodePlugin.class);
        List<Map<String, String>> labelAndValue = new ArrayList<>();
        beansOfType.forEach((k, v) -> {
            Map<String, String> build = MapUtil.<String, String>builder()
                    .put("name", v.getName())
                    .put("value", k)
                    .put("regular", v.getRegular())
                    .build();
            labelAndValue.add(build);
        });
        return R.ok(labelAndValue);
    }

}
