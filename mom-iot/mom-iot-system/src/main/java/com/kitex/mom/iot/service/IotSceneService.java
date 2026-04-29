package com.kitex.mom.iot.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.iot.dto.IotSceneDTO;
import com.kitex.mom.iot.entity.IotSceneEntity;

import java.util.List;

/**
 * 场景联动Service接口
 */
public interface IotSceneService extends IService<IotSceneEntity> {

    /**
     * 分页查询场景列表
     *
     * @param page  分页对象
     * @param query 查询参数
     * @return 分页结果
     */
    IPage<IotSceneEntity> getScenePage(Page<IotSceneEntity> page, IotSceneEntity query);

    /**
     * 获取场景详情
     *
     * @param id 场景ID
     * @return 场景详情
     */
    IotSceneDTO getSceneDetail(Long id);

    /**
     * 新增场景
     *
     * @param dto 场景数据
     * @return 是否成功
     */
    boolean addScene(IotSceneDTO dto);

    /**
     * 更新场景
     *
     * @param dto 场景数据
     * @return 是否成功
     */
    boolean updateScene(IotSceneDTO dto);

    /**
     * 删除场景
     *
     * @param ids 场景ID列表
     * @return 是否成功
     */
    boolean deleteSceneByIds(List<Long> ids);

    /**
     * 启用/禁用场景
     *
     * @param id     场景ID
     * @param status 状态：0-禁用，1-启用
     * @return 是否成功
     */
    boolean updateSceneStatus(Long id, Integer status);

    /**
     * 执行场景
     *
     * @param id 场景ID
     * @return 执行结果
     */
    boolean executeScene(Long id);

    /**
     * 保存场景设计
     *
     * @param dto 场景数据
     * @return 是否成功
     */
    boolean saveSceneDesign(IotSceneDTO dto);
} 