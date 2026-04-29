package com.kitex.mom.mes.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.admin.api.entity.SysUserEntity;
import com.kitex.admin.api.feign.RemoteUserService;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.message.api.feign.RemoteMessagePushService;
import com.kitex.mom.common.message.constant.enums.MessageTypeEnum;
import com.kitex.mom.common.message.dto.MessageDTO;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.entity.MdmClassesEntity;
import com.kitex.mom.mdm.feign.RemoteClassesService;
import com.kitex.mom.mes.dto.AndonNoticeResp;
import com.kitex.mom.mes.dto.AndonRecordHandleVO;
import com.kitex.mom.mes.entity.MesAndonHandleConfigEntity;
import com.kitex.mom.mes.entity.MesAndonHandleConfigStaffEntity;
import com.kitex.mom.mes.entity.MesAndonNoticeEntity;
import com.kitex.mom.mes.entity.MesAndonRecordEntity;
import com.kitex.mom.mes.enums.AndonNoticeTypeEnum;
import com.kitex.mom.mes.enums.AndonStateEnum;
import com.kitex.mom.mes.init.DelayedConfig;
import com.kitex.mom.mes.init.MessageDelayed;
import com.kitex.mom.mes.mapper.MesAndonRecordMapper;
import com.kitex.mom.mes.service.MesAndonHandleConfigService;
import com.kitex.mom.mes.service.MesAndonHandleConfigStaffService;
import com.kitex.mom.mes.service.MesAndonNoticeService;
import com.kitex.mom.mes.service.MesAndonRecordService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.DelayQueue;
import java.util.stream.Collectors;

/**
 * 安灯信息处理记录
 *
 * @author kitex
 * @date 2025-02-18 16:44:58
 */
@Service
@AllArgsConstructor
public class MesAndonRecordServiceImpl extends ServiceImpl<MesAndonRecordMapper, MesAndonRecordEntity> implements MesAndonRecordService {

    public static final String MES = "MES";
    private final MesAndonNoticeService noticeService;
    private final RemoteClassesService classesService;
    private final RemoteSeqConfigService seqConfigService;
    private final MesAndonHandleConfigService configService;
    private final MesAndonHandleConfigStaffService staffService;
    private final DelayedConfig delayedConfig;
    private final RemoteMessagePushService messageService;
    private final RemoteUserService remoteUserService;

    /**
     * 安灯记录列表
     *
     * @param pageParam
     * @param query
     * @return
     */
    @Override
    public IPage<AndonNoticeResp> noticePage(Page<AndonNoticeResp> pageParam, AndonNoticeResp query) {
        MomUser sysUser = SecurityUtils.getUser();
        // TODO 页面传过来的组织id 未处理
        return baseMapper.noticePage(pageParam, query, String.valueOf(sysUser.getId()), Arrays.asList(AndonStateEnum.UN_RECEIVE.getCode(), AndonStateEnum.UP_UN_RECEIVE.getCode(), AndonStateEnum.UN_HANDLE.getCode(), AndonStateEnum.UP_UN_HANDLE.getCode()));
    }

