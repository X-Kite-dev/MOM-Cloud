package com.kitex.mom.mes.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.AndonNoticeResp;
import com.kitex.mom.mes.dto.AndonRecordCancelVO;
import com.kitex.mom.mes.dto.AndonRecordHandleVO;
import com.kitex.mom.mes.dto.AndonRecordVerifyVO;
import com.kitex.mom.mes.entity.MesAndonOperationEntity;
import com.kitex.mom.mes.entity.MesAndonRecordEntity;
import com.kitex.mom.mes.enums.AndonStateEnum;
import com.kitex.mom.mes.service.MesAndonRecordService;
import com.kitex.mom.mes.service.impl.MesAndonProductionReleaseService;
import com.kitex.mom.mes.service.impl.MesStartPreparationDrilldownService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 安灯信息处理记录
 *
 * @author kitex
 * @date 2025-02-18 15:27:53
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesAndonRecord")
@Tag(description = "mesAndonRecord", name = "安灯信息处理记录")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesAndonRecordController {

    private final MesAndonRecordService service;
    private final MesAndonProductionReleaseService productionReleaseService;
    private final MesStartPreparationDrilldownService preparationDrilldown;

    /**
     * 分页查询
     *
     * @param pageParam
     * @param query
     * @return
     */

    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/record/page")
    @PreAuthorize("@pms.hasPermission('andonRecord:show')")
    public R<IPage<MesAndonRecordEntity>> recordPage(Page<MesAndonRecordEntity> pageParam, MesAndonRecordEntity query,
                                                     @RequestParam(required = false) Long preparationArrangeId) {
        QueryWrapper<MesAndonRecordEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(query);
        autoQueryWrapper.eq("factory_code", productionReleaseService.authorizeFactory("read"));
        if (preparationArrangeId != null) {
            var arrange = preparationDrilldown.require(preparationArrangeId, query.getArrangeCode(), query.getTaskCode());
            autoQueryWrapper.eq("tenant_id", arrange.getTenantId()).eq("arrange_code", arrange.getCode())
                    .eq("task_code", arrange.getTaskCode());
        }
        autoQueryWrapper.lambda().orderByDesc(MesAndonRecordEntity::getCreateTime);
        return R.ok(service.page(pageParam, autoQueryWrapper));
    }


    /**
     * 分页查询
     *
     * @param pageParam
     * @param query
     * @return
     */

    @Operation(summary = "分页查询", description = "分页查询")
    @GetMapping("/notice/page")
    @PreAuthorize("@pms.hasPermission('andonRecord:show')")
    public R<IPage<AndonNoticeResp>> noticePage(Page<AndonNoticeResp> pageParam, AndonNoticeResp query) {
        return R.ok(service.noticePage(pageParam, query));
    }

    /**
     * 工控机列表
     *
     * @param pageParam
     * @param query
     * @return
     */
    @GetMapping("/work/page")
    @PreAuthorize("@pms.hasPermission('andonRecord:show')")
    public R<IPage<MesAndonRecordEntity>> workPage(Page<MesAndonRecordEntity> pageParam, MesAndonRecordEntity query) {
        QueryWrapper<MesAndonRecordEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(query);
        autoQueryWrapper.eq("factory_code", productionReleaseService.authorizeFactory("read"));
        MomUser currentUser = SecurityUtils.getUser();
        autoQueryWrapper
                .apply("CAST({0} AS text) = ANY(regexp_split_to_array(handler_code, '\\s*,\\s*'))", currentUser.getId())
                .ne("state", AndonStateEnum.CLOSED.getCode())
                .orderByDesc("create_time");
        return R.ok(service.page(pageParam, autoQueryWrapper));
    }


    /**
     * 通过id查询安灯信息处理记录
     *
     * @param id id
     * @return R
     */
    @Operation(summary = "通过id查询", description = "通过id查询")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('andonRecord:show')")
    public R getById(@PathVariable("id") Long id) {
        return R.ok(productionReleaseService.readScopedEvent(id));
    }

    /**
     * 新增安灯信息处理记录
     *
     * @param mesAndonRecord 安灯信息处理记录
     * @return R
     */
    @Operation(summary = "新增安灯信息处理记录", description = "新增安灯信息处理记录")
    @SysLog("新增安灯异常类型配置")
    @PostMapping("/add")
    @PreAuthorize("@pms.hasPermission('andonRecord:add')")
    public R<MesAndonRecordEntity> save(@RequestBody MesAndonRecordEntity mesAndonRecord) {
        return R.ok(service.saveData(mesAndonRecord));
    }

    // 安灯业务记录是审计凭证，不开放通用修改和物理/逻辑删除入口。

    /**
     * 撤销
     *
     * @param vo 撤销命令
     * @return 新增结果
     */
    @PostMapping("/cancel")
    @PreAuthorize("@pms.hasPermission('andonRecord:cancel')")
    public R<Void> cancel(@RequestBody AndonRecordCancelVO vo) {
        service.cancel(vo);
        return R.ok();
    }

    /**
     * 关闭
     *
     * @param id id
     * @return 新增结果
     */
    @PostMapping("/close")
    @PreAuthorize("@pms.hasPermission('andonRecord:close')")
    public R<Void> close(Long id) {
        service.close(id);
        return R.ok();
    }


    /**
     * 关闭
     *
     * @param id id
     * @return 新增结果
     */
    @PostMapping("/receive")
    @PreAuthorize("@pms.hasPermission('andonRecord:receive')")
    public R<Void> receive(Long id) {
        service.receive(id);
        return R.ok();
    }

    /**
     * 处理
     *
     * @param vo vo
     * @return 新增结果
     */
    @PostMapping("/handle")
    @PreAuthorize("@pms.hasPermission('andonRecord:handle')")
    public R<Void> handle(@RequestBody AndonRecordHandleVO vo) {
        service.handle(vo);
        return R.ok();
    }

    @Operation(summary = "验证安灯处理结果", description = "发起人验证通过后闭环，驳回则返回处理环节")
    @PostMapping("/verify")
    @PreAuthorize("@pms.hasPermission('andonRecord:close')")
    public R<Void> verify(@RequestBody AndonRecordVerifyVO vo) {
        service.verify(vo);
        return R.ok();
    }

    @Operation(summary = "查询安灯操作履历")
    @GetMapping("/{id}/operations")
    @PreAuthorize("@pms.hasPermission('andonRecord:show')")
    public R<List<MesAndonOperationEntity>> operations(@PathVariable("id") Long id) {
        return R.ok(service.listOperations(id));
    }

}
