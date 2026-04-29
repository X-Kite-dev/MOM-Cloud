package com.kitex.mom.mdm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.annotation.RequestExcel;
import com.kitex.common.excel.annotation.ResponseExcel;
import com.kitex.mom.mdm.entity.MdmStationSettingEntity;
import com.kitex.mom.mdm.service.MdmStationSettingService;
import com.kitex.mom.mdm.vo.excel.MdmProcedureStationBindExcelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 工位配置管理 Controller
 *
 * @author claude
 * @date 2025-07-30
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mdmStationSetting")
@Tag(description = "mdmStationSetting", name = "工位配置管理")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MdmStationSettingController {

    private final MdmStationSettingService mdmStationSettingService;

    /**
     * 分页查询工序-工位绑定
     *
     * @param page 分页对象
     * @param bind 查询条件
     * @return 分页结果
     */
    @Operation(summary = "分页查询", description = "分页查询工序-工位绑定")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mdm_mdmStationSetting_view')")
    public R<Page<MdmStationSettingEntity>> page(@ParameterObject SearchPage page,
                                                 @ParameterObject MdmStationSettingEntity bind) {
        return R.ok(mdmStationSettingService.getPage(page.toMpPage(), bind));
    }

    /**
     * 查询工序-工位绑定状态的数量
     *
     * @return
     */
    @Operation(summary = "查询工序-工位绑定状态的数量", description = "查询工序-工位绑定状态的数量")
    @GetMapping("/getCount")
    public R<Map<Boolean, Long>> getCount(@ParameterObject MdmStationSettingEntity bind) {
        return R.ok(mdmStationSettingService.getCount(bind));
    }

    /**
     * 根据ID获取详情
     *
     * @param id id
     * @return 详情
     */
    @Operation(summary = "根据ID获取详情", description = "根据ID获取详情")
    @GetMapping("/getById/{id}")
    public R<MdmStationSettingEntity> getById(@PathVariable("id") Long id) {
        return R.ok(mdmStationSettingService.getById(id));
    }

    /**
     * 根据线体ID获取工序-工位绑定列表
     *
     * @param lineId 线体ID
     * @return 绑定列表
     */
    @Operation(summary = "根据线体获取绑定", description = "根据线体ID获取工序-工位绑定列表")
    @GetMapping("/line/{lineId}")
    public R<List<MdmStationSettingEntity>> getByLineId(@PathVariable Long lineId) {
        List<MdmStationSettingEntity> bindings = mdmStationSettingService.getByLineId(lineId);
        return R.ok(bindings);
    }

    /**
     * 根据工序编码获取对应的工位列表
     *
     * @param procedureCode 工序编码
     * @return 工位列表
     */
    @Operation(summary = "根据工序获取工位", description = "根据工序编码获取对应的工位列表")
    @GetMapping("/procedure/{procedureCode}/stations")
    public R<List<MdmStationSettingEntity>> getStationsByProcedure(@PathVariable String procedureCode) {
        List<MdmStationSettingEntity> stations = mdmStationSettingService.getStationsByProcedure(procedureCode);
        return R.ok(stations);
    }

    /**
     * 根据工位ID获取对应的工序列表
     *
     * @param stationId 工位ID
     * @return 工序列表
     */
    @Operation(summary = "根据工位获取工序", description = "根据工位ID获取对应的工序列表")
    @GetMapping("/station/{stationId}/procedures")
    public R<List<MdmStationSettingEntity>> getProceduresByStation(@PathVariable Long stationId) {
        List<MdmStationSettingEntity> procedures = mdmStationSettingService.getProceduresByStation(stationId);
        return R.ok(procedures);
    }

    /**
     * 根据车间ID获取所有工序-工位绑定
     *
     * @param workshopId 车间ID
     * @return 绑定列表
     */
    @Operation(summary = "根据车间获取绑定", description = "根据车间ID获取所有工序-工位绑定")
    @GetMapping("/workshop/{workshopId}")
    public R<List<MdmStationSettingEntity>> getByWorkshopId(@PathVariable Long workshopId) {
        List<MdmStationSettingEntity> bindings = mdmStationSettingService.getByWorkshopId(workshopId);
        return R.ok(bindings);
    }

    /**
     * 获取线体的工序-工位映射关系
     *
     * @param lineId 线体ID
     * @return 工序编码 -> 工位列表的映射
     */
    @Operation(summary = "获取线体工序工位映射", description = "获取线体的工序-工位映射关系")
    @GetMapping("/line/{lineId}/procedure-station-map")
    public R<Map<String, List<MdmStationSettingEntity>>> getLineProcessStationMap(@PathVariable Long lineId) {
        Map<String, List<MdmStationSettingEntity>> map = mdmStationSettingService.getLineProcessStationMap(lineId);
        return R.ok(map);
    }

    /**
     * 根据线体获取工位与工序对应关系
     *
     * @param lineId 线体ID
     * @return 工位ID -> 工序列表的映射
     */
    @Operation(summary = "获取线体工位工序映射", description = "根据线体获取工位与工序对应关系")
    @GetMapping("/line/{lineId}/station-procedure-map")
    public R<Map<Long, List<MdmStationSettingEntity>>> getLineStationProcessMap(@PathVariable Long lineId) {
        Map<Long, List<MdmStationSettingEntity>> map = mdmStationSettingService.getLineStationProcessMap(lineId);
        return R.ok(map);
    }

    /**
     * 获取工位的主工序
     *
     * @param stationId 工位ID
     * @return 主工序
     */
    @Operation(summary = "获取工位主工序", description = "获取工位的主工序（isPrimary=true的工序）")
    @GetMapping("/station/{stationId}/primary-procedure")
    public R<MdmStationSettingEntity> getPrimaryProcedureByStation(@PathVariable Long stationId) {
        MdmStationSettingEntity primaryProcedure = mdmStationSettingService.getPrimaryProcedureByStation(stationId);
        return R.ok(primaryProcedure);
    }

    /**
     * 批量绑定工序和工位
     *
     * @param bindings 绑定列表
     * @return 操作结果
     */
    @Operation(summary = "批量绑定", description = "批量绑定工序和工位")
    @PostMapping("/batch")
//    @PreAuthorize("@pms.hasPermission('mdm_mdmStationSetting_add')")
    public R<Boolean> batchBind(@RequestBody List<MdmStationSettingEntity> bindings) {
        boolean success = mdmStationSettingService.batchBind(bindings);
        return success ? R.ok(true) : R.failed("批量绑定失败");
    }

    /**
     * 新增工序-工位绑定
     *
     * @param bind 绑定信息
     * @return 操作结果
     */
    @Operation(summary = "新增绑定", description = "新增工序-工位绑定")
    @PostMapping
//    @PreAuthorize("@pms.hasPermission('mdm_mdmStationSetting_add')")
    public R<Boolean> save(@RequestBody MdmStationSettingEntity bind) {
        return R.ok(mdmStationSettingService.saveProcedureStationBind(bind));
    }

    /**
     * 修改工序-工位绑定
     *
     * @param bind 绑定信息
     * @return 操作结果
     */
    @Operation(summary = "修改绑定", description = "修改工序-工位绑定")
    @PutMapping
//    @PreAuthorize("@pms.hasPermission('mdm_mdmStationSetting_edit')")
    public R<Boolean> update(@RequestBody MdmStationSettingEntity bind) {
        return R.ok(mdmStationSettingService.updateProcedureStationBind(bind));
    }

    /**
     * 工位-员工绑定
     *
     * @param bind 绑定信息
     * @return 操作结果
     */
    @Operation(summary = "工位-员工绑定", description = "工位-员工绑定")
    @PutMapping("/employeeBind")
    @PreAuthorize("@pms.hasPermission('mdm_mdmStationSetting_edit')")
    public R<Boolean> employeeBind(@RequestBody MdmStationSettingEntity bind) {
        return R.ok(mdmStationSettingService.employeeBind(bind));
    }

    /**
     * 设备-员工绑定
     *
     * @param bind 绑定信息
     * @return 操作结果
     */
    @Operation(summary = "设备-员工绑定", description = "设备-员工绑定")
    @PutMapping("/deviceBind")
    @PreAuthorize("@pms.hasPermission('mdm_mdmStationSetting_edit')")
    public R<Boolean> deviceBind(@RequestBody MdmStationSettingEntity bind) {
        return R.ok(mdmStationSettingService.deviceBind(bind));
    }

    /**
     * 解绑工序和工位
     *
     * @param procedureCode 工序编码
     * @param stationId     工位ID
     * @return 操作结果
     */
    @Operation(summary = "解绑", description = "解绑工序和工位")
    @DeleteMapping("/unbind/{procedureCode}/{stationId}")
    @PreAuthorize("@pms.hasPermission('mdm_mdmStationSetting_del')")
    public R<Boolean> unbind(@PathVariable String procedureCode, @PathVariable Long stationId) {
        boolean success = mdmStationSettingService.unbind(procedureCode, stationId);
        return success ? R.ok(true) : R.failed("解绑失败");
    }

    /**
     * 删除绑定
     *
     * @param ids 绑定IDS
     * @return 操作结果
     */
    @Operation(summary = "删除绑定", description = "删除工序-工位绑定")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mdm_mdmStationSetting_del')")
    public R<Boolean> removeById(@RequestBody Long[] ids) {
        return R.ok(mdmStationSettingService.removeBatchByIds(Arrays.asList(ids)));
    }

    /**
     * 检查绑定是否存在
     *
     * @param procedureCode 工序编码
     * @param stationId     工位ID
     * @return 是否存在
     */
    @Operation(summary = "检查绑定存在", description = "检查工序是否已绑定到指定工位")
    @GetMapping("/exists/{procedureCode}/{stationId}")
    public R<Boolean> isBindExists(@PathVariable String procedureCode, @PathVariable Long stationId) {
        boolean exists = mdmStationSettingService.isBindExists(procedureCode, stationId, null);
        return R.ok(exists);
    }

    /**
     * 根据线体获取工位的主工序映射关系
     * 核心功能：通过线体可以直接知道每个工位对应的主工序是什么
     *
     * @param lineId 线体ID
     * @return 工位ID -> 主工序编码的映射
     */
    @Operation(summary = "获取线体工位主工序映射", description = "根据线体获取工位的主工序映射关系")
    @GetMapping("/line/{lineId}/station-primary-procedure-map")
    public R<Map<Long, String>> getLineStationPrimaryProcedureMap(@PathVariable Long lineId) {
        Map<Long, String> map = mdmStationSettingService.getLineStationPrimaryProcedureMap(lineId);
        return R.ok(map);
    }

    /**
     * 根据线体获取工序执行序列（按优先级排序）
     * 用于确定生产流程的工序顺序
     *
     * @param lineId 线体ID
     * @return 按优先级排序的工序编码列表
     */
    @Operation(summary = "获取线体工序序列", description = "根据线体获取工序执行序列（按优先级排序）")
    @GetMapping("/line/{lineId}/procedure-sequence")
    public R<List<String>> getLineProcedureSequence(@PathVariable Long lineId) {
        List<String> sequence = mdmStationSettingService.getLineProcedureSequence(lineId);
        return R.ok(sequence);
    }

    /**
     * 根据线体获取工序的主工位映射关系
     * 每个工序在该线体上的主要执行工位
     *
     * @param lineId 线体ID
     * @return 工序编码 -> 主工位ID的映射
     */
    @Operation(summary = "获取线体工序主工位映射", description = "根据线体获取工序的主工位映射关系")
    @GetMapping("/line/{lineId}/procedure-primary-station-map")
    public R<Map<String, Long>> getLineProcedurePrimaryStationMap(@PathVariable Long lineId) {
        Map<String, Long> map = mdmStationSettingService.getLineProcedurePrimaryStationMap(lineId);
        return R.ok(map);
    }

    /**
     * 导入excel 表格
     * 参考 MdmMaterialController#importMaterial
     */
    @Operation(summary = "导入工序-工位绑定", description = "导入工序-工位绑定")
    @PostMapping("/import")
    @PreAuthorize("@pms.hasPermission('mdm_mdmStationSetting_export')")
    public R importProcedureStationBind(@RequestExcel(headRowNumber = 2) List<MdmProcedureStationBindExcelVO> excelVOList,
                                        BindingResult bindingResult) {
        return mdmStationSettingService.importProcedureStationBind(excelVOList, bindingResult);
    }

    /**
     * 导出excel 表格
     * 参考 MdmMaterialController#export
     */
    @ResponseExcel
    @GetMapping("/export")
    @PreAuthorize("@pms.hasPermission('mdm_mdmStationSetting_export')")
    public List<MdmProcedureStationBindExcelVO> export(@ParameterObject MdmStationSettingEntity query, Long[] ids) {
        return mdmStationSettingService.export(query, ids);
    }
}