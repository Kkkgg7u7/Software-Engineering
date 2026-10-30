package com.werewolf;
import com.werewolf.config.ProductionConfigurationGuard;
import org.junit.jupiter.api.Test;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ProductionConfigurationGuardTest {
 final String secret="synthetic-production-test-ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
 void valid(String key,List<String> origins,String password,List<String> profiles){ProductionConfigurationGuard.validate(key,origins,password,profiles);}
 @Test void acceptsExplicitHttpsWithIndependentSecrets(){assertDoesNotThrow(()->valid(secret,List.of("https://jiujiu.example.com"),"synthetic-password-123",List.of("mysql","prod")));}
 @Test void rejectsKnownDevelopmentSecretsAndDemoProfile(){assertThrows(IllegalStateException.class,()->valid("werewolf-dev-secret-key-please-change-in-production-2024",List.of("https://example.com"),"synthetic-password-123",List.of("mysql","prod")));assertThrows(IllegalStateException.class,()->valid(secret,List.of("https://example.com"),"werewolf123",List.of("mysql","prod")));assertThrows(IllegalStateException.class,()->valid(secret,List.of("https://example.com"),"synthetic-password-123",List.of("h2","prod")));}
 @Test void rejectsWildcardHttpAndNonOriginUrls(){for(String origin:List.of("https://*.example.com","http://example.com","https://example.com/path","https://user@example.com","https://example.com?x=1"))assertThrows(IllegalStateException.class,()->valid(secret,List.of(origin),"synthetic-password-123",List.of("mysql","prod")));}
}
