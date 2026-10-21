package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesWarehouseStockOutReceiptDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedBirthDTO;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesWarehouseFeedBirthEntity;
import com.kitex.mom.mes.entity.MesWarehouseFeedEntity;

/** 投料出生原账，不提供历史补账或单独扣库入口。 */
public interface MesWarehouseFeedBirthService extends IService<MesWarehouseFeedBirthEntity> {
    /** 首次实际投料前验证260全部目录保护；无底座不得静默降级。 */
    void assertReady();

    /** 按实际工厂读取完整永久来源；旧无证据明确未证明，不按当前批次补造。 */
    MesWarehouseFeedBirthDTO evidence(Long feedId);

    /** 与首次feed/OUT同一事务冻结全部原来源，任何必要写或回读失败全体回滚。 */
    MesWarehouseFeedBirthEntity record(MesWarehouseFeedEntity feed, MesTaskEntity task,
                                       MesTaskArrangeEntity arrange, MesWarehouseStockOutReceiptDTO receipt);
}
