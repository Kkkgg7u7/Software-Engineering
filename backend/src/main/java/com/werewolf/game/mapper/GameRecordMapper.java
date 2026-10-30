package com.werewolf.game.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.werewolf.game.entity.GameRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GameRecordMapper extends BaseMapper<GameRecord> {
}
