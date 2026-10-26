package com.law.backend.memory;

import com.law.backend.service.SessionOwnershipService;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;

@org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
@EnableTransactionManagement
@Import({PgChatMemory.class, ConversationTurnService.class, ConversationHistoryService.class, SessionOwnershipService.class})
class MemoryTestDatabase {
    @Bean(destroyMethod = "shutdown") EmbeddedDatabase dataSource() {
        return new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
    }
    @Bean PlatformTransactionManager transactionManager(DataSource dataSource) { return new JdbcTransactionManager(dataSource); }
    @Bean SqlSessionTemplate sqlSession(DataSource dataSource) throws Exception {
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(dataSource);
        var sessionFactory = factory.getObject();
        sessionFactory.getConfiguration().setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : new Class<?>[]{MessageMapper.class, SummaryMapper.class, SessionMapper.class})
            sessionFactory.getConfiguration().addMapper(mapper);
        return new SqlSessionTemplate(sessionFactory);
    }
    @Bean MessageMapper messageMapper(SqlSessionTemplate sql) { return sql.getMapper(MessageMapper.class); }
    @Bean SummaryMapper summaryMapper(SqlSessionTemplate sql) { return sql.getMapper(SummaryMapper.class); }
    @Bean SessionMapper sessionMapper(SqlSessionTemplate sql) { return sql.getMapper(SessionMapper.class); }

    static final String SCHEMA = """
        create table chat_session(session_id varchar(64) primary key,title varchar(64) default 'test',
          created_at bigint default 0, updated_at bigint default 0, owner_key varchar(80));
        create table chat_message(id bigserial primary key, session_id varchar(64) not null,
          role varchar(20) not null, content text check(content <> 'reject'),created_at timestamp default current_timestamp,
          turn_id varchar(36),status varchar(20) not null default 'COMPLETE', unique(session_id,turn_id,role));
        create table chat_summary(session_id varchar(64) primary key,content text not null,
          updated_at timestamp default current_timestamp,covered_until_message_id bigint not null default 0,
          version bigint not null default 0,legacy boolean not null default false,lease_token varchar(36),
          lease_until timestamp,failures integer not null default 0,next_attempt_at timestamp not null default current_timestamp);
        """;
}
