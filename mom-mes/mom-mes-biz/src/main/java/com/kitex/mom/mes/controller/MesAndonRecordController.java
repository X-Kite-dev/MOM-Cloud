package com.kitex.mom.mes.controller;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.AndonNoticeResp;
import com.kitex.mom.mes.dto.AndonRecordHandleVO;
import com.kitex.mom.mes.entity.MesAndonRecordEntity;
import com.kitex.mom.mes.enums.AndonStateEnum;
import com.kitex.mom.mes.service.MesAndonRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

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
    public R<IPage<MesAndonRecordEntity>> recordPage(Page<MesAndonRecordEntity> pageParam, MesAndonRecordEntity query) {
        QueryWrapper<MesAndonRecordEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(query);
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
        MomUser currentUser = SecurityUtils.getUser();
        autoQueryWrapper
                .apply("FIND_IN_SET({0}, handler_code) > 0", currentUser.getId())
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
        return R.ok(service.getById(id));
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
    public R<Void> save(@RequestBody MesAndonRecordEntity mesAndonRecord) {
        service.saveData(mesAndonRecord);
        return R.ok();
    }

    /**
     * 修改安灯异常类型配置
     *
     * @param mesAndonRecord 安灯异常类型配置
     * @return R
     */
    @Operation(summary = "修改安灯信息处理记录", description = "修改安灯信息处理记录")
    @SysLog("修改安灯异常类型配置")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('andonRecord:edit')")
    public R<Void> updateById(@RequestBody MesAndonRecordEntity mesAndonRecord) {
        service.updateById(mesAndonRecord);
        return R.ok();
    }

    /**
     * 通过id删除安灯异常类型配置
     *
     * @param ids id列表
     * @return R
     */
    @Operation(summary = "通过id删除安灯信息处理记录", description = "通过id删除安灯信息处理记录")
    @SysLog("通过id删除安灯异常类型配置")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('andonRecord:delete')")
    public R<Void> removeById(@RequestBody Long[] ids) {
        service.removeBatchByIds(CollUtil.toList(ids));
        return R.ok();
    }

    /**
     * 撤销
     *
     * @param id id
     * @return 新增结果
     */
    @PostMapping("/cancel")
    @PreAuthorize("@pms.hasPermission('andonRecord:cancel')")
    public R<Void> cancel(Long id) {
        service.cancel(id);
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

}