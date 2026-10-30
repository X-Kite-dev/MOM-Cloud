package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesReportCommandReceiptDTO;
import com.kitex.mom.mes.dto.MesReportOperatorContextDTO;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.entity.MesReportCommandEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.enums.MesReportChannelEnum;

import java.util.Collection;

/**
 * 原始报工命令与成功回执领域服务，不提供通用增删改入口。
 */
public interface MesReportCommandService extends IService<MesReportCommandEntity> {

    /**
     * 原正文先冻结、原号先查重；返回已有reportId即表示可以回放，否则继续原子报工。
     */
    CapturedCommand capture(MesTaskReportVO command, MesReportChannelEnum channel);

    /**
     * 载具外层事务在任何工单/行锁前，按原号排序预取全部逐件请求锁。
     */
    void lockRequests(Collection<String> requestIds);

    /**
     * 所有业务事实完成后、同一事务提交前追加永久成功回执。
     */
    void accept(CapturedCommand captured, MesTaskReportEntity report);

    /**
     * 已成功弱网包若有新版原正文则完整核对；无新版账只返回false，不为历史伪造证据。
     */
    boolean verifyAcceptedReplay(MesTaskReportVO command, MesReportChannelEnum channel, Long reportId);

    /**
     * 仅原发起人、原工厂查询；查询不重新执行任何报工动作。
     */
    MesReportCommandReceiptDTO receipt(String requestId);

    /**
     * 仅交付当前实名组织身份，不授予执行或离线许可。
     */
    MesReportOperatorContextDTO operatorContext();

    /**
     * 与原报工同原号锁：已有成功返回原回执，否则永久封存本次未受理原命令。
     */
    MesReportCommandReceiptDTO closeUnaccepted(MesTaskReportVO command, MesReportChannelEnum channel, String reason);

    /**
     * 解析前的不可变上下文，不保留可被业务处理改写的VO引用。
     */
    record CapturedCommand(String requestId, String factoryCode, Long tenantId, Long actorId,
                           String actorCode, String channel, String version, String snapshot, String fingerprint,
                           Long replayReportId) {
    }
}
