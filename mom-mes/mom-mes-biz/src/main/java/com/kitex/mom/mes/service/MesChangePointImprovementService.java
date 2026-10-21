package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesChangePointImprovementCreateCommand;
import com.kitex.mom.mes.entity.MesChangePointImprovementEntity;
import com.kitex.mom.mes.entity.MesChangePointImprovementOperationEntity;

import java.util.List;

/**
 * 变化点损失Pareto改善来源服务。
 */
public interface MesChangePointImprovementService extends IService<MesChangePointImprovementEntity> {

    IPage<MesChangePointImprovementEntity> queryPage(Page<MesChangePointImprovementEntity> page,
                                                     MesChangePointImprovementEntity query);

    MesChangePointImprovementEntity getDetail(Long id);

    MesChangePointImprovementEntity create(MesChangePointImprovementCreateCommand command);

    List<MesChangePointImprovementOperationEntity> operations(Long id);
}
