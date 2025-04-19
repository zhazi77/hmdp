package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.Blog;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.setBlogIsLike(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        // 1. 查询 Blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("博客不存在！");
        }
        // 2. 查询 Blog 作者的详细信息
        queryBlogUser(blog);

        // 3. 查询 Blog 是否被点赞
        setBlogIsLike(blog);
        return Result.ok(blog);
    }

    private void setBlogIsLike(Blog blog) {
        // 1. 获取当前登录用户 id
        UserDTO userDTO = UserHolder.getUser();
        if (userDTO == null) {
            // 用户未登录，不需要设置 isLike
            return;
        }
        blog.setIsLike(isBlogLiked(blog));
    }

    private boolean isBlogLiked(Blog blog) {
        // 1. 获取当前登录用户 id
        Long userId = UserHolder.getUser().getId();
        // 2. 判断当前登录用户是否已经点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        return Boolean.TRUE.equals(score != null);
    }

    // NOTE: 按下点赞按钮时触发的请求，拦截器确保这里用户已经登录
    //       这里不需要考虑 blog 的 isLiked 属性，因为查询点赞的功能调用的不是这个接口
    @Override
    public Result likeBlog(Long id) {
        // 1. 获取 blog
        Blog blog = getById(id);

        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + id;
        if (isBlogLiked(blog)) {
            // 2.1. 已经点赞，则本次取消点赞，设置 blog 点赞数 - 1，设置 isLike = false,
            //      从 redis 中删除这个键
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        } else {
            // 2.2. 未点赞，则本次点赞，设置 blog 点赞数 + 1， 设置 isLike = true
            //      向 redis 中添加这个键
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }
        return Result.ok(blog);
    }

    @Override
    public Result queryBlogLikes(Long id) {
        // 1. 查询 top5 个点赞用户
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(BLOG_LIKED_KEY + id, 0, 4);
        // 2. 判断查询是否为空
        if (top5 == null || top5.isEmpty()) {
            // 2.1 为空
            return Result.ok(Collections.emptyList());
        }

        // 2.2 不为空，解析出用户 id 信息
        List<Long> userIds = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", userIds);

        // 2.2 不为空，转化为 UserDTO 列表
        List<UserDTO> userDTOS = userService.query()
                .in("id", userIds).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
