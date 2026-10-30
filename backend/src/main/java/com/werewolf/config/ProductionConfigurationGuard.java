package com.werewolf.config;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import java.net.URI;
import java.util.*;
/** Reject known development configuration before a production server starts serving. Never log values. */
@Component @Profile("prod")
public class ProductionConfigurationGuard implements InitializingBean {
 private final AppProperties properties;private final Environment environment;
 public ProductionConfigurationGuard(AppProperties properties,Environment environment){this.properties=properties;this.environment=environment;}
 public void afterPropertiesSet(){
  validate(properties.getJwt().getSecret(),properties.getCors().getAllowedOrigins(),environment.getProperty("spring.datasource.password"),Arrays.asList(environment.getActiveProfiles()));
 }
 public static void validate(String secret,List<String> origins,String databasePassword,List<String> profiles){
  if(secret==null||secret.length()<48||secret.contains("please-change")||secret.contains("replace-")||secret.chars().distinct().count()<16)throw new IllegalStateException("生产环境必须配置独立随机 WEREWOLF_JWT_SECRET（至少 48 字符）");
  if(profiles.contains("h2"))throw new IllegalStateException("生产环境不可启用含演示账号的 h2 profile");
  if(databasePassword==null||databasePassword.length()<16||Set.of("werewolf123","root123456").contains(databasePassword))throw new IllegalStateException("生产环境必须配置独立 MYSQL_PASSWORD（至少 16 字符）");
  if(origins==null||origins.isEmpty())throw new IllegalStateException("生产环境必须配置精确 HTTPS 来源");
  for(String origin:origins)try{URI uri=URI.create(origin);if(origin.contains("*")||!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!(uri.getPath()==null||uri.getPath().isEmpty()))throw new IllegalArgumentException();}catch(Exception e){throw new IllegalStateException("生产环境 WEREWOLF_CORS_ORIGINS 必须为精确 HTTPS origin，不可使用通配符");}
 }
}
