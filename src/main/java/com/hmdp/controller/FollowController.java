package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    @PutMapping("/{followUserId}/{isFollowed}")
    public Result follow(@PathVariable("followUserId") Long followUserId,@PathVariable("isFollowed") boolean isFollowed){
        return followService.follow(followUserId,isFollowed);
    }

    @GetMapping("/or/not/{followUserId}")
    public Result isFollow(@PathVariable("followUserId") Long followUserId){
        return followService.isFollow(followUserId);
    }

    @GetMapping("common/{id}")
    public Result common(@PathVariable("id") Long id){
        return followService.common(id);
    }
}
