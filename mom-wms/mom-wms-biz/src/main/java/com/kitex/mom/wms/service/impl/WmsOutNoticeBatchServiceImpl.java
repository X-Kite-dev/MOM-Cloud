package com.kitex.mom.wms.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.wms.dto.WmsInNoticeQueryDTO;
import com.kitex.mom.wms.entity.WmsOutNoticeBatchDetailEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeBatchEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeDetailEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import com.kitex.mom.wms.enums.InOutNoticeStateEnum;
import com.kitex.mom.wms.enums.ReviewStateEnum;
import com.kitex.mom.wms.mapper.WmsOutNoticeBatchMapper;
import com.kitex.mom.wms.mapper.WmsOutNoticeDetailMapper;
import com.kitex.mom.wms.service.WmsOutNoticeBatchDetailService;
import com.kitex.mom.wms.service.WmsOutNoticeBatchService;
import com.kitex.mom.wms.service.outNotice.WmsOutNoticeFactory;
import com.kitex.mom.wms.service.outNotice.WmsOutNoticeHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 批量出库表
 *
 * @author kitex
 * @date 2025-04-09 16:04:44
 */
@Service
@RequiredArgsConstructor
public class WmsOutNoticeBatchServiceImpl extends ServiceImpl<WmsOutNoticeBatchMapper, WmsOutNoticeBatchEntity> implements WmsOutNoticeBatchService {

    private final WmsOutNoticeBatchDetailService wmsOutNoticeBatchDetailService;

    private final WmsOutNoticeDetailMapper wmsOutNoticeDetailMapper;

