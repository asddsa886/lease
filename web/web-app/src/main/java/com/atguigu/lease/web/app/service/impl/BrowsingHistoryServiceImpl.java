package com.atguigu.lease.web.app.service.impl;

import com.atguigu.lease.model.entity.BrowsingHistory;
import com.atguigu.lease.model.entity.GraphInfo;
import com.atguigu.lease.model.enums.ItemType;
import com.atguigu.lease.web.app.mapper.BrowsingHistoryMapper;
import com.atguigu.lease.web.app.mapper.GraphInfoMapper;
import com.atguigu.lease.web.app.service.BrowsingHistoryService;
import com.atguigu.lease.web.app.vo.graph.GraphVo;
import com.atguigu.lease.web.app.vo.history.HistoryItemVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @author liubo
 * @description 针对表【browsing_history(浏览历史)】的数据库操作Service实现
 * @createDate 2023-07-26 11:12:39
 */
@Service
public class BrowsingHistoryServiceImpl extends ServiceImpl<BrowsingHistoryMapper, BrowsingHistory>
        implements BrowsingHistoryService {
    @Autowired
    private BrowsingHistoryMapper browsingHistoryMapper;

    @Autowired
    private GraphInfoMapper graphInfoMapper;

    @Override
    public IPage<HistoryItemVo> pageItem(Page<HistoryItemVo> page, Long userId) {
        // 1) 分页只查主数据（保证分页准确）
        IPage<HistoryItemVo> resultPage = browsingHistoryMapper.pageItem(page, userId);
        List<HistoryItemVo> records = resultPage.getRecords();
        if (records == null || records.isEmpty()) {
            return resultPage;
        }

        // 2) 批量查图片（把 N+1 变成 1 次 in 查询）
        List<Long> roomIdList = records.stream()
                .map(HistoryItemVo::getRoomId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (roomIdList.isEmpty()) {
            return resultPage;
        }

        LambdaQueryWrapper<GraphInfo> graphQuery = new LambdaQueryWrapper<>();
        graphQuery.eq(GraphInfo::getItemType, ItemType.ROOM);
        graphQuery.in(GraphInfo::getItemId, roomIdList);
        // 仅查询组装所需字段
        graphQuery.select(GraphInfo::getItemId, GraphInfo::getName, GraphInfo::getUrl);

        List<GraphInfo> graphInfoList = graphInfoMapper.selectList(graphQuery);

        Map<Long, List<GraphVo>> roomIdToGraphVoList = graphInfoList.stream()
                .collect(Collectors.groupingBy(
                        GraphInfo::getItemId,
                        Collectors.mapping(
                                g -> GraphVo.builder().name(g.getName()).url(g.getUrl()).build(),
                                Collectors.toList()
                        )
                ));

        // 3) 回填到 records
        for (HistoryItemVo item : records) {
            item.setRoomGraphVoList(roomIdToGraphVoList.getOrDefault(item.getRoomId(), List.of()));
        }

        return resultPage;
    }

    @Async
    @Override
    public void saveHistory(Long userId, Long id) {

        LambdaQueryWrapper<BrowsingHistory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BrowsingHistory::getUserId, userId);
        wrapper.eq(BrowsingHistory::getRoomId, id);

        BrowsingHistory browsingHistory = browsingHistoryMapper.selectOne(wrapper);
        if (browsingHistory == null) {
            browsingHistory = new BrowsingHistory();
            browsingHistory.setUserId(userId);
            browsingHistory.setRoomId(id);
            browsingHistory.setBrowseTime(new Date());
            browsingHistoryMapper.insert(browsingHistory);
        } else {
            browsingHistory.setBrowseTime(new Date());
            browsingHistoryMapper.updateById(browsingHistory);
        }

    }
}