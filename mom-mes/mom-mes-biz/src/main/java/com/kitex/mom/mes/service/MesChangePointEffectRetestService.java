package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesChangePointEffectRetestContextDTO;
import com.kitex.mom.mes.dto.MesChangePointEffectRetestCreateCommand;
import com.kitex.mom.mes.dto.MesChangePointEffectRetestProof;
import com.kitex.mom.mes.dto.MesChangePointEffectRetestReviewCommand;
import com.kitex.mom.mes.entity.MesChangePointEffectRetestEntity;

/**
 * CAPA关闭后的原始证据复测、独立复核与可靠新周期复开。
 */
public interface MesChangePointEffectRetestService extends IService<MesChangePointEffectRetestEntity> {
    /**
     * 读取原始来源与QMS当前权威周期，不依赖链接缓存。
     */
    MesChangePointEffectRetestContextDTO context(Long improvementId);

    /**
     * 固定排序分页，列表不传输大证据快照。
     */
    Page<MesChangePointEffectRetestEntity> queryPage(Long improvementId, long current, long size);

    /**
     * 获取当前实际租户、工厂内的完整不可变证据。
     */
    MesChangePointEffectRetestEntity detail(Long id);

    /**
     * 关闭后按原分组取证，冻结目标、缺口及精确计算结果。
     */
    MesChangePointEffectRetestEntity create(MesChangePointEffectRetestCreateCommand command);

    /**
     * 独立复核重新取证，漂移则失效，不能人工选择达标结果。
     */
    MesChangePointEffectRetestEntity review(MesChangePointEffectRetestReviewCommand command);

    /**
     * QMS只读反查已提交FAIL，不回调QMS，避免跨服务锁等待。
     */
    MesChangePointEffectRetestProof proof(Long id);

    /**
     * 复核事务提交后调用；失败保留原请求号，可从原单重新投递。
     */
    void executeReopen(Long id);
}