    @Override
    public WmsOutNoticeBatchEntity getNoticeBatchById(Long id) {
        WmsOutNoticeBatchEntity wmsOutNoticeBatchEntity = this.getById(id);
        //查询明细
        List<WmsOutNoticeBatchDetailEntity> batchDetailList = wmsOutNoticeBatchDetailService.list(new LambdaQueryWrapper<WmsOutNoticeBatchDetailEntity>()
                .eq(WmsOutNoticeBatchDetailEntity::getBillCode, wmsOutNoticeBatchEntity.getBillCode()));
        //根据明细查询通知单及其明细信息
        List<Long> noticeIdList = batchDetailList.stream().map(WmsOutNoticeBatchDetailEntity::getNoticeId).distinct().toList();
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(null);
        List<WmsOutNoticeEntity> noticeList = handler.getNoticeList(noticeIdList);

        List<WmsOutNoticeDetailEntity> noticeDetailList = new ArrayList<>();
        noticeList.forEach(item -> {
            if (CollUtil.isNotEmpty(item.getDetailList())) {
                noticeDetailList.addAll(item.getDetailList());
            }
        });
        wmsOutNoticeBatchEntity.setNoticeList(noticeList);
        wmsOutNoticeBatchEntity.setNoticeDetailList(noticeDetailList);
        return wmsOutNoticeBatchEntity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveNoticeBatch(WmsOutNoticeBatchEntity wmsOutNoticeBatch) {
        if (CollUtil.isEmpty(wmsOutNoticeBatch.getNoticeList())) {
            throw new CheckedException("明细不能为空！");
        }
        MomUser user = SecurityUtils.getUser();
        //保存主表
        wmsOutNoticeBatch.setCreateByName(user.getName());
        wmsOutNoticeBatch.setState(ReviewStateEnum.UN_COMPLETE.getCode());
        this.save(wmsOutNoticeBatch);
        //保存明细
        List<WmsOutNoticeBatchDetailEntity> batchDetailEntityList = new ArrayList<>();
        wmsOutNoticeBatch.getNoticeList().forEach(item -> {
            WmsOutNoticeBatchDetailEntity batchDetailEntity = new WmsOutNoticeBatchDetailEntity();
            batchDetailEntity.setNoticeId(item.getId());
            batchDetailEntity.setBillCode(wmsOutNoticeBatch.getBillCode());
            batchDetailEntityList.add(batchDetailEntity);
        });
        wmsOutNoticeBatchDetailService.saveBatch(batchDetailEntityList);
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateNoticeBatchById(WmsOutNoticeBatchEntity wmsOutNoticeBatch) {
        if (CollUtil.isEmpty(wmsOutNoticeBatch.getNoticeList())) {
            throw new CheckedException("明细不能为空！");
        }
        //更新主表
        this.updateById(wmsOutNoticeBatch);
        //更新明细
        wmsOutNoticeBatchDetailService.remove(new LambdaQueryWrapper<WmsOutNoticeBatchDetailEntity>()
                .eq(WmsOutNoticeBatchDetailEntity::getBillCode, wmsOutNoticeBatch.getBillCode()));

        List<WmsOutNoticeBatchDetailEntity> batchDetailEntityList = new ArrayList<>();
        wmsOutNoticeBatch.getNoticeList().forEach(item -> {
            WmsOutNoticeBatchDetailEntity batchDetailEntity = new WmsOutNoticeBatchDetailEntity();
            batchDetailEntity.setNoticeId(item.getId());
            batchDetailEntity.setBillCode(wmsOutNoticeBatch.getBillCode());
            batchDetailEntityList.add(batchDetailEntity);
        });
        wmsOutNoticeBatchDetailService.saveBatch(batchDetailEntityList);
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeNoticeBatchById(ArrayList<Long> toList) {
        //删除主表
        List<WmsOutNoticeBatchEntity> wmsOutNoticeBatchEntities = this.listByIds(toList);
        List<String> stringList = wmsOutNoticeBatchEntities.stream().map(WmsOutNoticeBatchEntity::getBillCode).distinct().toList();
        this.removeBatchByIds(wmsOutNoticeBatchEntities);
        //删除子表
        wmsOutNoticeBatchDetailService.remove(new LambdaQueryWrapper<WmsOutNoticeBatchDetailEntity>()
                .in(WmsOutNoticeBatchDetailEntity::getBillCode, stringList));
        return Boolean.TRUE;
    }

    @Override
    public Page<WmsOutNoticeEntity> selectNoticePage(SearchPage page, WmsInNoticeQueryDTO queryDTO) {
        QueryWrapper<WmsOutNoticeEntity> wrapper = getMainQueryWrapper(queryDTO);
        WmsOutNoticeHandler handler = WmsOutNoticeFactory.getHandler(queryDTO.getBillType());
        Page<WmsOutNoticeEntity> result = handler.page(page.toMpPage(), wrapper);
        if (CollUtil.isNotEmpty(result.getRecords())) {
            List<WmsOutNoticeEntity> records = result.getRecords();
            List<Long> noticeList = records.stream().map(BaseEntity::getId).distinct().toList();
            List<WmsOutNoticeDetailEntity> wmsOutNoticeDetailEntities = wmsOutNoticeDetailMapper.selectList(new LambdaQueryWrapper<WmsOutNoticeDetailEntity>()
                    .in(WmsOutNoticeDetailEntity::getNoticeId, noticeList));
            Map<Long, List<WmsOutNoticeDetailEntity>> detailsMap = wmsOutNoticeDetailEntities.stream()
                    .collect(Collectors.groupingBy(WmsOutNoticeDetailEntity::getNoticeId, Collectors.toList()));
            records.forEach(item -> {
                List<WmsOutNoticeDetailEntity> detailList = detailsMap.get(item.getId());
                if (CollUtil.isNotEmpty(detailList)) {
                    item.setDetailList(detailList);
                }
            });
        }
        return result;
    }

	/**
	 * 按完整原通知身份构造通知列表和主表导出条件；不改变既有业务类型、状态和组织约束。
	 *
	 * @param queryDTO 查询参数，空通知号不筛选，非法通知号拒绝查询
	 * @return 通知主表查询条件，通知号以Long等值绑定
	 */
	public QueryWrapper<WmsOutNoticeEntity> getMainQueryWrapper(WmsInNoticeQueryDTO queryDTO) {
		Long noticeId = queryDTO.noticeIdValue();
		QueryWrapper<WmsOutNoticeEntity> wrapper = new QueryWrapper<>();
		if (CollUtil.isNotEmpty(queryDTO.getCreateTime())) {
			wrapper.lambda().ge(BaseCore::getCreateTime, queryDTO.getCreateTime().get(0));
			if (queryDTO.getCreateTime().size() > 1) {
				wrapper.lambda().lt(BaseCore::getCreateTime, queryDTO.getCreateTime().get(1));
			}
		}
		wrapper.lambda().eq(noticeId != null, WmsOutNoticeEntity::getId, noticeId)
				.like(StrUtil.isNotBlank(queryDTO.getCreateBy()), BaseCore::getCreateBy, queryDTO.getCreateBy())
				.eq(StrUtil.isNotBlank(queryDTO.getBillType()), WmsOutNoticeEntity::getBillType,
						queryDTO.getBillType())
				.eq(StrUtil.isNotBlank(queryDTO.getWarehouseCode()), WmsOutNoticeEntity::getWarehouseCode,
						queryDTO.getWarehouseCode())
				.ne(WmsOutNoticeEntity::getState, InOutNoticeStateEnum.FINISHED.getCode())
				.ne(WmsOutNoticeEntity::getState, InOutNoticeStateEnum.CANCELED.getCode())
				.orderByDesc(BaseCore::getCreateTime);
		return wrapper;
	}


}
