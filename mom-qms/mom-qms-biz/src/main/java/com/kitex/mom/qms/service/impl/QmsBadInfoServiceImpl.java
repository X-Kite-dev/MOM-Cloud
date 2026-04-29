package com.kitex.mom.qms.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.query.utils.QueryGenerator;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.qms.dto.req.QmsBadHandleReq;
import com.kitex.mom.qms.dto.req.QmsBadJudgeReq;
import com.kitex.mom.qms.entity.QmsBadInfoDetailEntity;
import com.kitex.mom.qms.entity.QmsBadInfoEntity;
import com.kitex.mom.qms.enums.InspectionTaskStatusEnum;
import com.kitex.mom.qms.mapper.QmsBadInfoMapper;
import com.kitex.mom.qms.service.QmsBadInfoDetailService;
import com.kitex.mom.qms.service.QmsBadInfoService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 不合格品管理
 *
 * @author kitex
 * @since 2025-01-21
 */
@Service
@AllArgsConstructor
public class QmsBadInfoServiceImpl extends ServiceImpl<QmsBadInfoMapper, QmsBadInfoEntity> implements QmsBadInfoService {

    private final RemoteMaterialService remoteMaterialService;
    private final QmsBadInfoDetailService detailService;


    /**
     * 分页
     *
     * @param page
     * @param qmsBadInfoEntity
     * @return
     */
    public Page<QmsBadInfoEntity> queryPage(Page<QmsBadInfoEntity> page, QmsBadInfoEntity qmsBadInfoEntity) {
        QueryWrapper<QmsBadInfoEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(qmsBadInfoEntity);
        autoQueryWrapper.lambda()
                .ne(QmsBadInfoEntity::getUnJudgeNum, 0)
                .orderByDesc(QmsBadInfoEntity::getCreateTime);
        return this.page(page, autoQueryWrapper);
    }

    /**
     * 判定列表
     *
     * @param page
     * @param qmsBadInfoDetailEntity
     * @return
     */
    public Page<QmsBadInfoDetailEntity> queryDetailPage(Page<QmsBadInfoDetailEntity> page, QmsBadInfoDetailEntity qmsBadInfoDetailEntity) {
        QueryWrapper<QmsBadInfoDetailEntity> autoQueryWrapper = QueryGenerator.initQueryWrapper(qmsBadInfoDetailEntity);
        autoQueryWrapper.lambda().orderByDesc(QmsBadInfoDetailEntity::getCreateTime);
        return detailService.page(page, autoQueryWrapper);
    }

    /**
     * 状态数
     *
     * @param qmsBadInfoDetailEntity
     * @return
     */
    public Map<String, Long> detailCount(QmsBadInfoDetailEntity qmsBadInfoDetailEntity) {
        QueryWrapper<QmsBadInfoDetailEntity> autoQueryWrapper = new QueryWrapper<>();
        autoQueryWrapper.lambda().like(StrUtil.isNotBlank(qmsBadInfoDetailEntity.getMatCode()), QmsBadInfoDetailEntity::getMatCode, qmsBadInfoDetailEntity.getMatCode())
                .like(StrUtil.isNotBlank(qmsBadInfoDetailEntity.getMatName()), QmsBadInfoDetailEntity::getMatName, qmsBadInfoDetailEntity.getMatName())
                .eq(StrUtil.isNotBlank(qmsBadInfoDetailEntity.getBadType()), QmsBadInfoDetailEntity::getBadType, qmsBadInfoDetailEntity.getBadType())
                .eq(StrUtil.isNotBlank(qmsBadInfoDetailEntity.getResourceType()), QmsBadInfoDetailEntity::getResourceType, qmsBadInfoDetailEntity.getResourceType());
        autoQueryWrapper.lambda().orderByDesc(QmsBadInfoDetailEntity::getCreateTime);
        return detailService.list(autoQueryWrapper)
                .stream()
                .collect(Collectors.groupingBy(QmsBadInfoDetailEntity::getStatus, Collectors.counting()));
    }

