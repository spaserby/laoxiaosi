package com.law.backend.user;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 用户数据访问）
 */
@Mapper
public interface UserMapper {

    @Select("select * from app_user where username = #{username}")
    UserEntity findByUsername(@Param("username") String username);

    @Select("select * from app_user where phone = #{phone}")
    UserEntity findByPhone(@Param("phone") String phone);

    @Select("select * from app_user where id = #{id}")
    UserEntity findById(@Param("id") Long id);

    @Select("select count(*) from app_user where username = #{username}")
    int countByUsername(@Param("username") String username);

    @Select("select count(*) from app_user where phone = #{phone}")
    int countByPhone(@Param("phone") String phone);

    /** 注册插入（回填自增主键） */
    @Insert("insert into app_user(username, password_hash, phone, role, status, created_at)"
            + " values(#{username}, #{passwordHash}, #{phone}, #{role}, #{status}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserEntity user);

    @Update("update app_user set password_hash = #{passwordHash} where id = #{id}")
    int updatePassword(@Param("id") Long id, @Param("passwordHash") String passwordHash);

    @Update("update app_user set role = #{role} where id = #{id}")
    int updateRole(@Param("id") Long id, @Param("role") String role);

    /** 头像选择持久化 */
    @Update("update app_user set avatar = nullif(#{avatar}, '') where id = #{id}")
    int updateAvatar(@Param("id") Long id, @Param("avatar") String avatar);
}
