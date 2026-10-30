package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.entity.MesArrangeTimingEventEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;

import java.time.LocalDateTime;

/**
 * 原派工时间证据服务；无对外通用CRUD命令，调用方须持真实派工业务锁。
 */
public interface MesArrangeTimingEventService extends IService<MesArrangeTimingEventEntity> {
    /**
     * 返回数据库生成的原区间/事件及精确秒数正文，不接受端侧扣除量。
     */
    String pauseContext(MesTaskArrangeEntity arrange, LocalDateTime from, LocalDateTime until);
}