    /**
     * 查询详情
     *
     * @param id
     * @return
     */
    public QmsBadInfoDetailEntity queryDetailById(Long id) {
        return detailService.getById(id);
    }

    /**
     * 不合格品填报
     *
     * @param qmsBadInfoEntity
     */
    public void add(QmsBadInfoEntity qmsBadInfoEntity) {
        // 回填规则型号字段
        if (StrUtil.isNotEmpty(qmsBadInfoEntity.getMatCode()) && StrUtil.isEmpty(qmsBadInfoEntity.getMatModel())) {
            MdmMaterialEntity matDetail = remoteMaterialService.getDetails(qmsBadInfoEntity.getMatCode()).getData();
            if (Objects.nonNull(matDetail)) {
                qmsBadInfoEntity.setMatModel(matDetail.getSpecificationModel());
            }
        }
        qmsBadInfoEntity.setUnJudgeNum(qmsBadInfoEntity.getNum());
        qmsBadInfoEntity.setStatus(InspectionTaskStatusEnum.WAIT.name());
        this.save(qmsBadInfoEntity);
    }

    /**
     * 判定
     *
     * @param req
     */
    public void judge(QmsBadJudgeReq req) {
        QmsBadInfoEntity info = this.getById(req.getId());
        if (Objects.isNull(info)) {
            throw new RuntimeException("参数错误，请检查");
        }
        if (req.getNum() > info.getUnJudgeNum()) {
            throw new RuntimeException("此次判定数量大于未判定数量，判定失败");
        }
        // 更新主表 未判定数量更新
        info.setUnJudgeNum(info.getUnJudgeNum() - req.getNum());
        info.setStatus(InspectionTaskStatusEnum.RUNNING.name());
        this.updateById(info);
        // 保存记录表
        QmsBadInfoDetailEntity detail = new QmsBadInfoDetailEntity();
        BeanUtil.copyProperties(info, detail);
        detail.setId(null);
        detail.setBadId(info.getId());
        detail.setJudgeNum(req.getNum());
        detail.setBadType(req.getBadType());
        detail.setBadTypeName(req.getBadTypeName());
        detail.setBadReason(req.getBadReason());
        detail.setStatus("0");
        detailService.save(detail);
    }

    /**
     * 处置
     *
     * @param req
     */
    public void handle(QmsBadHandleReq req) {
        // 更新判定记录表
        QmsBadInfoDetailEntity detail = detailService.getById(req.getDetailId());
        detail.setHandleResult(req.getHandleResult());
        detail.setHandleRemark(req.getHandleRemark());
        detail.setHandleFile(req.getHandleFile());
        MomUser currentUser = SecurityUtils.getUser();
        detail.setHandlePersonId(currentUser.getUsername());
        detail.setHandlePersonName(currentUser.getName());
        detail.setStatus("1");
        detail.setHandleTime(new Date());
        detailService.updateById(detail);
        // TODO 处置完的操作 1 紧急放行-》 入库通知（紧急放行下，进行标注 2  让步接受 -》 入库通知单（让步接收，标注 3  退货-》 退货单据

        QmsBadInfoEntity info = this.getById(detail.getBadId());
        // 若已全部处置完成，则更新主表状态为已完成
        if (Objects.nonNull(info)) {
            Integer handleNum = detailService.list(new LambdaQueryWrapper<QmsBadInfoDetailEntity>().eq(QmsBadInfoDetailEntity::getBadId, info.getId())
                            .eq(QmsBadInfoDetailEntity::getStatus, "1"))
                    .stream().mapToInt(QmsBadInfoDetailEntity::getJudgeNum).sum();
            if (Objects.equals(handleNum, info.getNum())) {
                info.setStatus(InspectionTaskStatusEnum.FINISHED.name());
                this.updateById(info);
            }
        }
    }
}
