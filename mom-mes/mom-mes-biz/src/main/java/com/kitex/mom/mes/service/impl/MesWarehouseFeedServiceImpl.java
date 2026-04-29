package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mdm.feign.RemoteProcessRouteService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesWarehouseFeedEntity;
import com.kitex.mom.mes.enums.WarehouseInBizTypeEnum;
import com.kitex.mom.mes.enums.WarehouseOutBizTypeEnum;
import com.kitex.mom.mes.event.FeedRecordSaveEvent;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.mapper.MesWarehouseFeedMapper;
import com.kitex.mom.mes.service.MesWarehouseFeedService;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import com.kitex.mom.qms.api.feign.RemotePurchaseService;
import com.kitex.mom.qms.entity.QmsInspectionPurchaseTaskEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 线边库投料记录
 *
 * @author kitex
 * @date 2025-02-20 17:22:19
 */
@Service
@RequiredArgsConstructor
public class MesWarehouseFeedServiceImpl extends ServiceImpl<MesWarehouseFeedMapper, MesWarehouseFeedEntity> implements MesWarehouseFeedService {

    private final MesWarehouseLiveStockService liveStockService;
    private final MesTaskMapper mesTaskMapper;
    private final RemoteMaterialService remoteMaterialService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final RemoteProcessRouteService remoteProcessRouteService;
    private final RemotePurchaseService remotePurchaseService;
    private final RemoteParamService remoteParamService;


    /**
     * pad投料接口
     * 投料记录，相同的合并一起；需要出库；校验条码判断物料是不是bom里面的；
     */
    @Override
    @Transactional
    public Boolean saveFeed(MesWarehouseFeedEntity feed) {
        String backFlushFlag = remoteParamService.getByKey("BACK_FLUSH").getData();
        if ("1".equals(backFlushFlag)) {
            throw new RuntimeException("已配置倒冲，投料失败");
        }
        // 1.判断物料是不是bom里面的
        MesTaskEntity mesTask = Optional.ofNullable(mesTaskMapper.selectOne(new LambdaQueryWrapper<MesTaskEntity>()
                        .eq(MesTaskEntity::getCode, feed.getTaskCode())
                , false)).orElseThrow(() -> new CheckedException("未查找到此生产工单！"));
        String bomSnapshot = mesTask.getBomSnapshot();
        if (StrUtil.isNotBlank(bomSnapshot)) {
            List<MdmBomChildListDTO> list = JSONUtil.toList(bomSnapshot, MdmBomChildListDTO.class);
            List<MdmBomChildListDTO> bomChildList = list.stream().filter(it -> feed.getMatCode().equals(it.getMaterialCode())).toList();
            if (CollUtil.isEmpty(bomChildList)) {
                throw new CheckedException("产品bom子项中没有此物料！");
            }
        }
        // 2.判断是否存在相同投料记录并保存
        feed.setFeedTime(LocalDateTime.now());
        MomUser user = SecurityUtils.getUser();
        feed.setFeedPersonCode(user.getUsername());
        feed.setFeedPersonName(user.getName());
        // 查找物料信息
        MdmMaterialEntity material = Optional.ofNullable(remoteMaterialService.getDetails(feed.getMatCode()).getData()).orElseThrow(() -> new CheckedException("未查找到此物料信息！"));
        feed.setMatUnit(material.getBasicUnit());
        feed.setMatSpec(material.getSpecificationModel());
        // 设置物料锁定状态为未锁定
        feed.setIsLock(Boolean.FALSE);

        // 查询质量 得到质检结果
        QmsInspectionPurchaseTaskEntity inspectionPurchaseTask = remotePurchaseService.getTaskByMatCodeAndSupplierCodeAndBatchNo(feed.getMatCode(), feed.getSupplierCode(), feed.getMatBatch()).getData();
        if (Objects.nonNull(inspectionPurchaseTask)) {
            feed.setInspectionTaskId(inspectionPurchaseTask.getId());
            feed.setInspectionResult(inspectionPurchaseTask.getResult());
        }
        feed.setFactoryCode(mesTask.getFactoryCode());
        save(feed);
        // 3.出库
        MesWarehouseLiveStockInoutDTO inoutDTO = new MesWarehouseLiveStockInoutDTO();
        BeanUtil.copyProperties(feed, inoutDTO);
        inoutDTO.setBillNo(feed.getArrangeCode());
        inoutDTO.setNum(feed.getFeedNum());
        inoutDTO.setProduceTaskCode(null);
        inoutDTO.setBizType(WarehouseOutBizTypeEnum.FEEDING.getCode());
        liveStockService.outStock(inoutDTO);
        // 4.保存操作记录
        MesTaskReportFeedVO params = new MesTaskReportFeedVO();
        BeanUtil.copyProperties(feed, params);
        params.setWorkerCode(feed.getFeedPersonCode());
        params.setWorkerName(feed.getFeedPersonName());
        params.setFeedNum(feed.getFeedNum());
        params.setTransferCode(feed.getMatBarcode());
        params.setFactoryCode(mesTask.getFactoryCode());
        applicationEventPublisher.publishEvent(new FeedRecordSaveEvent(this, params));
        return Boolean.TRUE;
    }

