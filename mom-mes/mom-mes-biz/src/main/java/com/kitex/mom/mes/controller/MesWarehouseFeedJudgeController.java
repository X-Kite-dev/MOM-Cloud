package com.kitex.mom.mes.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesWarehouseFeedJudgeDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedLeftDTO;
import com.kitex.mom.mes.entity.MesWarehouseFeedJudgeEntity;
import com.kitex.mom.mes.service.MesWarehouseFeedJudgeService;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 升级前投料余料审批历史查询入口。
 *
 * <p>旧模型缺少精确实物组和WMS幂等证据，仅保留同租户、同工厂历史查询；
 * 所有写操作均故障关闭，新的生产退料使用{@code /mesWarehouseStockFeedJudge}。</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWarehouseFeedJudge")
@Tag(description = "mesWarehouseFeedJudge", name = "历史投料余料审批（只读）")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class MesWarehouseFeedJudgeController {

    private static final String LEGACY_READ_ONLY_MESSAGE =
            "旧投料余料审批已停用，请使用线边库存受控退库功能";

    private final MesWarehouseFeedJudgeService service;
    private final MesActualFactoryAccessService factoryAccess;

    @Operation(summary = "分页查询历史退料审批")
    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesWarehouseFeedJudge:show')")
    public R<IPage<MesWarehouseFeedJudgeEntity>> page(Page<MesWarehouseFeedJudgeEntity> page,
                                                      MesWarehouseFeedJudgeEntity query) {
        return R.ok(service.page(page, historyQuery(query)));
    }

    @Operation(summary = "列表查询历史退料审批")
    @GetMapping("/list")
    @PreAuthorize("@pms.hasPermission('mesWarehouseFeedJudge:show')")
    public R<List<MesWarehouseFeedJudgeEntity>> list(MesWarehouseFeedJudgeEntity query) {
        return R.ok(service.list(historyQuery(query)));
    }

    @Operation(summary = "按主键查询历史退料审批")
    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mesWarehouseFeedJudge:show')")
    public R<MesWarehouseFeedJudgeEntity> getById(@PathVariable Long id) {
        MesWarehouseFeedJudgeEntity record = service.getOne(historyQuery(new MesWarehouseFeedJudgeEntity())
                .eq(MesWarehouseFeedJudgeEntity::getId, id), false);
        if (record == null) {
            throw new CheckedException("当前租户和工厂下不存在该历史退料审批");
        }
        return R.ok(record);
    }

    @Operation(summary = "旧新增入口已停用")
    @PostMapping
    @PreAuthorize("@pms.hasPermission('mesWarehouseFeedJudge:add')")
    public R<Void> save(@RequestBody MesWarehouseFeedJudgeEntity ignored) {
        throw legacyReadOnly();
    }

    @Operation(summary = "旧修改入口已停用")
    @PutMapping
    @PreAuthorize("@pms.hasPermission('mesWarehouseFeedJudge:edit')")
    public R<Void> update(@RequestBody MesWarehouseFeedJudgeEntity ignored) {
        throw legacyReadOnly();
    }

    @Operation(summary = "旧删除入口已停用")
    @DeleteMapping
    @PreAuthorize("@pms.hasPermission('mesWarehouseFeedJudge:delete')")
    public R<Void> remove(@RequestBody Long[] ignored) {
        throw legacyReadOnly();
    }

    @Operation(summary = "旧提交入口已停用")
    @PostMapping("/submit")
    @PreAuthorize("@pms.hasPermission('mesWarehouseFeedJudge:submit')")
    public R<Void> submit(@RequestBody MesWarehouseFeedLeftDTO ignored) {
        throw legacyReadOnly();
    }

    @Operation(summary = "旧审批入口已停用")
    @PostMapping("/judge")
    @PreAuthorize("@pms.hasPermission('mesWarehouseFeedJudge:judge')")
    public R<Void> judge(@RequestBody MesWarehouseFeedJudgeDTO ignored) {
        throw legacyReadOnly();
    }

    private LambdaQueryWrapper<MesWarehouseFeedJudgeEntity> historyQuery(MesWarehouseFeedJudgeEntity query) {
        String factory = factoryAccess.require("read");
        return new LambdaQueryWrapper<MesWarehouseFeedJudgeEntity>()
                .eq(MesWarehouseFeedJudgeEntity::getFactoryCode, factory)
                .eq(StrUtil.isNotBlank(query.getStatus()), MesWarehouseFeedJudgeEntity::getStatus, query.getStatus())
                .like(StrUtil.isNotBlank(query.getCode()), MesWarehouseFeedJudgeEntity::getCode, query.getCode())
                .like(StrUtil.isNotBlank(query.getTaskCode()), MesWarehouseFeedJudgeEntity::getTaskCode,
                        query.getTaskCode())
                .like(StrUtil.isNotBlank(query.getMatCode()), MesWarehouseFeedJudgeEntity::getMatCode,
                        query.getMatCode())
                .orderByDesc(MesWarehouseFeedJudgeEntity::getCreateTime)
                .orderByDesc(MesWarehouseFeedJudgeEntity::getId);
    }

    private CheckedException legacyReadOnly() {
        return new CheckedException(LEGACY_READ_ONLY_MESSAGE);
    }
}
