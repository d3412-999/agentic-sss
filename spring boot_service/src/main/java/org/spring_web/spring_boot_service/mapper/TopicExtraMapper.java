package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.spring_web.spring_boot_service.entity.Topic;

/**
 * 题目容量的原子增减，手写 SQL，绕开 MyBatis-Plus 的 update(null, wrapper) 与乐观锁插件。
 *
 * <p><b>为什么不用「先 SELECT 再 UPDATE」</b>：两个并发事务会同时读到「还有名额」，
 * 各自 +1 造成超卖。下面两条语句把「判断 + 增减」压进同一条 UPDATE 的 WHERE 里，
 * 由 MySQL 行锁串行化执行，返回影响行数 1 = 成功、0 = 条件不满足。</p>
 *
 * <p><b>为什么拆成 reserve / release 两条，而不是一个 delta 参数共用一条 SQL</b>：
 * 两个方向的约束并不对称。预占要卡上限（selected_count &lt; max_select_count）；
 * 释放只要求别减成负数（selected_count &gt; 0）。若共用
 * {@code selected_count + delta BETWEEN 0 AND max_select_count}，一旦教师把上限
 * 调到低于已选人数、或历史数据本就不一致，学生反而退不了选——退选不该被上限拦住。</p>
 *
 * <p>version 手动 +1 是为了让并发的「教师编辑题目」（updateById 带 @Version）能感知到
 * 容量变化，避免后写覆盖前写；updated_at 取数据库时间，避免应用服务器时钟不一致。</p>
 *
 * <p>这两条是自定义 @Update，参数里没有实体对象，MyBatis-Plus 的
 * OptimisticLockerInnerInterceptor 不会改写它们，行为完全可预期。</p>
 */
@Mapper
public interface TopicExtraMapper extends BaseMapper<Topic> {

    /** 原子预占 1 个名额；返回 0 表示名额已满、题目已删除或已被下架。 */
    @Update("""
            UPDATE topic
               SET selected_count = selected_count + 1,
                   version = version + 1,
                   updated_at = NOW(3)
             WHERE id = #{topicId}
               AND deleted = 0
               AND status = 'PUBLISHED'
               AND selected_count < max_select_count
            """)
    int reserveOne(@Param("topicId") long topicId);

    /** 原子释放 1 个名额；返回 0 表示计数已是 0（数据不一致），此时不阻塞退选。 */
    @Update("""
            UPDATE topic
               SET selected_count = selected_count - 1,
                   version = version + 1,
                   updated_at = NOW(3)
             WHERE id = #{topicId}
               AND deleted = 0
               AND selected_count > 0
            """)
    int releaseOne(@Param("topicId") long topicId);
}
