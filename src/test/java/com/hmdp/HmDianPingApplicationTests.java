package com.hmdp;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RandomPhoneNumber;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.RegexUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import javax.annotation.Resource;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
@AutoConfigureMockMvc
public class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private CacheClient cacheClient;
    @Resource
    private RedisIdWorker redisIdWorker;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserServiceImpl userService;

    private ExecutorService es = Executors.newFixedThreadPool(500);
    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }

    @Test
    void testSaveShop() throws InterruptedException {
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
    }

    // NOTE: 使用前要记得修改 UserServiceImpl 中的 sendCode 方法，让它返回 code
    @Test
    public void createUserBy1000() {
        // NOTE: 生成的号码不一定合规，简单起见，直接跑两遍
        List<String> phones = RandomPhoneNumber.randomCreatePhone(1000);
        phones.stream().forEach(phone -> {
            if (!RegexUtils.isPhoneInvalid(phone)) {
                User login_user = new User();
                login_user.setPhone(phone);
                login_user.setCreateTime(LocalDateTime.now());
                login_user.setUpdateTime(LocalDateTime.now());
                String nickName_suf = RandomUtil.randomString(10);
                login_user.setNickName("user_" + nickName_suf);
                userService.save(login_user);
            }
        });
    }
    @Test
    public void tokenBy1000() throws Exception {
        String phone = "";
        String code = "";
        //注意！这里的绝对路径设置为自己想要的地方
        OutputStreamWriter osw = null;
        osw = new OutputStreamWriter(new FileOutputStream("D:\\workspace\\temp\\token.txt"));
        //先模拟10个用户的登录
        for (int i = 1; i < 1000; i++) {
            User user = userService.getById(i);
            phone = user.getPhone();
            //创建虚拟请求，模拟通过手机号，发送验证码
            ResultActions perform1 = mockMvc.perform(MockMvcRequestBuilders
                    .post("/user/code?phone=" + phone));
            //获得Response的body信息
            String resultJson1 = perform1.andReturn().getResponse().getContentAsString();
            //将结果转换为result对象
            Result result = JSONUtil.toBean(resultJson1, Result.class);
            //获得验证码
            code = result.getData().toString();
            //创建登录表单
            LoginFormDTO loginFormDTO = new LoginFormDTO();
            loginFormDTO.setCode(code);
            loginFormDTO.setPhone(phone);
            //将表单转换为json格式的字符串
            String loginFormDtoJson = JSONUtil.toJsonStr(loginFormDTO);
            //创建虚拟请求，模拟登录
            ResultActions perform2 = mockMvc.perform(MockMvcRequestBuilders.post("/user/login")
                    //设置contentType表示为json信息
                    .contentType(MediaType.APPLICATION_JSON)
                    //放入json对象
                    .content(loginFormDtoJson));
            String resultJson2 = perform2.andReturn().getResponse().getContentAsString();
            Result result2 = JSONUtil.toBean(resultJson2, Result.class);
            //获得token
            String token = result2.getData().toString();
            //写入
            osw.write(token+"\n");
        }
        //关闭输出流
        osw.close();
    }
}