    /**
     * 创建安灯处理
     *
     * @param mesAndonRecord
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveData(MesAndonRecordEntity mesAndonRecord) {
        MesAndonRecordEntity result = new MesAndonRecordEntity();
        MdmClassesEntity currentShift = classesService.getBanci().getData();
        MesAndonHandleConfigEntity config = configService.getOne(new LambdaQueryWrapper<MesAndonHandleConfigEntity>()
                .eq(MesAndonHandleConfigEntity::getTypeCode, mesAndonRecord.getTypeCode())
                .and(i -> i.like(MesAndonHandleConfigEntity::getShiftCode, currentShift.getShiftCode()))
                .last("limit 1"));
        if (ObjectUtil.isNull(config)) {
            throw new RuntimeException("无法匹配处理配置，呼叫失败");
        }

        List<MesAndonHandleConfigStaffEntity> staffList = staffService.list(new LambdaQueryWrapper<MesAndonHandleConfigStaffEntity>()
                .eq(MesAndonHandleConfigStaffEntity::getConfigCode, config.getCode())
                .eq(MesAndonHandleConfigStaffEntity::getLevel, mesAndonRecord.getLevel())
                .orderByAsc(MesAndonHandleConfigStaffEntity::getSort)
        );

        result.setState(AndonStateEnum.UN_RECEIVE.getCode());
        result.setCode(seqConfigService.create("mes_andon_code").getData());
        result.setConfigCode(config.getCode());
        result.setTypeCode(config.getTypeCode());
        result.setTypeName(config.getTypeName());
        result.setWorkshopCode(config.getWorkshopCode());
        result.setWorkshopName(config.getWorkshopName());
        result.setLineCode(config.getLineCode());
        result.setLineName(config.getLineName());
        result.setShiftCode(currentShift.getShiftCode());
        result.setShiftName(currentShift.getShiftName());
        result.setLevel(mesAndonRecord.getLevel());
        result.setFaultCode(mesAndonRecord.getFaultCode());
        result.setFaultName(mesAndonRecord.getFaultName());
        result.setExDesc(mesAndonRecord.getExDesc());
        result.setHandlerName(staffList.get(0).getStaffName());
        result.setHandlerCode(staffList.get(0).getStaffCode());
        result.setCreateByName(SecurityUtils.getUser().getName());

        this.save(result);

        String[] handlerCodeArray = staffList.get(0).getStaffCode().split(",");
        String[] handlerNameArray = staffList.get(0).getStaffName().split(",");
        List<MesAndonNoticeEntity> handleNoticeList = new ArrayList<>();
        for (int i = 0; i < handlerCodeArray.length; i++) {
            MesAndonNoticeEntity notice = new MesAndonNoticeEntity();
            notice.setNoticeTime(LocalDateTime.now());
            notice.setNoticeType(AndonNoticeTypeEnum.HANDLE.getCode());
            notice.setStaffCode(handlerCodeArray[i]);
            notice.setStaffName(handlerNameArray[i]);
            notice.setRecordCode(result.getCode());
            handleNoticeList.add(notice);
        }
        noticeService.saveBatch(handleNoticeList);


        // 将通知人加入延时队列中等待处理
        DelayQueue<MessageDelayed> delayQueue = delayedConfig.getQueue();
        for (int i = 0; i < staffList.size(); i++) {
            if (i > 0) {
                MesAndonNoticeEntity notice = new MesAndonNoticeEntity();
                notice.setNoticeTime(LocalDateTime.now());
                notice.setNoticeType(AndonNoticeTypeEnum.NOTICE.getCode());
                notice.setStaffCode(staffList.get(i).getStaffCode());
                notice.setStaffName(staffList.get(i).getStaffName());
                notice.setRecordCode(mesAndonRecord.getCode());

                MessageDelayed messageDelayed = new MessageDelayed(notice, staffList.get(i - 1).getLimitTime());
                delayQueue.add(messageDelayed);
            }
        }

        // 给对应员工推送钉钉消息
        String toUsers = staffList.get(0).getStaffCode();
        String message = "安灯呼叫通知：\n" + "流程编号：" + result.getCode() + "\n" + "时间：" + timeStr() + "\n" + "异常类型：" + mesAndonRecord.getTypeName() + "\n" + "车间：" + result.getWorkshopName() + "\n" + "产线：" + result.getLineName() + "\n" + "\n" + "请及时接收";
        sendMessage(message, toUsers);
    }

    /**
     * 撤销
     *
     * @param id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long id) {
        MesAndonRecordEntity record = this.getById(id);
        if (Objects.isNull(record)) {
            throw new RuntimeException("获取处理信息失败");
        }
        MomUser user = SecurityUtils.getUser();
        if (!user.getUsername().equals(record.getCreateBy())) {
            throw new RuntimeException("必须由处理发起人撤销");
        }
        if (!AndonStateEnum.UN_RECEIVE.getCode().equals(record.getState()) && !AndonStateEnum.UP_UN_RECEIVE.getCode().equals(record.getState())) {
            throw new RuntimeException("未接收的呼叫才能撤销");
        }
        this.update(new LambdaUpdateWrapper<MesAndonRecordEntity>()
                .eq(MesAndonRecordEntity::getId, record.getId())
                .set(MesAndonRecordEntity::getState, AndonStateEnum.CANCEL.getCode())
        );
        List<MesAndonNoticeEntity> noticeList = noticeService.list(new LambdaQueryWrapper<MesAndonNoticeEntity>()
                .eq(MesAndonNoticeEntity::getRecordCode, record.getCode())
        );
        noticeService.remove(new LambdaQueryWrapper<MesAndonNoticeEntity>()
                .eq(MesAndonNoticeEntity::getRecordCode, record.getCode())
        );
        String toUsers = noticeList.stream().map(MesAndonNoticeEntity::getStaffCode).collect(Collectors.joining(","));
        String message = "安灯呼叫通知：\n" + "流程编号：" + record.getCode() + "\n" + "时间：" + timeStr() + "\n" + "异常类型：" + record.getTypeName() + "\n" + "车间：" + record.getWorkshopName() + "\n" + "产线：" + record.getLineName() + "\n" + "\n" + "发起人已撤销";
        // 发送消息到钉钉
        sendMessage(message, toUsers);
    }

    /**
     * 关闭
     *
     * @param id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void close(Long id) {
        MesAndonRecordEntity record = this.getById(id);
        if (Objects.isNull(record)) {
            throw new RuntimeException("获取处理信息失败");
        }
        MomUser user = SecurityUtils.getUser();
        if (!user.getUsername().equals(record.getCreateBy())) {
            throw new RuntimeException("必须由处理发起人撤销");
        }
        if (!AndonStateEnum.HANDLED.getCode().equals(record.getState())) {
            throw new RuntimeException("未接收的呼叫才能撤销");
        }
        this.update(new LambdaUpdateWrapper<MesAndonRecordEntity>()
                .eq(MesAndonRecordEntity::getId, record.getId())
                .set(MesAndonRecordEntity::getState, AndonStateEnum.CLOSED.getCode())
                .set(MesAndonRecordEntity::getCloseTime, LocalDateTime.now())
        );
    }

    /**
     * 接收
     *
     * @param id
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void receive(Long id) {
        MesAndonRecordEntity record = this.getById(id);
        if (Objects.isNull(record)) {
            throw new RuntimeException("获取处理信息失败");
        }
        MomUser user = SecurityUtils.getUser();
        List<String> handlerList = Arrays.asList(record.getHandlerCode().split(","));
        if (!handlerList.contains(String.valueOf(user.getId()))) {
            throw new RuntimeException("必须由指定处理人接收");
        }
        if (!AndonStateEnum.UN_RECEIVE.getCode().equals(record.getState()) && !AndonStateEnum.UP_UN_RECEIVE.getCode().equals(record.getState())) {
            throw new RuntimeException("只能接收未接收的处理");
        }

        this.update(new LambdaUpdateWrapper<MesAndonRecordEntity>()
                .eq(MesAndonRecordEntity::getId, record.getId())
                .set(MesAndonRecordEntity::getReceiverName, user.getName())
                .set(MesAndonRecordEntity::getReceiverCode, user.getId())
                .set(AndonStateEnum.UN_RECEIVE.getCode().equals(record.getState()), MesAndonRecordEntity::getState, AndonStateEnum.UN_HANDLE.getCode())
                .set(AndonStateEnum.UP_UN_RECEIVE.getCode().equals(record.getState()), MesAndonRecordEntity::getState, AndonStateEnum.UP_UN_HANDLE.getCode())
                .set(MesAndonRecordEntity::getReceiveTime, LocalDateTime.now())
        );

        List<MesAndonNoticeEntity> noticeList = noticeService.list(new LambdaQueryWrapper<MesAndonNoticeEntity>()
                .eq(MesAndonNoticeEntity::getRecordCode, record.getCode())
                .eq(MesAndonNoticeEntity::getNoticeType, "notice"));

        List<String> noticeStaff = new ArrayList<>(noticeList.stream().map(MesAndonNoticeEntity::getStaffCode).toList());

        noticeService.remove(new LambdaQueryWrapper<MesAndonNoticeEntity>()
                .eq(MesAndonNoticeEntity::getRecordCode, record.getCode())
                .ne(MesAndonNoticeEntity::getStaffCode, String.valueOf(user.getId()))
                .eq(MesAndonNoticeEntity::getNoticeType, AndonNoticeTypeEnum.HANDLE.getCode())
        );

        // 钉钉通知相关人员已接收
        noticeStaff.addAll(handlerList);
        String message = "安灯呼叫通知：\n" + "流程编号：" + record.getCode() + "\n" + "时间：" + timeStr() + "\n" + "异常类型：" + record.getTypeName() + "\n" + "车间：" + record.getWorkshopName() + "\n" + "产线：" + record.getLineName() + "\n" + "\n" + "处理人" + user.getName() + "已接收";
        sendMessage(message, String.join(",", noticeStaff));
    }

    /**
     * 处理
     *
     * @param vo
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handle(AndonRecordHandleVO vo) {
        MesAndonRecordEntity record = this.getById(vo.getId());
        if (Objects.isNull(record)) {
            throw new RuntimeException("获取处理信息失败");
        }
        MomUser user = SecurityUtils.getUser();
        if (!record.getReceiverCode().contains(String.valueOf(user.getId()))) {
            throw new RuntimeException("必须由接收人处理");
        }
        if (!AndonStateEnum.UN_HANDLE.getCode().equals(record.getState()) && !AndonStateEnum.UP_UN_HANDLE.getCode().equals(record.getState())) {
            throw new RuntimeException("接收后才能处理");
        }
        this.update(new LambdaUpdateWrapper<MesAndonRecordEntity>()
                .eq(MesAndonRecordEntity::getId, record.getId())
                .set(MesAndonRecordEntity::getState, AndonStateEnum.HANDLED.getCode())
                .set(MesAndonRecordEntity::getHandleTime, LocalDateTime.now())
                .set(MesAndonRecordEntity::getResult, vo.getResult())
        );
    }


    private String timeStr() {
        // 获取当前时间
        LocalDateTime currentTime = LocalDateTime.now();

        // 定义时间格式化器
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        // 格式化当前时间
        return currentTime.format(formatter);

    }

    private void sendMessage(String message, String users) {
        List<Long> userIdsList = Arrays.stream(users.split(",")).map(Long::parseLong).toList();
        List<SysUserEntity> userList = remoteUserService.getUserByIdInner(userIdsList.stream().toList()).getData();
        if (CollUtil.isEmpty(userList)) {
            throw new RuntimeException("查询用户数据失败！");
        }
        String userIds = userList.stream().map(SysUserEntity::getWxDingUserid).collect(Collectors.joining(","));
        //构建参数推送消息
        MessageDTO messageDTO = new MessageDTO();
        messageDTO.setCode(MES);
        messageDTO.setMessageType(MessageTypeEnum.TEXT.getType());
        messageDTO.setUserList(userIdsList);
        messageDTO.setUserIds(userIds);
        messageDTO.setMessage(message);
        messageService.pushPersonMsg(messageDTO);
    }
}