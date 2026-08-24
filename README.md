# 黑马点评 (hm-dianping)

仿大众点评的前后端分离项目，重点演示 Redis 在真实业务中的落地：缓存、分布式锁、秒杀、GEO、Bitmap、Stream 消息队列等。

## 项目描述

围绕 Redis 展开的点评类应用，涵盖 **短信登录、商户缓存、优惠券秒杀、附近商户、用户签到、好友关注、达人探店** 等模块。核心通过 Redis 缓存与分布式锁解决集群下的性能与并发问题：以 Redis 替换 HttpSession 解决 Session 共享，以缓存 + 分布式锁支撑高并发读与「一人一单」秒杀，以 GEO/Bitmap/ZSet/Stream 等结构实现各类社交与地理功能。

## 技术栈

- **后端**：Spring Boot 2.3、Java 8、MyBatis-Plus
- **前端**：前后端分离，静态页面部署于 Nginx
- **存储**：MySQL 8、Redis（Lettuce + 连接池）
- **组件**：Redisson（分布式锁）、Hutool、Lombok、AOP
- **脚本**：Lua（原子扣库存、原子释放锁）
- **部署**：Nginx + Tomcat 集群、MySQL 集群、Redis 集群
