package com.werewolf;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class UserProfileMigrationTest {
    private void migrateTwice(Connection connection) throws java.sql.SQLException {
        for (int i = 0; i < 2; i++) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/h2/migration.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/h2/migrations/V002__user_profile.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/h2/migrations/V003__friendship_media.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/h2/migrations/V004__conversations.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/h2/migrations/V005__community.sql"));
            if (connection.getMetaData().getTables(null,null,"ROOMS",null).next()) ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/h2/migrations/V006__game_platform.sql"));
        }
    }
    @Test
    void oldUserDataSurvivesRepeatedUpgrade() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:oldprofile;MODE=MySQL", "sa", "")) {
            c.createStatement().execute("CREATE TABLE users(id BIGINT PRIMARY KEY, username VARCHAR(50), password VARCHAR(120), nickname VARCHAR(50), created_at TIMESTAMP)");
            c.createStatement().execute("INSERT INTO users VALUES(7,'existing','existing-hash','Existing',TIMESTAMP '2024-01-02 03:04:05')");
            migrateTwice(c);
            var result = c.createStatement().executeQuery("SELECT password,signature,updated_at,auth_version FROM users WHERE id=7");
            assertTrue(result.next());
            assertEquals("existing-hash", result.getString(1));
            assertEquals("", result.getString(2));
            assertEquals("2024-01-02 03:04:05.0", result.getTimestamp(3).toString());
            assertEquals(0, result.getInt(4));
        }
    }
    @Test
    void freshSchemaAlsoAllowsRepeatedMigrations() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:newprofile;MODE=MySQL", "sa", "")) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/h2/schema.sql"));
            migrateTwice(c);
            c.createStatement().execute("INSERT INTO users(username,password,nickname) VALUES('new','hash','New')");
            var result = c.createStatement().executeQuery("SELECT signature,updated_at FROM users WHERE username='new'");
            assertTrue(result.next());
            assertEquals("", result.getString(1));
            assertNotNull(result.getTimestamp(2));
        }
    }
    @Test void oldRoomMetadataSurvivesRepeatedPlatformUpgrade() throws Exception {
        try(Connection c=DriverManager.getConnection("jdbc:h2:mem:oldroomplatform;MODE=MySQL","sa","")){
            c.createStatement().execute("CREATE TABLE rooms(id BIGINT PRIMARY KEY,room_name VARCHAR(30),password VARCHAR(120))");
            c.createStatement().execute("INSERT INTO rooms VALUES(42,'existing room','hashed-secret')");
            for(int i=0;i<2;i++){ScriptUtils.executeSqlScript(c,new ClassPathResource("db/h2/migrations/V006__game_platform.sql"));ScriptUtils.executeSqlScript(c,new ClassPathResource("db/h2/migrations/V007__room_voice.sql"));ScriptUtils.executeSqlScript(c,new ClassPathResource("db/h2/migrations/V008__room_role_counts.sql"));}
            var row=c.createStatement().executeQuery("SELECT room_name,password,game_type,allow_invites,private_chat_enabled,voice_enabled,role_counts FROM rooms WHERE id=42");
            assertTrue(row.next());assertEquals("existing room",row.getString(1));assertEquals("hashed-secret",row.getString(2));assertEquals("WEREWOLF",row.getString(3));assertTrue(row.getBoolean(4));assertTrue(row.getBoolean(5));assertFalse(row.getBoolean(6));assertNull(row.getString(7));
        }
    }

}
