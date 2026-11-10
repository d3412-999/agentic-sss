package org.spring_web.spring_boot_service.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.spring_web.spring_boot_service.entity.GradeItem;

/**
 * 成绩明细 Mapper。
 *
 * <p>grade_item 有 (grade_id, selection_id) 唯一键，而实体又配置了逻辑删除，
 * 重算时如果只做逻辑删除，旧行仍占着唯一键，重新插入会 Duplicate key。
 * 因此重算前用物理删除清空该成绩的明细。</p>
 */
@Mapper
public interface GradeItemMapper extends BaseMapper<GradeItem> {

    /**
     * 物理删除某个成绩下的全部明细。
     */
    @Delete("DELETE FROM grade_item WHERE grade_id = #{gradeId}")
    int deletePhysicallyByGradeId(@Param("gradeId") Long gradeId);
}
