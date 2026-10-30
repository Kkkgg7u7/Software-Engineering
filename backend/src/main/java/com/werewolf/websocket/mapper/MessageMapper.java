package com.werewolf.websocket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.werewolf.websocket.entity.Message;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {
}
