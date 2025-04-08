package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private IFollowService followService;

    @Resource
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
        records.forEach(blog ->{
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            //用户是否点赞
            blog.setIsLiked(isBlogLiked(blog));
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);

        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        //用户是否点赞
        blog.setIsLiked(isBlogLiked(blog));
        return Result.ok(blog);
    }

    private Boolean isBlogLiked(Blog blog) {
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, blog.getUserId().toString());
        return score != null;
    }


    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        //判断该用户是否点过赞（redis的zset(由于后面要进行点赞用户的排序)集合中是否有该用户）
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null){
            //未点赞
            // 修改数据库点赞数量+1
            boolean isSuccess = update()
                    .setSql("liked = liked + 1").eq("id", id).update();
            // 将该用户加入redis的set集合中
            if(isSuccess){
                //根据时间戳进行排序
                stringRedisTemplate.opsForZSet().add(key, userId.toString(),System.currentTimeMillis());
            }
        }else{
            //已点赞
            // 修改数据库点赞数量-1
            boolean isSuccess = update()
                    .setSql("liked = liked - 1").eq("id", id).update();
            // 将该用户从redis的set集合中移除
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }

        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Set<String> userIds = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(userIds == null || userIds.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = userIds.stream().map(Long::valueOf).collect(Collectors.toList());
        //不能直接用listById，因为根据数据库查找默认按照id升序返回，故应在代码后加ORDER BY FIELD (id,ids)
        String strIds = StrUtil.join(",", ids);
        List<UserDTO> userDTOS = userService
                .query().in("id",ids).last("order by field (id,"+strIds+")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("添加笔记失败！");
        }
        //查询该用户的粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        follows.forEach(follow -> {
            Long userId = follow.getUserId();
            //收件箱的key
            String key = RedisConstants.FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        });
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Long offset) {
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.FEED_KEY + userId;
        //分页查询
        Set<ZSetOperations.TypedTuple<String>> tupleSet = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if(tupleSet == null || tupleSet.isEmpty()){
            return Result.ok();
        }
        List<Long> ids = new ArrayList<>(tupleSet.size());
        long minTime = 0;
        int off = 1;
        for (ZSetOperations.TypedTuple<String> tuple : tupleSet) {
            String blogIdStr = tuple.getValue();
            ids.add(Long.valueOf(blogIdStr));
            long time = tuple.getScore().longValue();
            if(time == minTime){
                off++;
            }else{
                minTime = time;
                off = 1;
            }
        }
        String strIds = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("order by field (id," + strIds + ")").list();
        blogs.forEach(blog -> {
            User user = userService.getById(blog.getUserId());
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            //用户是否点赞
            blog.setIsLiked(isBlogLiked(blog));
        });
        //返回给前端包含offset 上一次最小score（时间戳）
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(off);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }
}
