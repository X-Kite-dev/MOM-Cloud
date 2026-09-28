package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesWarehouseFeedJudgeDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedLeftDTO;
import com.kitex.mom.mes.entity.MesWarehouseFeedJudgeEntity;
import com.kitex.mom.mes.mapper.MesWarehouseFeedJudgeMapper;
import com.kitex.mom.mes.service.MesWarehouseFeedJudgeService;
import org.springframework.stereotype.Service;

/**
 * 升级前投料记录余料审批的只读兼容服务。
 *
 * <p>旧流程按投料汇总修改数量，缺少仓库、库位、批次、冻结证据和WMS永久幂等号，
 * 继续写入会绕过线边实物库存闭环。历史数据仍可查询，所有写操作统一引导至
 * {@code /mesWarehouseStockFeedJudge} 受控退库流程。</p>
 */
@Service
public class MesWarehouseFeedJudgeServiceImpl
        extends ServiceImpl<MesWarehouseFeedJudgeMapper, MesWarehouseFeedJudgeEntity>
        implements MesWarehouseFeedJudgeService {

    private static final String LEGACY_READ_ONLY_MESSAGE =
            "旧投料余料审批已停用，请使用线边库存受控退库功能";

    @Override
    public void submitReturnMaterial(MesWarehouseFeedLeftDTO submitDTO) {
        throw new CheckedException(LEGACY_READ_ONLY_MESSAGE);
    }

    @Override
    public void judge(MesWarehouseFeedJudgeDTO submitDTO) {
        throw new CheckedException(LEGACY_READ_ONLY_MESSAGE);
    }
}
