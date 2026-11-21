package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesMaterialListDTO;
import com.kitex.mom.mes.entity.MesMaterialListEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.enums.MesMaterialListStatusEnum;
import com.kitex.mom.mes.mapper.MesMaterialListMapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.service.MesMaterialListDetailService;
import com.kitex.mom.mes.service.MesMaterialListService;
import com.kitex.mom.mes.vo.MesMaterialListQueryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * <p>
 * 用料清单主表 服务实现类
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MesMaterialListServiceImpl extends ServiceImpl<MesMaterialListMapper, MesMaterialListEntity> implements MesMaterialListService {
    private final MesReworkMaterialPlanService reworkMaterialPlans;

    private final MesTaskMapper mesTaskMapper;
    private final MesActualFactoryAccessService factoryAccess;
    private final MesMaterialListDetailService materialListDetailService;

    @Override
    public Page<MesMaterialListDTO> getMaterialListPage(Long current, Long size, MesMaterialListQueryVO queryVO) {
        queryVO.setFactoryCode(factoryAccess.require("read"));
        Page<MesMaterialListDTO> page = new Page<>(current, size);
        return baseMapper.selectMaterialListPage(page, queryVO, TenantContextHolder.getTenantId());
    }

    @Override
    public MesMaterialListDTO getMaterialListByTaskId(Long taskId) {
        String factory = factoryAccess.require("read");
        Long tenant = TenantContextHolder.getTenantId();
        MesMaterialListDTO dto = baseMapper.selectMaterialListByTaskId(taskId, tenant, factory);
        if (dto == null && !mesTaskMapper.exists(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getId, taskId).eq(MesTaskEntity::getTenantId, tenant)
                .eq(MesTaskEntity::getFactoryCode, factory))) {
            throw new CheckedException("当前工厂未找到原工单");
        }
        if (dto != null) {
            dto.setMaterialDetails(materialListDetailService.getDetailsByMaterialListId(dto.getId()));
        }
        return dto;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean createMaterialListByTask(Long taskId) {
        var task = lockTask(taskId, factoryAccess.require("write"));
        reworkMaterialPlans.assertReady(task);
        var head = getOne(new LambdaQueryWrapper<MesMaterialListEntity>()
                .eq(MesMaterialListEntity::getTaskId, taskId)
                .eq(MesMaterialListEntity::getTenantId, task.getTenantId())
                .eq(MesMaterialListEntity::getFactoryCode, task.getFactoryCode()).last("FOR UPDATE"));
        if (head != null) {
            return synchronizeFrozenList(head, task);
        }

        head = new MesMaterialListEntity();
        head.setTenantId(task.getTenantId());
        head.setFactoryCode(task.getFactoryCode());
        head.setTaskId(taskId);
        head.setTaskCode(task.getCode());
        head.setProductCode(task.getMatCode());
        head.setProductName(task.getMatName());
        head.setProductSpec(task.getMatSpec());
        head.setBomId(task.getBomId());
        head.setBomVersion(task.getBomVersion());
        head.setBomSnapshot(task.getBomSnapshot());
        head.setPlanQuantity(task.getNoticeNum());
        head.setStatus(reworkMaterialPlans.approvedWithoutMaterials(task)
                ? MesMaterialListStatusEnum.COMPLETED.getCode() : MesMaterialListStatusEnum.NEW.getCode());
        if (!save(head)) {
            throw new CheckedException("用料清单主单保存失败");
        }
        return synchronizeFrozenList(head, task);
    }

    /** 先锁工单，再读取或创建清单，避免两个请求同时生成不同的明细身份。 */
    private MesTaskEntity lockTask(Long taskId, String factory) {
        var task = mesTaskMapper.selectOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getId, taskId).eq(MesTaskEntity::getFactoryCode, factory)
                .eq(MesTaskEntity::getTenantId, TenantContextHolder.getTenantId()).last("FOR UPDATE"));
        if (task == null) {
            throw new CheckedException("当前工厂未找到原生产工单");
        }
        return task;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateMaterialListStatus(Long id, String status) {
        var locked = lockList(id);
        materialListDetailService.applyHeadStatus(locked.head(), locked.task(), status);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean regenerateMaterialList(Long id) {
        var locked = lockList(id);
        reworkMaterialPlans.assertReady(locked.task());
        return synchronizeFrozenList(locked.head(), locked.task());
    }

    private record LockedList(MesMaterialListEntity head, MesTaskEntity task) {
    }

    /** 根据清单身份定位工单，按工单、清单的顺序加锁后再次核对归属。 */
    private LockedList lockList(Long id) {
        String factory = factoryAccess.require("write");
        var query = new LambdaQueryWrapper<MesMaterialListEntity>()
                .eq(MesMaterialListEntity::getId, id).eq(MesMaterialListEntity::getFactoryCode, factory)
                .eq(MesMaterialListEntity::getTenantId, TenantContextHolder.getTenantId());
        var head = getOne(query);
        if (head == null) {
            throw new CheckedException("当前工厂未找到用料清单");
        }
        var task = lockTask(head.getTaskId(), factory);
        head = getOne(query.last("FOR UPDATE"));
        if (head == null || !Objects.equals(head.getTaskId(), task.getId())) {
            throw new CheckedException("用料清单归属已变化，请刷新原工单");
        }
        return new LockedList(head, task);
    }

    /** 先核明细。相同快照下保留头单的领料进度和历史，任何必要写入失败整单回滚。 */
    private boolean synchronizeFrozenList(MesMaterialListEntity head, MesTaskEntity task) {
        if (!materialListDetailService.createMaterialDetailsByBom(head.getId(), task.getId(),
                task.getBomId(), task.getBomVersion(), task.getNoticeNum())) {
            throw new CheckedException("工单 " + task.getCode() + " 的用料清单明细未全部保存");
        }
        if (!Objects.equals(head.getBomId(), task.getBomId())
                || !Objects.equals(head.getBomVersion(), task.getBomVersion())
                || !Objects.equals(head.getBomSnapshot(), task.getBomSnapshot())
                || head.getPlanQuantity() == null || head.getPlanQuantity().compareTo(task.getNoticeNum()) != 0) {
            head.setBomId(task.getBomId());
            head.setBomVersion(task.getBomVersion());
            head.setBomSnapshot(task.getBomSnapshot());
            head.setPlanQuantity(task.getNoticeNum());
            if (!updateById(head)) {
                throw new CheckedException("更新冻结用料清单主单失败");
            }
        }
        materialListDetailService.applyHeadStatus(head, task, null);
        return true;
    }

    @Override
    public boolean checkMaterialListCompleted(Long id) {
        String factory = factoryAccess.require("read");
        var head = getOne(new LambdaQueryWrapper<MesMaterialListEntity>()
                .eq(MesMaterialListEntity::getId, id).eq(MesMaterialListEntity::getFactoryCode, factory)
                .eq(MesMaterialListEntity::getTenantId, TenantContextHolder.getTenantId()));
        if (head == null) {
            return false;
        }
        var details = materialListDetailService.getDetailsByMaterialListId(id);
        if (details.isEmpty()) {
            var task = mesTaskMapper.selectOne(new LambdaQueryWrapper<MesTaskEntity>()
                    .eq(MesTaskEntity::getId, head.getTaskId()).eq(MesTaskEntity::getFactoryCode, factory)
                    .eq(MesTaskEntity::getTenantId, head.getTenantId()));
            return reworkMaterialPlans.approvedWithoutMaterials(task);
        }
        return details.stream().allMatch(row -> row.getRemainingQuantity() != null && row.getRemainingQuantity().signum() <= 0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean batchCreateMaterialListByTasks(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty() || taskIds.stream().anyMatch(Objects::isNull)) {
            throw new CheckedException("请选择要生成用料清单的生产工单");
        }
        for (Long taskId : taskIds.stream().distinct().sorted().toList()) {
            createMaterialListByTask(taskId);
        }
        return true;
    }
}
