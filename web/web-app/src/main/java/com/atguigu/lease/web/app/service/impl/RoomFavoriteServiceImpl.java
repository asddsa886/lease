package com.atguigu.lease.web.app.service.impl;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.login.LoginUser;
import com.atguigu.lease.common.login.LoginUserHolder;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.model.entity.GraphInfo;
import com.atguigu.lease.model.entity.RoomFavorite;
import com.atguigu.lease.model.enums.ItemType;
import com.atguigu.lease.web.app.mapper.GraphInfoMapper;
import com.atguigu.lease.web.app.mapper.RoomFavoriteMapper;
import com.atguigu.lease.web.app.mapper.RoomInfoMapper;
import com.atguigu.lease.web.app.service.RoomFavoriteService;
import com.atguigu.lease.web.app.vo.favorite.RoomFavoriteItemVo;
import com.atguigu.lease.web.app.vo.graph.GraphVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RoomFavoriteServiceImpl extends ServiceImpl<RoomFavoriteMapper, RoomFavorite>
        implements RoomFavoriteService {

    private final RoomFavoriteMapper roomFavoriteMapper;
    private final RoomInfoMapper roomInfoMapper;
    private final GraphInfoMapper graphInfoMapper;

    public RoomFavoriteServiceImpl(RoomFavoriteMapper roomFavoriteMapper,
                                   RoomInfoMapper roomInfoMapper,
                                   GraphInfoMapper graphInfoMapper) {
        this.roomFavoriteMapper = roomFavoriteMapper;
        this.roomInfoMapper = roomInfoMapper;
        this.graphInfoMapper = graphInfoMapper;
    }

    @Override
    public void saveFavorite(Long roomId) {
        saveFavorite(currentUserId(), roomId);
    }

    @Override
    public void saveFavorite(Long userId, Long roomId) {
        validateUserAndRoom(userId, roomId);
        if (findExisting(userId, roomId) != null) {
            return;
        }

        RoomFavorite favorite = new RoomFavorite();
        favorite.setUserId(userId);
        favorite.setRoomId(roomId);
        roomFavoriteMapper.insert(favorite);
    }

    @Override
    public void removeFavorite(Long roomId) {
        removeFavorite(currentUserId(), roomId);
    }

    @Override
    public void removeFavorite(Long userId, Long roomId) {
        validateUserAndRoomId(userId, roomId);
        LambdaQueryWrapper<RoomFavorite> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(RoomFavorite::getUserId, userId)
                .eq(RoomFavorite::getRoomId, roomId);
        roomFavoriteMapper.delete(queryWrapper);
    }

    @Override
    public IPage<RoomFavoriteItemVo> pageItem(Page<RoomFavoriteItemVo> page) {
        return pageItem(page, currentUserId());
    }

    @Override
    public IPage<RoomFavoriteItemVo> pageItem(Page<RoomFavoriteItemVo> page, Long userId) {
        validateUserId(userId);
        IPage<RoomFavoriteItemVo> result = roomFavoriteMapper.pageItem(page, userId);
        fillRoomGraphs(result.getRecords());
        return result;
    }

    @Override
    public boolean isFavorite(Long userId, Long roomId) {
        if (userId == null || roomId == null) {
            return false;
        }
        return findExisting(userId, roomId) != null;
    }

    @Override
    public Set<Long> favoriteRoomIds(Long userId, List<Long> roomIds) {
        if (userId == null || roomIds == null || roomIds.isEmpty()) {
            return Set.of();
        }
        List<Long> distinctRoomIds = roomIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (distinctRoomIds.isEmpty()) {
            return Set.of();
        }

        Set<Long> favoriteSet = new LinkedHashSet<>(roomFavoriteMapper.selectFavoriteRoomIds(userId, distinctRoomIds));
        return distinctRoomIds.stream()
                .filter(favoriteSet::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Long currentUserId() {
        LoginUser currentUser = LoginUserHolder.get();
        if (currentUser == null || currentUser.getId() == null) {
            throw new LeaseException(ResultCodeEnum.APP_LOGIN_AUTH);
        }
        return currentUser.getId();
    }

    private void validateUserAndRoom(Long userId, Long roomId) {
        validateUserAndRoomId(userId, roomId);
        if (roomInfoMapper.selectById(roomId) == null) {
            throw new LeaseException(ResultCodeEnum.DATA_ERROR);
        }
    }

    private void validateUserAndRoomId(Long userId, Long roomId) {
        validateUserId(userId);
        if (roomId == null) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }
    }

    private void validateUserId(Long userId) {
        if (userId == null) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }
    }

    private RoomFavorite findExisting(Long userId, Long roomId) {
        LambdaQueryWrapper<RoomFavorite> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(RoomFavorite::getUserId, userId)
                .eq(RoomFavorite::getRoomId, roomId)
                .last("limit 1");
        return roomFavoriteMapper.selectOne(queryWrapper);
    }

    private void fillRoomGraphs(List<RoomFavoriteItemVo> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<Long> roomIds = records.stream()
                .map(RoomFavoriteItemVo::getRoomId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (roomIds.isEmpty()) {
            return;
        }

        QueryWrapper<GraphInfo> graphQuery = new QueryWrapper<>();
        graphQuery.eq("item_type", ItemType.ROOM.getCode())
                .in("item_id", roomIds)
                .select("item_id", "name", "url");
        Map<Long, List<GraphVo>> roomIdToGraphVoList = graphInfoMapper.selectList(graphQuery).stream()
                .collect(Collectors.groupingBy(
                        GraphInfo::getItemId,
                        Collectors.mapping(
                                graph -> GraphVo.builder().name(graph.getName()).url(graph.getUrl()).build(),
                                Collectors.toList()
                        )
                ));

        for (RoomFavoriteItemVo record : records) {
            record.setRoomGraphVoList(roomIdToGraphVoList.getOrDefault(record.getRoomId(), List.of()));
        }
    }
}