    /**
     * 退料
     *
     * @param feed
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void feedBack(MesWarehouseFeedEntity feed) {
        MesWarehouseLiveStockInoutDTO inoutDTO = new MesWarehouseLiveStockInoutDTO();
        BeanUtil.copyProperties(feed, inoutDTO);
        inoutDTO.setBillNo(feed.getArrangeCode());
        inoutDTO.setProduceTaskCode(feed.getProduceTaskCode());
        inoutDTO.setNum(feed.getFeedNum().subtract(feed.getUsedNum()));
        inoutDTO.setBizType(WarehouseInBizTypeEnum.SURPLUS.getCode());
        liveStockService.inStock(inoutDTO);
    }

    /**
     * 消耗
     *
     * @param useDTO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void use(MesWarehouseUseDTO useDTO) {
        // 检验投料数量是否足够
//        List<MdmProcessProcedureMatEntity> feedConfigList = remoteProcessRouteService.getMatByCode(useDTO.getProcessRouteCode(), useDTO.getRouteCode()).getData();
        // 查询快照中保存的投料配置
        List<ProcessProcedureDTO> processList = JSONUtil.toList(useDTO.getSnapshot(), ProcessProcedureDTO.class);
        Optional<ProcessProcedureDTO> routeDTO = processList.stream().filter(item -> item.getRouteCode().equals(useDTO.getRouteCode())).findFirst();
        List<ProcessProcedureDTO.ProcessProcedureMatDTO> feedConfigList = null;
        if (routeDTO.isPresent()) {
            feedConfigList = routeDTO.get().getMdmProcessProcedureMatList();
        }
        // 若没有配置投料绑定，则不进行消耗
        if (CollUtil.isEmpty(feedConfigList)) {
            return;
        }
        List<MesWarehouseFeedEntity> feedList = this.list(new LambdaQueryWrapper<MesWarehouseFeedEntity>()
                .eq(MesWarehouseFeedEntity::getArrangeCode, useDTO.getArrangeCode())
                .ne(MesWarehouseFeedEntity::getIsLock, Boolean.TRUE) // 查询未锁定的
                .orderByAsc(MesWarehouseFeedEntity::getFeedTime)); // 按投料时间升序排序，实现先入先出
        List<MesWarehouseFeedEntity> updateList = new ArrayList<>();

        // 按物料分组统计已投料数量
        Map<String, BigDecimal> feedSumByMatCode = feedList.stream()
                .collect(Collectors.groupingBy(
                        MesWarehouseFeedEntity::getMatCode,
                        Collectors.reducing(BigDecimal.ZERO,
                                feed -> feed.getFeedNum().subtract(Objects.isNull(feed.getUsedNum()) ? BigDecimal.ZERO : feed.getUsedNum()),
                                BigDecimal::add)
                ));

        // 遍历配置表检查投料是否足够
        for (ProcessProcedureDTO.ProcessProcedureMatDTO config : feedConfigList) {
            String matCode = config.getMaterialCode();
            BigDecimal requiredQuantity = useDTO.getReportNum().multiply(config.getQuantity());
            BigDecimal actualQuantity = feedSumByMatCode.getOrDefault(matCode, BigDecimal.ZERO);

            if (actualQuantity.compareTo(requiredQuantity) < 0) {
                throw new RuntimeException(String.format("物料[%s]投料数量不足，需求数量：%s，实际投料数量：%s",
                        matCode, requiredQuantity, actualQuantity.setScale(2, RoundingMode.HALF_UP)));
            }

            // 按先入先出原则更新usedNum
            BigDecimal remainingToUse = requiredQuantity;
            for (MesWarehouseFeedEntity feed : feedList) {
                if (Objects.isNull(feed.getUsedNum())) {
                    feed.setUsedNum(BigDecimal.ZERO);
                }
                if (!feed.getMatCode().equals(matCode)) {
                    continue;
                }

                BigDecimal availableToUse = feed.getFeedNum().subtract(feed.getUsedNum());
                if (availableToUse.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                if (remainingToUse.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }

                if (availableToUse.compareTo(remainingToUse) >= 0) {
                    // 当前记录足够消耗剩余需求
                    feed.setUsedNum(feed.getUsedNum().add(remainingToUse));
                    updateList.add(feed);
                    remainingToUse = BigDecimal.ZERO;
                } else {
                    // 当前记录不够，全部消耗掉
                    feed.setUsedNum(feed.getFeedNum());
                    updateList.add(feed);
                    remainingToUse = remainingToUse.subtract(availableToUse);
                }
            }
        }

        // 批量更新usedNum
        if (!updateList.isEmpty()) {
            this.updateBatchById(updateList);
        }
    }

    /**
     * 查询投料列表
     *
     * @param query
     * @return
     */
    @Override
    public List<MesWarehouseFeedLeftDTO> queryTaskFeedList(MesWarehouseFeedLeftDTO query) {
        return baseMapper.queryTaskFeedList(query);
    }

    /**
     * 余料分页查询
     *
     * @param pageParam
     * @param query
     * @return
     */
    @Override
    public IPage<MesWarehouseFeedLeftDTO> queryLeftPage(Page<MesWarehouseFeedLeftDTO> pageParam, MesWarehouseFeedLeftDTO query) {
        return baseMapper.queryLeftPage(pageParam, query);
    }

}