package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mes.dto.MesProcessProcedureMoldDTO;
import com.kitex.mom.mes.dto.MesRouteMoldConfigurationDTO;
import com.kitex.mom.mes.entity.MesProcessProcedureMoldEntity;
import com.kitex.mom.mes.service.MesProcessProcedureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 模具绑定
 *
 * @author kitex
 * @since 2025-04-11
 */
@Tag(description = "mesProcessProcedureMold", name = "模具绑定")
@RestController
@RequestMapping("/mesProcessProcedureMold")
@RequiredArgsConstructor
public class MesProcessProcedureMoldController {

    private final MesProcessProcedureService service;

    @Operation(summary = "读取节点模具维护快照", description = "含实际节点、原图版本和当前绑定，不替代生产冻结")
    @GetMapping("/configuration/{id}")
    @PreAuthorize("@pms.hasPermission('mdm_mdmProcessRoute_view')")
    public R<MesRouteMoldConfigurationDTO> configuration(@PathVariable("id") Long id) {
        return R.ok(service.configuration(id));
    }

    @Operation(summary = "按稳定路线节点查询模具绑定")
    @GetMapping("/getMoldByNode")
    public R<List<MesProcessProcedureMoldEntity>> getMoldByNode(@RequestParam("routeCode") String routeCode,
                                                                @RequestParam("routeNodeCode") String routeNodeCode) {
        return R.ok(service.getMoldByNode(routeCode, routeNodeCode));
    }


    /**
     * 查询工艺路线下工序绑定的模具信息
     *
     * @param id 工艺路线-工序id
     * @return R
     */
    @Operation(summary = "查询工艺路线下工序绑定的模具信息", description = "查询工艺路线下工序绑定的模具信息")
    @GetMapping("/getMold/{id}")
    public R<List<MesProcessProcedureMoldEntity>> getMold(@PathVariable("id") Long id) {
        return R.ok(service.getMold(id));
    }

    /**
     * 新增工艺路线下工序绑定的模具信息
     *
     * @param mesProcessProcedureMoldDTO 工艺路线-工序-模具
     * @return R
     */
    @Operation(summary = "保存路线节点模具配置", description = "核原图版本，差异保存；空清单明确解绑")
    @PutMapping("/saveMold")
    @PreAuthorize("@pms.hasPermission('mdm_mdmProcessRoute_edit')")
    public R<Boolean> saveMold(@RequestBody MesProcessProcedureMoldDTO mesProcessProcedureMoldDTO) {
        return R.ok(service.saveMold(mesProcessProcedureMoldDTO));
    }

    /**
     * 查询工艺路线下工序绑定的模具信息
     *
     * @param id 工艺路线-工序id
     * @return R
     */
    @Operation(summary = "查询工艺路线下工序绑定的模具信息", description = "查询工艺路线下工序绑定的模具信息")
    @GetMapping("/getMoldByCode")
    public R<List<MesProcessProcedureMoldEntity>> getMoldByCode(String routeCode, String processCode) {
        return R.ok(service.getMoldByCode(routeCode, processCode));
    }

    /**
     * 查询工艺路线下所有可选择的模具列表
     *
     * @param id 工艺路线-工序id
     * @return R
     */
    @Operation(summary = "查询工艺路线下工序绑定的模具信息", description = "查询工艺路线下工序绑定的模具信息")
    @GetMapping("/queryMoldList/{id}")
    public R<List<MdmDeviceEntity>> queryMoldList(@PathVariable("id") Long id) {
        return R.ok(service.queryMoldList(id));
    }

}
