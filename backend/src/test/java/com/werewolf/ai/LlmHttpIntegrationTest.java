package com.werewolf.ai;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.werewolf.ai.dto.*;
import com.werewolf.config.AiProperties;
import com.werewolf.game.domain.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class LlmHttpIntegrationTest {
 HttpServer server;ExecutorService executor;AiService async;final ObjectMapper json=new ObjectMapper();volatile String response;volatile int delay;volatile String auth,request;volatile int calls;
 @BeforeEach void start()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newCachedThreadPool();server.setExecutor(executor);server.createContext("/chat/completions",exchange->{calls++;auth=exchange.getRequestHeaders().getFirst("Authorization");request=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);try{Thread.sleep(delay);byte[] bytes=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content",response)))));exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);}catch(Exception ignored){}finally{exchange.close();}});server.start();}
 @AfterEach void stop(){if(async!=null)async.shutdown();server.stop(0);executor.shutdownNow();}
 AiProperties config(){var p=new AiProperties();p.setEnabled(true);p.setApiKey("synthetic-local-test-key");p.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());p.setTimeoutSeconds(1);return p;}
 LlmAgent agent(AiProperties p){return new LlmAgent(p,new AiJsonParser(json),new RuleAgent(),new ChatCompletionClient(p,json));}
 AiContext context(){var c=new AiContext();c.setMySeatId(1);c.setMyRole("GUARD");c.setMyNickname("guard");c.setPhase("NIGHT");c.setRound(1);return c;}
 @Test void validConfiguredHttpReplyBecomesLegalExtendedRoleDecision()throws Exception{response="{\"action\":\"GUARD_PROTECT\",\"targetId\":2,\"reason\":\"protect\"}";var result=agent(config()).decide(context(),List.of(ActionType.GUARD_PROTECT),List.of(2));assertEquals(AiDecision.Source.LLM,result.source());assertEquals(2,result.targetSeatId());assertEquals("Bearer synthetic-local-test-key",auth);assertFalse(json.readTree(request).path("stream").asBoolean());}
 @Test void forgedActionOrTargetFallsBackToLegalRuleAction(){response="{\"action\":\"HUNTER_SHOOT\",\"targetId\":999}";var result=agent(config()).decide(context(),List.of(ActionType.GUARD_PASS),List.of());assertEquals(AiDecision.Source.RULE_FALLBACK,result.source());assertEquals(ActionType.GUARD_PASS,result.action());}
 @Test void slowHttpTimesOutAndFallsBackWithinBound(){delay=3000;response="{}";long start=System.nanoTime();var result=agent(config()).decide(context(),List.of(ActionType.GUARD_PASS),List.of());long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);assertEquals(AiDecision.Source.RULE_FALLBACK,result.source());assertTrue(elapsed<2500,"HTTP read timeout was ignored: "+elapsed);}
 @Test void asyncSnapshotDoesNotBlockAndBudgetAvoidsAnotherRequest()throws Exception{
  delay=3000;response="{}";var p=config();p.setMaxCallsPerGame(1);async=new AiService(p,new AiContextBuilder(),new RuleAgent(),agent(p));
  var state=new GameState(1L,"LLMTEST");state.setPhase(GamePhase.NIGHT);state.setRound(1);var actor=new PlayerState(1,-1L,"guard",null,true);actor.setRole(RoleType.GUARD);state.getPlayers().add(actor);
  var runtime=new AiRuntimeView(){public GameState state(){return state;}public List<ActionType> legalActions(int seat){return List.of(ActionType.GUARD_PASS);}public List<Integer> legalTargets(PlayerState who,List<ActionType> actions){return List.of();}};
  long start=System.nanoTime();var future=async.request(runtime,actor,List.of(ActionType.GUARD_PASS),0);assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<300,"async request blocked the room");assertFalse(future.isDone());
  state.setRound(99);assertEquals(AiDecision.Source.RULE_FALLBACK,future.get(3,TimeUnit.SECONDS).source());assertTrue(json.readTree(request).path("messages").get(1).path("content").asText().contains("第 1 轮"));
  var capped=async.request(runtime,actor,List.of(ActionType.GUARD_PASS),1);assertTrue(capped.isDone());assertEquals(ActionType.GUARD_PASS,capped.get().action());assertEquals(1,calls);
 }
}
