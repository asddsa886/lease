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
 * 浏览历史服务实现类。
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
        // 查询当前页的浏览历史记录。
        IPage<HistoryItemVo> resultPage = browsingHistoryMapper.pageItem(page, userId);
        List<HistoryItemVo> records = resultPage.getRecords();
        if (records == null || records.isEmpty()) {
            return resultPage;
        }

        // 批量查询房间图片，避免出现 N+1 查询。
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
        // 仅查询组装记录所需字段。
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

        // 给每条浏览记录补充房间图片信息。
        for (HistoryItemVo item : records) {
            item.setRoomGraphVoList(roomIdToGraphVoList.getOrDefault(item.getRoomId(), List.of()));
        }

        return resultPage;
    }

    @Async("browsingHistoryTaskExecutor")
    @Override
    public void saveHistory(Long userId, Long roomId) {
        if (userId == null || roomId == null) {
            return;
        }

        Date browseTime = new Date();
        int updated = browsingHistoryMapper.touchByUserIdAndRoomId(userId, roomId, browseTime);
        if (updated > 0) {
            return;
        }

        BrowsingHistory browsingHistory = new BrowsingHistory();
        browsingHistory.setUserId(userId);
        browsingHistory.setRoomId(roomId);
        browsingHistory.setBrowseTime(browseTime);
        browsingHistoryMapper.insert(browsingHistory);
    }
}
