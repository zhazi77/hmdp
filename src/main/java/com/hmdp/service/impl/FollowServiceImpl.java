package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    IUserService userService;

    @Override
    public Result follow(Long followId, Boolean isFollow) {
        // 1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();

        String key = "follows:" + userId;
        // 2. 判断当前用户操作
        if (isFollow) {
            // 3. 要关注
            boolean isSuccess = save(new Follow().setUserId(userId).setFollowUserId(followId));
            if (isSuccess) {
                // 把关注关系写入 Redis (方便后面判断共同关注）
                stringRedisTemplate.opsForSet().add(key, followId.toString());
            }
        } else {
            // 4. 要取消关注
            boolean isSuccess = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followId));
            if (isSuccess) {
                // 把关注关系从 Redis 中删除
                stringRedisTemplate.opsForSet().remove(key, followId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followId) {
        // 1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();

        // 2. 查询当前用户是否已经关注了 followId 的用户
        Long count = query().eq("user_id", userId).eq("follow_user_id", followId).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        // 1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();

        // 2. 获取两个用户关注的交集
        Set<String> interSet = stringRedisTemplate.opsForSet().union("follows:" + userId, "follows:" + id);
        if (interSet == null || interSet.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 3. 解析 id 集合
        List<Long> ids = interSet.stream().map(Long::valueOf).collect(Collectors.toList());

        // 4. 查询用户信息（这回不需要处理顺序了）
        List<UserDTO> userDTOS = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
