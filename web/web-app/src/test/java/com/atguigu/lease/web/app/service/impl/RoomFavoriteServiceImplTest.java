package com.atguigu.lease.web.app.service.impl;

import com.atguigu.lease.common.login.LoginUser;
import com.atguigu.lease.model.entity.RoomFavorite;
import com.atguigu.lease.model.entity.RoomInfo;
import com.atguigu.lease.web.app.mapper.GraphInfoMapper;
import com.atguigu.lease.web.app.mapper.RoomFavoriteMapper;
import com.atguigu.lease.web.app.mapper.RoomInfoMapper;
import com.atguigu.lease.web.app.vo.favorite.RoomFavoriteItemVo;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoomFavoriteServiceImplTest {

    private final RoomFavoriteMapper roomFavoriteMapper = mock(RoomFavoriteMapper.class);
    private final RoomInfoMapper roomInfoMapper = mock(RoomInfoMapper.class);
    private final GraphInfoMapper graphInfoMapper = mock(GraphInfoMapper.class);

    private final RoomFavoriteServiceImpl service = new RoomFavoriteServiceImpl(
            roomFavoriteMapper,
            roomInfoMapper,
            graphInfoMapper
    );

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldSaveFavoriteOnceForCurrentUser() {
        useLoginUser(7L);
        when(roomInfoMapper.selectById(3L)).thenReturn(new RoomInfo());
        when(roomFavoriteMapper.selectOne(any())).thenReturn(null);

        service.saveFavorite(3L);

        ArgumentCaptor<RoomFavorite> captor = ArgumentCaptor.forClass(RoomFavorite.class);
        verify(roomFavoriteMapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(7L);
        assertThat(captor.getValue().getRoomId()).isEqualTo(3L);
    }

    @Test
    void shouldIgnoreDuplicateFavorite() {
        useLoginUser(7L);
        RoomFavorite existing = new RoomFavorite();
        existing.setId(10L);
        existing.setUserId(7L);
        existing.setRoomId(3L);
        when(roomInfoMapper.selectById(3L)).thenReturn(new RoomInfo());
        when(roomFavoriteMapper.selectOne(any())).thenReturn(existing);

        service.saveFavorite(3L);

        verify(roomFavoriteMapper, never()).insert(any(RoomFavorite.class));
    }

    @Test
    void shouldReturnFavoriteRoomIdsForBatchStatus() {
        when(roomFavoriteMapper.selectFavoriteRoomIds(7L, List.of(3L, 5L, 8L))).thenReturn(List.of(5L, 8L));

        Set<Long> result = service.favoriteRoomIds(7L, List.of(3L, 5L, 8L));

        assertThat(result).containsExactly(5L, 8L);
    }

    @Test
    void shouldPageCurrentUserFavorites() {
        useLoginUser(7L);
        Page<RoomFavoriteItemVo> page = new Page<>(1, 10);
        Page<RoomFavoriteItemVo> resultPage = new Page<>(1, 10);
        RoomFavoriteItemVo item = new RoomFavoriteItemVo();
        item.setRoomId(3L);
        resultPage.setRecords(List.of(item));
        when(roomFavoriteMapper.pageItem(page, 7L)).thenReturn(resultPage);
        when(graphInfoMapper.selectList(any())).thenReturn(List.of());

        IPage<RoomFavoriteItemVo> result = service.pageItem(page);

        assertThat(result.getRecords()).singleElement().extracting(RoomFavoriteItemVo::getRoomId).isEqualTo(3L);
        verify(roomFavoriteMapper).pageItem(page, 7L);
    }

    private void useLoginUser(Long userId) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                new LoginUser(userId, "user-" + userId),
                null,
                List.of()
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
