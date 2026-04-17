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
 * @description 閽堝琛ㄣ€恇rowsing_history(娴忚鍘嗗彶)銆戠殑鏁版嵁搴撴搷浣淪ervice瀹炵幇
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
        // 1) 鍒嗛〉鍙煡涓绘暟鎹紙淇濊瘉鍒嗛〉鍑嗙‘锛?
        IPage<HistoryItemVo> resultPage = browsingHistoryMapper.pageItem(page, userId);
        List<HistoryItemVo> records = resultPage.getRecords();
        if (records == null || records.isEmpty()) {
            return resultPage;
        }

        // 2) 鎵归噺鏌ュ浘鐗囷紙鎶?N+1 鍙樻垚 1 娆?in 鏌ヨ锛?
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
        // 浠呮煡璇㈢粍瑁呮墍闇€瀛楁
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

        // 3) 鍥炲～鍒?records
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
