package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.mdm.feign.RemoteBomService;
import com.kitex.mom.mes.dto.MesMaterialListDTO;
import com.kitex.mom.mes.dto.MesMaterialListDetailDTO;
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
    private final RemoteBomService remoteBomService;
    private final MesMaterialListDetailService materialListDetailService;

    @Override
    public Page<MesMaterialListDTO> getMaterialListPage(Long current, Long size, MesMaterialListQueryVO queryVO) {
        Page<MesMaterialListDTO> page = new Page<>(current, size);
        return baseMapper.selectMaterialListPage(page, queryVO);
    }

    @Override
    public MesMaterialListDTO getMaterialListByTaskId(Long taskId) {
        MesMaterialListDTO dto = baseMapper.selectMaterialListByTaskId(taskId);
        if (dto != null) {
            dto.setMaterialDetails(materialListDetailService.getDetailsByMaterialListId(dto.getId()));
        }
        return dto;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean createMaterialListByTask(Long taskId) {
        try {
            MesTaskEntity task = mesTaskMapper.selectOne(new LambdaQueryWrapper<MesTaskEntity>().eq(MesTaskEntity::getId,taskId).last("FOR UPDATE"));
            if (task == null) {
                log.error("工单不存在，taskId: {}", taskId);
                return false;
            }
            reworkMaterialPlans.assertReady(task);

            MesMaterialListEntity existingList = this.getOne(
                    new LambdaQueryWrapper<MesMaterialListEntity>()
                            .eq(MesMaterialListEntity::getTaskId, taskId)
                            .eq(MesMaterialListEntity::getDelFlag, "0")
            );

            if (existingList != null) {
                log.warn("该工单已存在用料清单，taskId: {}", taskId);
                return true;
            }

            MesMaterialListEntity materialList = new MesMaterialListEntity();
            materialList.setTenantId(task.getTenantId());
            materialList.setFactoryCode(task.getFactoryCode());
            materialList.setTaskId(taskId);
            materialList.setTaskCode(task.getCode());
            materialList.setProductCode(task.getMatCode());
            materialList.setProductName(task.getMatName());
            materialList.setProductSpec(task.getMatSpec());
            materialList.setBomId(task.getBomId());
            materialList.setBomVersion(task.getBomVersion());
            materialList.setBomSnapshot(task.getBomSnapshot());
            materialList.setPlanQuantity(task.getNoticeNum());
            materialList.setStatus(reworkMaterialPlans.approvedWithoutMaterials(task)
                    ? MesMaterialListStatusEnum.COMPLETED.getCode() : MesMaterialListStatusEnum.NEW.getCode());

            boolean saveResult = this.save(materialList);
            if (!saveResult) {
                log.error("保存用料清单失败，taskId: {}", taskId);
                return false;
            }

            boolean createDetailsResult = materialListDetailService.createMaterialDetailsByBom(
                    materialList.getId(), taskId, task.getBomId(), task.getBomVersion(), task.getNoticeNum()
            );

            if (!createDetailsResult) {
                log.error("创建用料清单明细失败，taskId: {}", taskId);
                return false;
            }

            log.info("成功创建用料清单，taskId: {}, materialListId: {}", taskId, materialList.getId());
            return true;

        } catch (Exception e) {
            log.error("创建用料清单异常，taskId: {}", taskId, e);
            throw e;
        }
    }

    @Override
    public boolean updateMaterialListStatus(Long id, String status) {
        MesMaterialListEntity entity = new MesMaterialListEntity();
        entity.setId(id);
        entity.setStatus(status);
        return this.updateById(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean regenerateMaterialList(Long id) {
        try {
            MesMaterialListEntity materialList = this.getById(id);
            if (materialList == null) {
                log.error("用料清单不存在，id: {}", id);
                return false;
            }

            MesTaskEntity task = mesTaskMapper.selectOne(new LambdaQueryWrapper<MesTaskEntity>().eq(MesTaskEntity::getId,materialList.getTaskId()).last("FOR UPDATE"));
            if (task == null) {
                log.error("关联工单不存在，taskId: {}", materialList.getTaskId());
                return false;
            }
            reworkMaterialPlans.assertReady(task);

            materialList.setBomId(task.getBomId());
            materialList.setBomVersion(task.getBomVersion());
            materialList.setBomSnapshot(task.getBomSnapshot());
            materialList.setPlanQuantity(task.getNoticeNum());
            if (reworkMaterialPlans.approvedWithoutMaterials(task)) {
                materialList.setStatus(MesMaterialListStatusEnum.COMPLETED.getCode());
            } else if (!"REWORK".equals(task.getResourceType())) {
                materialList.setStatus(MesMaterialListStatusEnum.NEW.getCode());
            }

            boolean updateResult = this.updateById(materialList);
            if (!updateResult) {
                log.error("更新用料清单失败，id: {}", id);
                return false;
            }

            boolean createDetailsResult = materialListDetailService.createMaterialDetailsByBom(
                    id, materialList.getTaskId(), task.getBomId(), task.getBomVersion(), task.getNoticeNum()
            );

            if (!createDetailsResult) {
                log.error("重新创建用料清单明细失败，id: {}", id);
                return false;
            }

            log.info("成功重新生成用料清单，id: {}", id);
            return true;

        } catch (Exception e) {
            log.error("重新生成用料清单异常，id: {}", id, e);
            throw e;
        }
    }

    @Override
    public boolean checkMaterialListCompleted(Long id) {
        List<MesMaterialListDetailDTO> details = materialListDetailService.getDetailsByMaterialListId(id);
        if (details.isEmpty()) {
            var list = getById(id);
            if (list != null && reworkMaterialPlans.approvedWithoutMaterials(mesTaskMapper.selectById(list.getTaskId()))) {
                return updateMaterialListStatus(id, MesMaterialListStatusEnum.COMPLETED.getCode());
            }
            return false;
        }

        boolean allCompleted = details.stream()
                .allMatch(detail -> "COMPLETED".equals(detail.getPickStatus()) ||
                        (detail.getPickedQuantity().compareTo(detail.getRequiredQuantity()) >= 0));

        if (allCompleted) {
            updateMaterialListStatus(id, MesMaterialListStatusEnum.COMPLETED.getCode());
            return true;
        }

        return false;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean batchCreateMaterialListByTasks(List<Long> taskIds) {
        int successCount = 0;
        for (Long taskId : taskIds) {
            try {
                if (createMaterialListByTask(taskId)) {
                    successCount++;
                }
            } catch (Exception e) {
                log.error("批量创建用料清单失败，taskId: {}", taskId, e);
            }
        }

        log.info("批量创建用料清单完成，总数: {}, 成功: {}", taskIds.size(), successCount);
        return successCount == taskIds.size();
    }
}
